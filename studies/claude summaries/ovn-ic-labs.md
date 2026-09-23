# OVN-IC — Guia completo dos laboratórios `pratice-ovn-ic` e `advanced-ovn-ic`

> Documento de estudo para dominar 100% dos dois labs em `projects/ovn-ic`.
> Cobre fundamentos, arquitetura, cada linha relevante dos scripts e das roles,
> os 16 *gotchas*, os resultados e um banco de perguntas e respostas.
>
> **Estado do código:** corresponde à revisão **dual-plane** do `advanced-ovn-ic`
> — dois planes (client e mgmt), dois transit switches, cluster RAFT de 3 membros
> para os bancos IC, e aplicação React (AZ1) + Java (AZ2) + PostgreSQL. O
> `pratice-ovn-ic` não mudou desde a primeira versão.

---

## Índice

1. [Fundamentos: OVN em uma AZ](#1-fundamentos-ovn-em-uma-az)
2. [Fundamentos: o que o OVN-IC acrescenta](#2-fundamentos-o-que-o-ovn-ic-acrescenta)
3. [Lab 1 — `pratice-ovn-ic` (CLO-77)](#3-lab-1--pratice-ovn-ic-clo-77)
4. [Lab 2 — `advanced-ovn-ic` (CLO-73), revisão dual-plane](#4-lab-2--advanced-ovn-ic-clo-73-revisão-dual-plane)
5. [Os 16 gotchas, explicados a fundo](#5-os-16-gotchas-explicados-a-fundo)
6. [As três gerações do lab, lado a lado](#6-as-três-gerações-do-lab-lado-a-lado)
7. [Cheat-sheet de verificação e troubleshooting](#7-cheat-sheet-de-verificação-e-troubleshooting)
8. [Banco de perguntas e respostas](#8-banco-de-perguntas-e-respostas)
9. [Inconsistências encontradas no repositório](#9-inconsistências-encontradas-no-repositório)

---

## 1. Fundamentos: OVN em uma AZ

Antes de falar de federação, é preciso ter o modelo de uma AZ isolada na ponta da língua.

### 1.1 Os componentes

| Componente | Papel | Onde roda |
|---|---|---|
| **NB-DB** (Northbound) | Banco OVSDB com a **intenção**: logical switches, logical routers, portas, ACLs, NAT, load balancers. É o que o operador/CMS escreve. | central (porta 6641) |
| **`ovn-northd`** | Compilador: lê o NB e **traduz** para *logical flows* no SB. Não fala com hipervisor nenhum. | central |
| **SB-DB** (Southbound) | Banco OVSDB com o **resultado compilado**: `Logical_Flow`, `Port_Binding`, `Chassis`, `Encap`, `Datapath_Binding`. | central (porta 6642) |
| **`ovn-controller`** | Agente em cada hipervisor (*chassis*). Lê o SB, traduz logical flows em **OpenFlow** e programa o `br-int` local. Também registra o chassis na tabela `Chassis` e cria os túneis. | cada chassis |
| **`ovs-vswitchd` / `br-int`** | O datapath de verdade. `br-int` é a bridge de integração onde as portas dos workloads e as portas de túnel ficam. | cada chassis |

### 1.2 O pipeline mental

```
operador → ovn-nbctl → NB-DB → ovn-northd → SB-DB → ovn-controller → OpenFlow → br-int → pacote
             (intenção)          (compila)     (flows lógicos)        (flows físicos)
```

Isso é o que se chama de **NB → SB → datapath**. Toda a depuração do OVN é "em qual dessas
camadas a informação parou de descer".

### 1.3 Como um chassis é configurado

Tudo vive em `external_ids` da tabela `Open_vSwitch` do OVS local:

```bash
ovs-vsctl set open_vswitch . \
  external_ids:system-id=az1-chassis          `# nome do chassis na tabela Chassis do SB` \
  external_ids:ovn-remote=tcp:127.0.0.1:6642  `# onde está o SB` \
  external_ids:ovn-encap-type=geneve          `# tipo de encapsulamento` \
  external_ids:ovn-encap-ip=172.18.3.175      `# IP de origem do túnel (underlay)` \
  external_ids:ovn-is-interconn=true          `# habilita participação no OVN-IC`
```

O `ovn-controller` lê isso, cria a linha em `Chassis`/`Encap` no SB, e **automaticamente**
sobe uma porta de túnel GENEVE para cada outro chassis que ele vê no SB.

### 1.4 Como uma porta é "ligada"

Um *logical switch port* (LSP) no NB é só um objeto. Ele vira tráfego real quando uma
interface do OVS carrega o `iface-id` correspondente:

```bash
ovs-vsctl add-port br-int veth-app1 \
  -- set interface veth-app1 external_ids:iface-id=lsp-app-vm-1
```

O `ovn-controller` vê o `iface-id`, encontra o LSP homônimo, e preenche
`Port_Binding.chassis` no SB com o seu próprio chassis. **É esse binding que diz ao resto
da malha em qual chassis aquele IP/MAC está** — e, portanto, para onde encapsular.

---

## 2. Fundamentos: o que o OVN-IC acrescenta

### 2.1 O problema

Uma instalação OVN = uma *Availability Zone*. Esticar um único control plane por várias
regiões é ruim (escala, latência de OVSDB, blast radius). O OVN-IC resolve isso mantendo
**cada AZ com seu próprio NB/SB/northd/chassis** e adicionando uma camada **aditiva** de
federação.

### 2.2 Os componentes novos

| Componente | Papel |
|---|---|
| **IC-NB** (porta 6645) | Banco **global** com a *intenção da federação*: quais **transit switches** existem, quais AZs participam. Tabela principal: `Transit_Switch`, `Availability_Zone`. |
| **IC-SB** (porta 6646) | Banco **global** com o *estado de runtime*: `Gateway` (chassis registrados por AZ), `Route` (rotas anunciadas/aprendidas), `Port_Binding`, `Encap`. |
| **`ovn-ic`** (daemon, 1 por AZ) | A ponte. Conecta simultaneamente a **IC-NB + IC-SB** (globais) e ao **NB + SB locais**. Propaga o transit switch para o NB local, registra o gateway da AZ no IC-SB, anuncia rotas locais e injeta rotas aprendidas no NB local. |
| **Transit switch** | Logical switch declarado **só** no IC-NB. O `ovn-ic` o materializa em cada NB local com a anotação `interconn-ts`. Funciona como o *backbone* L2 compartilhado entre os routers das AZs. |
| **Gateway chassis** | O chassis de cada AZ que termina os túneis GENEVE vindos das outras AZs. Marcado com `ovn-is-interconn=true` **e** fixado na LRP de trânsito. |

### 2.3 O fluxo de federação, passo a passo

```
1. ovn-ic lê NB_Global.name          → descobre que esta AZ se chama "az1"
2. ovn-ic registra Availability_Zone "az1" no IC-SB
3. ovn-ic vê chassis com ovn-is-interconn=true → cria a linha Gateway no IC-SB
4. ovn-ic lê Transit_Switch "ts" no IC-NB      → cria o LS "ts" no NB local
5. operador conecta lr-az1 ao "ts" (lsp-ts-az1 ↔ lrp-az1-ts)
6. ovn-ic vê lrp-az1-ts com gateway chassis    → publica o Port_Binding no IC-SB
7. ovn-ic (route adv) publica 10.10.1.0/24 no IC-SB como rota de az1
8. ovn-ic da az2 (route learn) lê essa rota    → injeta em lr-az2 como "(learned)"
9. ovn-controller de az2 vê o Port_Binding remoto → sobe o túnel GENEVE para o encap-ip de az1
```

Se IC-NB/IC-SB caírem, **o tráfego intra-AZ continua**, e o inter-AZ também continua
enquanto nada mudar (as rotas já estão programadas no SB/OpenFlow). O que para são as
**mudanças de topologia**.

### 2.4 O caminho de um pacote inter-AZ

`app-vm-1` (10.10.1.10, AZ1) → `db-vm` (10.10.2.10, AZ2):

```
1.  container escreve o pacote em eth0 (MTU 1442)
2.  veth → br-int (AZ1); ovn-controller aplica o pipeline do ls-az1
3.  destino não é local → sai pelo lsp-az1-router para lr-az1
4.  lr-az1 consulta rotas: 10.10.2.0/24 via 169.254.100.2 (learned)  → next-hop na LRP de trânsito
5.  pacote entra no datapath do transit switch "ts"
6.  o Port_Binding de lsp-ts-az2 aponta para az2-chassis
7.  br-int encapsula em GENEVE (UDP 6081), src 172.18.3.175 → dst 172.18.33.126
8.  underlay entrega; br-int da AZ2 desencapsula
9.  lr-az2 roteia 10.10.2.10 para ls-az2 → Port_Binding local de lsp-db-vm
10. veth → eth0 do db-vm
```

**Overhead GENEVE = 58 bytes** (IP 20 + UDP 8 + Geneve 8 + opções 8 + Ethernet interna 14).
Por isso `1500 − 58 = 1442` de MTU no workload.

### 2.5 Duas generalizações que o lab 2 usa

O modelo acima descreve **um** transit switch e **um** banco IC. A revisão atual do
`advanced-ovn-ic` generaliza os dois pontos, e vale fixar isso desde já:

**N transit switches.** Nada no OVN-IC limita a federação a um transit switch. Cada
`Transit_Switch` declarado no IC-NB vira um **datapath lógico independente**, propagado
para o NB de cada AZ. Um router só aprende rotas do transit switch em que ele tem porta —
é assim que dois planes ficam isolados usando o **mesmo** túnel GENEVE. O isolamento é
lógico (datapaths distintos), não físico (fabric distinto).

**IC-NB/IC-SB em cluster RAFT.** Um `ovsdb-server` pode rodar em modo cluster. Cada banco
passa a usar **duas portas**: a de cliente (onde os `ovn-ic` conectam) e a de **peer RAFT**
(replicação entre membros). Um cluster serve leitura em qualquer membro e **redireciona
escrita para o líder**, então os clientes podem listar todos os membros e continuam
funcionando quando um cai.

Por que **três** membros e não dois: RAFT exige **maioria estrita** para eleger líder e
confirmar escrita. Maioria de 2 é 2 — perder qualquer membro congela o banco, sem ganho
sobre um nó só. Maioria de 3 é 2 — o cluster sobrevive à perda de um. É exatamente para
ser esse terceiro voto que existe um nó **árbitro**, sem chassis e sem workloads.


---

## 3. Lab 1 — `pratice-ovn-ic` (CLO-77)

**Pergunta que ele responde:** "OVN-IC funciona de verdade entre duas instalações OVN
independentes?" — provado com **ICMP**.

### 3.1 As duas fases

| Fase | Como | Por que foi abandonada / mantida |
|---|---|---|
| **Fase 1** — VM única | Duas AZs simuladas só com *network namespaces*; cada namespace com seu `ovs-vswitchd` + `ovn-controller`. | Validou a topologia lógica de graça, mas **mascara o datapath**: o túnel GENEVE se resolve dentro do mesmo kernel, não há firewall entre chassis, MTU/encap não são exercitados e bugs de sincronização entre instâncias separadas de `ovn-controller` ficam escondidos. |
| **Fase 2** — duas VMs Ubuntu 24.04 | **AZ1 = `172.18.3.181`**, **AZ2 = `172.18.17.9`**. IC-NB/IC-SB hospedados na VM1, acessados por TCP a partir da VM2. | Arquitetura equivalente à de produção. É esta que os scripts implementam. |

### 3.2 Topologia lógica

```
            IC-NB (6645)  +  IC-SB (6646)      ← só na VM1 (az1)
                    │                │
        ovn-ic(az1) ┘                └ ovn-ic(az2)   ← az2 disca 172.18.3.181
  ════════ AZ1 (172.18.3.181) ════════║════════ AZ2 (172.18.17.9) ════════
   ls-az1  10.0.1.0/24                ║          ls-az2  10.0.2.0/24
    ├ lsp-vm1-az1  10.0.1.10          ║           ├ lsp-vm1-az2  10.0.2.10
    ├ lsp-vm2-az1  10.0.1.20          ║           ├ lsp-vm2-az2  10.0.2.20
    └ lsp-az1-router ──┐              ║           └ lsp-az2-router ──┐
   lr-az1              │              ║          lr-az2              │
    ├ lrp-az1-ls  10.0.1.1/24  ───────┘           ├ lrp-az2-ls  10.0.2.1/24 ─┘
    └ lrp-az1-ts  169.254.100.1/24 ●═══ ts ═══● lrp-az2-ts  169.254.100.2/24
           (gateway chassis: az1-chassis)  (gateway chassis: az2-chassis)
                           GENEVE UDP 6081 entre 172.18.3.181 ↔ 172.18.17.9
```

Workloads = **network namespaces** (`vm1-az1`, `vm2-az1`, `vm1-az2`, `vm2-az2`) ligados ao
`br-int` por *veth pairs*.

### 3.3 Endereçamento

| Plano | AZ1 | AZ2 | Compartilhado |
|---|---|---|---|
| Underlay (VM) | `172.18.3.181` | `172.18.17.9` | GENEVE UDP 6081 |
| Subrede lógica | `10.0.1.0/24` (gw `.1`) | `10.0.2.0/24` (gw `.1`) | `ts` → `169.254.100.0/24` |
| Workloads | `.10` e `.20` | `.10` e `.20` | LRP-ts: az1 `.1` / az2 `.2` |
| MACs | `00:00:00:01:xx:xx` | `00:00:00:02:xx:xx` | — |

### 3.4 `setup-az1.sh` — anatomia (322 linhas)

O script é **idempotente por força bruta**: limpa tudo antes de recriar.

#### Bloco 1 — CLEANUP (linhas 11-57)
Em ordem, e a ordem importa:
1. Mata `ovn-ic` pelo pidfile do *rundir* padrão do build de fonte (`/usr/local/var/run/ovn/`).
2. Mata processos pelos pidfiles do lab (`$AZ1_DIR/*.pid`, `$IC_DIR/*.pid`).
3. `pkill` por nome (`ovn-northd`, `ovn-controller`, os quatro `ovsdb-server`, `ovn-ic`).
4. `fuser -k` nas portas 6641/6642/6645/6646 — garante que nada segurou o socket.
5. **Remove as portas do OVS ANTES de destruir os namespaces.** Se você apagar o namespace
   primeiro, a veth some e o OVS fica com uma porta órfã apontando para nada.
6. `ip netns del` (o que também destrói o par veth) e depois `ip link del` para sobras.
7. `rm -rf` dos diretórios do lab.

#### Bloco 2 — DEPENDÊNCIAS (linhas 59-74)
Instala `openvswitch-switch`, `ovn-central`, `ovn-host` **e a toolchain de compilação**
(`git automake autoconf libtool make gcc libssl-dev libcap-ng-dev python3-dev python3-pip
python3-sphinx libunbound-dev`).

Depois **para e desabilita** as units do apt (`ovn-central`, `ovn-host`,
`ovn-ovsdb-server-nb`, `ovn-ovsdb-server-sb`) — elas disputariam as mesmas portas/sockets
com os daemons que o script sobe à mão. Só o `openvswitch-switch` fica ativo.

#### Bloco 3 — COMPILAÇÃO (linhas 76-116)
```bash
git clone --depth=1 --branch v3.3.0 https://github.com/openvswitch/ovs.git
./boot.sh && ./configure && make -j$(nproc)

git clone --depth=1 --branch v24.03.6 https://github.com/ovn-org/ovn.git
./boot.sh && ./configure --with-ovs-source=../ovs --with-ovs-build=../ovs && make -j$(nproc)
```
**Por que compilar:** o pacote `ovn-central` do Ubuntu 24.04 **não entrega** o binário
`ovn-ic` nem os schemas `ovn-ic-nb.ovsschema` / `ovn-ic-sb.ovsschema`.
**Por que OVS fixado em v3.3.0:** a branch `main` do OVS renomeou `obs_domain_id` →
`obs_domain_imm`, o que quebra o build do OVN 24.03.

Depois localiza os artefatos com `find` e copia `ovn-ic`, `ovn-ic-nbctl`, `ovn-ic-sbctl`
para `/usr/local/bin`. Aborta se não achar binário ou schema.

#### Bloco 4 — BANCOS (linhas 122-158)
Quatro `ovsdb-server` independentes, cada um com seu `.db`, `.ctl`, `.pid` e `.log`:

| Banco | Schema | `--remote` | Observação |
|---|---|---|---|
| IC-NB | `ovn-ic-nb.ovsschema` (do build) | `ptcp:6645` | **sem restrição de IP** → az2 alcança |
| IC-SB | `ovn-ic-sb.ovsschema` (do build) | `ptcp:6646` | idem |
| NB az1 | `/usr/share/ovn/ovn-nb.ovsschema` (apt) | `ptcp:6641:127.0.0.1` | **privado da AZ** |
| SB az1 | `/usr/share/ovn/ovn-sb.ovsschema` (apt) | `ptcp:6642:127.0.0.1` | **privado da AZ** |

Repare no contraste: **os bancos locais são localhost-only; os IC são expostos.** É
exatamente isso que faz a AZ ser autônoma.

#### Bloco 5 — `ovn-northd` + chassis + `ovn-controller` (linhas 160-186)
`ovn-northd` apontando para `tcp:127.0.0.1:6641` e `:6642`. Depois o `ovs-vsctl set
open_vswitch .` com `system-id=az1-chassis`, `ovn-encap-ip=172.18.3.181` e
`ovn-is-interconn=true`. Então `ovn-controller --detach` (ele descobre tudo pelo OVS local).

#### Bloco 6 — IDENTIDADE DA AZ (linhas 188-195) — **gotcha #1 e #4**
```bash
ovn-nbctl set NB_Global . name=az1 options:ic-route-adv=true options:ic-route-learn=true
```

#### Bloco 7 — `ovn-ic` (linhas 197-206)
Quatro conexões ao mesmo tempo: `--ic-nb-db`, `--ic-sb-db` (globais, aqui em localhost
porque az1 é o host dos bancos IC) e `--ovnnb-db`, `--ovnsb-db` (locais).

#### Bloco 8 — TOPOLOGIA (linhas 210-261)
Ordem deliberada:
1. `ls-add ls-az1` + dois LSPs com `lsp-set-addresses` e `lsp-set-port-security`.
2. `lr-add lr-az1` + `lrp-add lrp-az1-ls 00:00:00:01:ff:01 10.0.1.1/24` (o gateway).
3. Porta de patch `lsp-az1-router` (type `router`, addresses `router`,
   `options:router-port=lrp-az1-ls`) — é assim que switch e router se ligam no OVN.
4. **`lrp-add lrp-az1-ts ... 169.254.100.1/24` antes do `ts-add`** — a LRP precisa existir
   para o `ovn-ic` conseguir linkar `lsp-ts-az1` quando ele aparecer.
5. **`lrp-set-gateway-chassis lrp-az1-ts az1-chassis 1`** — gotcha #5.
6. `ovn-ic-nbctl ts-add ts` → cria o transit switch **no IC-NB**, nunca no NB local.
7. **Loop de espera** (30 tentativas × 2s) até `ts` aparecer no `ls-list` do NB local. Se
   não aparecer em 60s, aborta e manda olhar `ovn-ic.log`.
8. `lsp-add ts lsp-ts-az1` + type/addresses/`router-port=lrp-az1-ts`.

#### Bloco 9 — RESTART do `ovn-ic` (linhas 263-277) — **gotcha #3**
Mata e sobe de novo o `ovn-ic`, para ele reler a topologia completa de um estado limpo.

#### Bloco 10 — NAMESPACES (linhas 279-303)
Para cada VM simulada, o padrão é sempre o mesmo:
```bash
ip netns add vm1-az1
ip link add veth-vm1-az1 type veth peer name veth-vm1-az1-ns
ip link set veth-vm1-az1-ns netns vm1-az1
ip netns exec vm1-az1 ip link set veth-vm1-az1-ns address 00:00:00:01:00:01  # MAC = o do LSP
ip netns exec vm1-az1 ip addr add 10.0.1.10/24 dev veth-vm1-az1-ns
ip netns exec vm1-az1 ip route add default via 10.0.1.1                      # gw = a LRP
ovs-vsctl add-port br-int veth-vm1-az1 \
  -- set interface veth-vm1-az1 external_ids:iface-id=lsp-vm1-az1            # o binding
```
O **MAC tem que bater** com o `lsp-set-addresses`, senão o *port security* derruba o tráfego.

#### Bloco 11 — FIREWALL (linhas 305-308)
`ufw allow from 172.18.17.9` para as portas **6645/tcp** (IC-NB), **6646/tcp** (IC-SB) e
**6081/udp** (GENEVE).

#### Bloco 12 — VERIFICAÇÃO (linhas 310-322)
Dorme 15s e imprime `ovn-ic-nbctl show`, `ovn-ic-sbctl list Gateway` e `ovs-vsctl show`.

### 3.5 `setup-az2.sh` — o que muda

| Aspecto | az1 | az2 |
|---|---|---|
| Bancos IC | **cria e hospeda** IC-NB/IC-SB | **não cria**; disca `tcp:172.18.3.181:6645/6646` |
| Portas liberadas no cleanup | 6641, 6642, 6645, 6646 | só 6641, 6642 |
| `ts-add` | **sim** (cria o transit switch no IC-NB) | **não** (o `ts` já existe globalmente) |
| Ordem `ovn-ic` × topologia | sobe `ovn-ic` → cria topologia → `ts-add` → espera → conecta → **restart** | cria topologia (incl. `lrp-az2-ts` + gateway chassis) → **depois** sobe `ovn-ic` → espera `ts` → conecta → **restart** |
| Firewall | libera 6645, 6646 e 6081 | libera só 6081 |
| `NB_Global.name` | `az1` | `az2` |
| Schemas IC | instala do build | também compila, mas só precisa dos binários |

A razão da ordem diferente: em az2 a LRP de trânsito já pode existir **antes** do `ovn-ic`
subir, o que elimina metade da corrida do gotcha #3 (o restart no fim cobre o resto).

### 3.6 `verify.sh`

Detecta em qual AZ está (`ip netns list | grep vm1-az1`) e imprime, sempre apontando os
bancos IC para `172.18.3.181`:

- `ovn-ic-nbctl show` / `ovn-ic-sbctl list Gateway` / `ovn-ic-sbctl list Route`
- `ovn-nbctl lr-list` e `lr-route-list lr-az{1,2}` (aqui aparecem as rotas `(learned)`)
- `ovs-vsctl show | grep -A3 geneve` (os túneis)
- `ovn-sbctl list Port_Binding | grep -E '^(logical_port|chassis|encap)'`
- Pings: 1 intra-AZ e 2 inter-AZ, com `-c3 -W2`

### 3.7 Resultado

- Gateways `az1-chassis` e `az2-chassis` registrados no IC-SB.
- Túneis GENEVE estabelecidos entre as duas VMs.
- `10.0.1.0/24` e `10.0.2.0/24` aparecem como **`(learned)`** na AZ oposta.
- Pings intra-AZ (`vm1-az1↔vm2-az1`, `vm1-az2↔vm2-az2`) e inter-AZ
  (`vm1-az1↔vm1-az2`, `vm1-az1↔vm2-az2`, `vm1-az2↔vm2-az1`) com **0% de perda**.

---

## 4. Lab 2 — `advanced-ovn-ic` (CLO-73), revisão dual-plane

**Pergunta que ele responde:** "workloads em nuvens Incus diferentes, federadas por
OVN-IC, rodando uma carga útil de verdade" — provado com uma **aplicação de três camadas
partida entre as AZs**, de modo que *usar o sistema já é o teste da interconexão*.

É uma adaptação reduzida das arquiteturas de produção da Magalu (`dd-azdc-architecture`,
`dd-xaas-network`), e implementa a especificação desenhada em `advanced-ovn-ic.pdf`.

### 4.1 As três propriedades estruturais

Além de ir além do ping, a revisão atual reproduz três propriedades do desenho de produção:

| # | Propriedade | Como é implementada |
|---|---|---|
| 1 | **Dois planes isolados** | um *client* (tenant) e um *mgmt* (operador), cada um federado pelo **seu próprio** transit switch, sobre um único fabric GENEVE. O banco vive só no mgmt e é **inalcançável** do tenant por construção. |
| 2 | **Um edge por célula** | os dois plane routers entregam o norte-sul a **um** router de gateway por AZ, que faz SNAT de cada plane para um endereço externo próprio. |
| 3 | **Control plane de interconexão em HA** | IC-NB/IC-SB como **cluster RAFT de 3 membros**, então nenhuma VM sozinha derruba os bancos compartilhados da federação. |

### 4.2 Três VMs, duas delas AZs

| VM | Papel | O que roda |
|---|---|---|
| **az1** `172.18.3.175` (pub `201.23.73.63`) | célula completa | Incus, NB/SB/northd/`ovn-controller`, `ovn-ic`, chassis, edge, **membro RAFT + bootstrap** |
| **az2** `172.18.33.126` (pub `201.23.74.156`) | célula completa | idem, **membro RAFT** |
| **quorum** `172.18.3.240` | **árbitro** | **só** o terceiro membro do cluster RAFT. Sem chassis, sem `br-int`, sem GENEVE, sem workloads, sem NB/SB local. |

O `quorum` é deliberadamente mínimo: recebe apenas `openvswitch-common` (que traz
`ovsdb-server` e `ovsdb-tool`), **não** `openvswitch-switch` — para que não possa virar um
nó de datapath por acidente. E os schemas IC são **copiados** do az1 em vez de compilados,
porque 1 vCPU / 1 GB não é máquina de build.

No inventário isso aparece como dois grupos que se sobrepõem:

```ini
[azs]                 # as duas AZs reais: chassis, workloads, GENEVE
az1
az2

[ic_cluster]          # os três nós do cluster RAFT dos bancos IC
az1
az2
quorum

[all:vars]
ansible_user = ubuntu
```

### 4.3 Os dois planes

Esta é a diferença central em relação à revisão anterior.

| | **Client plane** | **Management plane** |
|---|---|---|
| Propósito | tráfego de tenant — o serviço web | tráfego de operador — o DBaaS e a observabilidade |
| Switch / router | `ls-client-<az>` / `lr-client-<az>` | `ls-mgmt-<az>` / `lr-mgmt-<az>` |
| Federado por | **`ts-client`** (`169.254.100.0/24`) | **`ts-mgmt`** (`169.254.200.0/24`) |
| Subredes | `10.10.1.0/24` (AZ1), `10.10.2.0/24` (AZ2) | `10.20.1.0/24` (AZ1), `10.20.2.0/24` (AZ2) |
| Supernet | `client_supernet: 10.10.0.0/16` | `mgmt_supernet: 10.20.0.0/16` |
| Load balancer | sim — um VIP por AZ (+ um *service VIP* na AZ1) | não |
| Membros | `app-vm-1` (React), `app-vm-2` (Java), `load-vm` | `app-vm-1`, `app-vm-2`, `load-vm`, **`db-vm`**, `obs-vm` |

As camadas da aplicação são **dual-homed**: servem HTTP na NIC do client e alcançam o banco
pela NIC do mgmt. O `db-vm` tem **uma única** NIC, no mgmt — então não existe endereço que o
client plane pudesse sequer usar para nomeá-lo.

**O isolamento é lógico, não físico.** Os dois transit switches são carregados pelo *mesmo*
túnel GENEVE entre os *mesmos* dois chassis; o que os separa é serem datapaths OVN distintos,
e o `ovn-ic` anunciar as rotas de cada plane só no transit switch daquele plane. O
`verify.yml` afirma isso diretamente: `lr-client-*` tem de aprender a subrede `10.10.x` do
peer e **nenhuma** rota `10.20.x`, e vice-versa.

> Vale ser explícito: é uma fronteira de **roteamento/tenancy**, não de criptografia. Quem
> conseguir injetar no `br-int` ou ler o túnel não é barrado por ela.

Dentro do container, quem mantém a separação é o **netplan por NIC**: além do default route
(na NIC marcada `default_route: true`), cada NIC recebe uma rota explícita para o **supernet
do seu próprio plane**. Assim o tráfego para `10.20.0.0/16` fica preso à NIC de mgmt e nunca
sai pela de client. A NIC de mgmt ganha ainda uma rota para o `underlay_supernet`
(`172.18.0.0/16`), para que consultar o agente de probe no host seja tráfego de gerência.

### 4.4 O edge da célula ("Gateway Nodes")

Os dois plane routers fazem default route para **um** router de gateway por célula
(`lr-edge-<az>`), que é dono do uplink para o `br-ex` e faz SNAT de cada plane para o seu
próprio endereço externo (`192.168.241.2` client e `192.168.241.3` mgmt, na az1).

Ele existe por duas razões:

1. **É o que o desenho especifica** — um bloco *Gateway Nodes* por célula, com as setas de
   SNAT dos dois planes apontando para ele.
2. **É o que torna o load balancer legal.** O OVN se recusa a programar um load balancer num
   router com mais de uma *distributed gateway port*
   (`Load-balancer is not supported yet when there is more than one distributed gateway port
   on the router`). Um plane router que também tivesse porta externa teria duas: a de trânsito
   e a externa. Mover o norte-sul para o edge deixa cada plane router com **exatamente uma**.

O `lr-edge` é um **gateway router de verdade** (`options:chassis=<chassis>`), não um
distribuído — é isso que permite fazer NAT sem uma distributed gateway port, e que faz o
load balancer valer em **todas** as portas dele.

#### O Edge Firewall

O edge necessariamente conhece rota para os **dois** planes — precisa, para devolver as
respostas. Só isso já os reconectaria: um workload do client plane alcança seu gateway, é
roteado ao edge, e é roteado de volta para dentro do mgmt plane — desfazendo em silêncio o
isolamento que os dois transit switches existem para dar.

Duas *logical router policies* no edge derrubam qualquer trânsito plane-a-plane:

```bash
ovn-nbctl lr-policy-add lr-edge-az1 100 \
  "ip4.src == 10.10.0.0/16 && ip4.dst == 10.20.0.0/16" drop   # e a simétrica
```

Na prática o Ansible gera as duas direções com `planes | product(planes)`, pulando o par
consigo mesmo. Norte-sul (destino fora dos dois supernets) passa intocado.

**Esse teste pegou um vazamento real**: antes das policies existirem, o `verify.yml`
detectou que o client plane alcançava o banco pelo edge compartilhado.

### 4.5 O arranjo dos bancos, agora com cluster

| Banco | Porta(s) | Escopo | Roda em | Quem conecta |
|---|---|---|---|---|
| NB local | `6641` | por AZ, privado | cada AZ (127.0.0.1) | `ovn-northd`, `ovn-nbctl`, `ovn-ic` **daquela** AZ |
| SB local | `6642` | por AZ, privado | cada AZ (127.0.0.1) | `ovn-northd`, `ovn-controller`, `ovn-ic` **daquela** AZ |
| **IC-NB** | `6645` cliente / **`6647` RAFT** | **global, clusterizado** | az1 + az2 + quorum | o `ovn-ic` das **duas** AZs |
| **IC-SB** | `6646` cliente / **`6648` RAFT** | **global, clusterizado** | az1 + az2 + quorum | o `ovn-ic` das **duas** AZs |

A porta RAFT é gravada **dentro do arquivo `.db`** pelo `ovsdb-tool create-cluster` /
`join-cluster` — não é passada no `ExecStart`. A unit só declara a porta de **cliente**:

```ini
ExecStart=/usr/sbin/ovsdb-server /opt/ovn-lab/ic-nb.db \
  --remote=ptcp:6645:172.18.3.175 \
  --unixctl=/opt/ovn-lab/ic-nb.ctl \
  --log-file=/opt/ovn-lab/ic-nb.log
```

Os clientes recebem **todos** os membros, montados por Jinja em `group_vars`:

```yaml
ic_nb_remote: "{{ groups['ic_cluster'] | map('extract', hostvars, 'az_private_ip')
                  | map('regex_replace', '^(.*)$', 'tcp:\1:' ~ ic_nb_port) | join(',') }}"
# vira: tcp:172.18.3.175:6645,tcp:172.18.33.126:6645,tcp:172.18.3.240:6645
```

Um ovsdb clusterizado atende **leitura em qualquer membro** e **redireciona escrita para o
líder**, então um `ovn-ic` continua funcionando quando o membro com quem ele falava some.

**O data plane é independente de tudo isso.** Com os bancos de interconexão fora do ar, os
flows já programados continuam encaminhando. O que uma queda do IC custa são **mudanças de
topologia**, não tráfego — e o `verify_ha.yml` afirma exatamente isso.

### 4.6 Endereçamento completo

| Plano / elemento | AZ1 (host az1) | AZ2 (host az2) | Compartilhado |
|---|---|---|---|
| Host (underlay) | `172.18.3.175` | `172.18.33.126` | GENEVE UDP 6081 |
| Árbitro de quórum | — | — | `172.18.3.240` (só cluster IC) |
| **Client** subrede | `10.10.1.0/24` (gw `.1`) | `10.10.2.0/24` (gw `.1`) | `ts-client` `169.254.100.0/24` |
| **Client** VIP | `10.10.1.100:80` (frontend) | `10.10.2.100:80` (backend) | — |
| **Client** service VIP | `10.10.1.200:80` → backend na AZ2 | — | o VIP que cruza AZ |
| **Mgmt** subrede | `10.20.1.0/24` (gw `.1`) | `10.20.2.0/24` (gw `.1`) | `ts-mgmt` `169.254.200.0/24` |
| `app-vm-N` | client `10.10.1.10` · mgmt `10.20.1.10` | client `10.10.2.20` · mgmt `10.20.2.20` | |
| `db-vm` | — | **mgmt `10.20.2.10` apenas** | Postgres `:5432` |
| `obs-vm` | mgmt `10.20.1.30` | mgmt `10.20.2.30` | Prometheus/Grafana |
| `load-vm` | client `10.10.1.40` · mgmt `10.20.1.40` | — | gerador de carga |
| Edge interno | `192.168.251.0/24` (edge `.254`) | `192.168.252.0/24` (edge `.254`) | **anunciado** pelo IC |
| Provider (`br-ex`) | `192.168.241.0/24` (host `.1`) | `192.168.242.0/24` (host `.1`) | **não** anunciado |
| Endereços de SNAT | client `.241.2` · mgmt `.241.3` | client `.242.2` · mgmt `.242.3` | |
| MTU | `1442` nas **duas** NICs de cada workload | idem | 1500 − 58 GENEVE |

#### Por que o edge é anunciado e o provider não

É a sutileza de roteamento mais importante da revisão, e vive em `ic_route_blacklist`:

```yaml
ic_route_blacklist: "{{ groups['azs'] | map('extract', hostvars, 'ext_cidr') | join(',') }}"
# = 192.168.241.0/24,192.168.242.0/24   (só os provider subnets)
```

- As subredes **provider** (`br-ex`) são encanamento do host: rotas conectadas no edge que
  não têm por que existir na outra AZ. Ficam na blacklist.
- As subredes **edge** (`192.168.25x.0/24`) **precisam** atravessar a interconexão: quando o
  load balancer manda um request para o backend na *outra* AZ, o edge faz SNAT para o próprio
  endereço de edge — e o backend remoto só consegue responder se a AZ dele tiver aprendido
  rota de volta para aquele endereço.

Isso se configura junto com os gotchas #1 e #4:

```bash
ovn-nbctl set NB_Global . \
  name=az1 \
  options:ic-route-adv=true \
  options:ic-route-learn=true \
  options:ic-route-blacklist=192.168.241.0/24,192.168.242.0/24
```

### 4.7 A aplicação — partida de propósito

Três camadas, distribuídas para que *usar o sistema* seja o teste:

| Camada | Onde | Stack | Escuta em |
|---|---|---|---|
| Frontend | `app-vm-1`, **AZ1** | React 18 + Vite, servido por nginx | NIC client `:80` |
| Backend | `app-vm-2`, **AZ2** | Java 21, `HttpServer` do JDK + JDBC | NIC client `:8080` |
| Banco | `db-vm`, **AZ2** | PostgreSQL 16 | NIC mgmt `:5432` |

O caminho de cada refresh do dashboard:

```
browser ──túnel ssh──► VIP da AZ1  10.10.1.100:80
                         └─► nginx + bundle React          app-vm-1 · AZ1 · client
                               └─► /api/*  ──► service VIP da AZ1  10.10.1.200:80
                                                 └─► DNAT do OVN LB ──► ts-client ──► GENEVE   ◄── CRUZA AZ
                                                       └─► backend Java   app-vm-2 · AZ2 · client
                                                             └─► JDBC ──► db-vm 10.20.2.10   ◄── PLANE MGMT
```

Três decisões de implementação que valem entender:

- **nginx faz proxy de `/api/*`** em vez de o browser chamar a AZ2 direto. Assim o browser só
  precisa alcançar a AZ1, enquanto o salto entre AZs continua acontecendo **dentro** do fabric.
- **O backend é JDK puro** — sem Maven, sem Spring. Os workloads são containers de 2 vCPU atrás
  de um NAT duplo; puxar árvore de dependências por ali é lento e frágil, e nada ali precisa de
  framework. HTTP, JSON, JDBC e enumeração de interfaces já estão no JDK (o driver JDBC vem da
  distro, em `/usr/share/java`).
- **O dashboard é uma visão da infraestrutura**, não uma página de demo. Cada host de AZ lê o
  próprio estado OVN (`ovn-nbctl`, `ovn-ic-sbctl`, `ovs-vsctl`, `ovs-appctl cluster/status`) e
  grava na tabela `infra_state` **pelo plane de gerência**; o backend Java lê essa tabela e a
  serve; o React exibe. A visão da infraestrutura, portanto, **percorre a arquitetura inteira
  para chegar na sua tela**.

#### O `Backend.java`, em detalhe

Toda a configuração vem do ambiente (`APP_AZ`, `APP_CLIENT_IP`, `APP_MGMT_IP`, `DB_HOST`,
`APP_PROBE_AGENTS`…), então o código não carrega endereço nenhum.

| Endpoint | O que faz |
|---|---|
| `GET /api/health` | liveness + identidade da camada |
| `GET /api/status` | auto-probe: identidade, endereços dos dois planes, NICs com MTU, latência do banco |
| `GET /api/infra` | lê `infra_state` do banco (o que o dashboard renderiza) |
| `GET /api/flow` | o caminho do request com tempo medido por salto |
| `GET /api/test` | roda ao vivo as checagens dos botões "Testar" do dashboard; `?group=` filtra |
| `GET /api/orders` | **escreve** uma linha em `orders` |
| `GET /api/report` | agregação sobre a tabela `orders` inteira — uma leitura deliberadamente mais pesada |

**Dois listeners, dois planes, um processo:**

```java
HttpServer server  = HttpServer.create(new InetSocketAddress(PORT), 0);          // client plane
HttpServer metrics = HttpServer.create(new InetSocketAddress(MGMT_IP, METRICS_PORT), 0); // mgmt
```

O serviço é oferecido ao tenant no client plane; a instrumentação dele (`/metrics`, porta
9102) é assunto de operação e vive no mgmt plane, **bindado explicitamente ao IP de mgmt**.
Raspar o backend a partir da rede de tenant é impossível por construção, não por política.

### 4.8 As sete roles + a nova `ic_cluster`

| role | o que faz |
|---|---|
| `common` | base apt (OVS/OVN), desabilita as units OVN do apt, **compila `ovn-ic` + schemas IC do fonte**, e instala a **política de logrotate** que impede um daemon em crash loop de encher o disco. Guardado por `stat`, então o build de ~10-20 min roda uma vez. |
| **`ic_cluster`** | o **cluster RAFT de 3 membros** do IC-NB/IC-SB: instala `ovsdb-server` no árbitro, copia os schemas IC para ele, `create-cluster` no bootstrap e `join-cluster` nos outros, e espera todos os membros + um líder. |
| `ovn_central` | NB/SB por AZ + `ovn-northd`, como units systemd. **Não tem mais os templates IC** — foram para a `ic_cluster`. |
| `ovn_chassis` | chassis OVS (encap-ip GENEVE), `ovn-controller`, `br-ex`, `ip_forward` + MASQUERADE, firewall para GENEVE **e todas as portas do cluster IC**, `node_exporter` e o **agente de probe**. |
| `ovn_topology` | identidade/opções de rota no `NB_Global`, **os dois** transit switches no IC-NB, e por plane: LS/LR/LRP, gateway chassis na LRP de trânsito, e a conexão ao transit propagado. |
| `ovn_services` | o **edge gateway router** (uplink, SNAT por plane, policies do Edge Firewall) e o **load balancer por AZ** (+ o service VIP cross-AZ na AZ1). |
| `incus` | instala Incus e `admin init --minimal`. |
| `workloads` | uma porta lógica OVN **por NIC**, containers Incus com um veth p2p por plane no `br-int`, netplan estático com rotas por plane, e as camadas em ordem de dependência: PostgreSQL → backend Java → publicação do estado da infra → frontend React → gerador de carga. |

#### `ic_cluster` — a role nova, passo a passo

1. Garante `/opt/ovn-lab`.
2. **Só no árbitro**: instala `openvswitch-common` (não `openvswitch-switch`).
3. Resolve os caminhos absolutos de `ovsdb-server` e `ovsdb-tool`.
4. **`slurp`** dos dois schemas IC a partir do bootstrap (`delegate_to: az1`, `run_once`) e
   `copy` deles para `/usr/local/share/ovn` em todos — o árbitro não compila nada.
5. **`create-cluster`** no bootstrap, com `creates:` guardando — reformar um cluster existente
   seria catastrófico.
6. Instala as units, `flush_handlers`, sobe o bootstrap, **espera a porta de cliente abrir**.
7. **`join-cluster`** nos demais, apontando para o RAFT do bootstrap, também com `creates:`.
8. Sobe os membros que entraram.
9. Espera (`retries: 30, delay: 4`) até `cluster/status` reportar **todos os membros e nenhum
   candidato** — isto é, sem eleição em curso:

```yaml
until:
  - ic_status.rc == 0
  - "'Servers:' in ic_status.stdout"
  - ic_status.stdout | regex_findall('at tcp:') | length == ic_cluster_size
  - "'candidate' not in ic_status.stdout"
```

**A ordenação sai de graça:** na estratégia `linear` do Ansible, cada task termina em todos
os hosts antes da próxima começar — então "cria o cluster no bootstrap" está completamente
feito antes de "entra no cluster" rodar em qualquer outro. É exatamente a ordem que o RAFT
precisa, sem `serial` nem delegação.

#### `ovn_topology` — agora em loop por plane

```
main.yml
 ├── NB_Global: name + ic-route-adv/learn + ic-route-blacklist      (gotchas #1, #4)
 ├── loop planes → plane.yml         LS, LR, LRP-ls, patch, LRP-ts, gateway chassis (#5)
 ├── loop transit_switches → ts-add no IC-NB, só no ic_bootstrap    (gotcha #2)
 ├── unit do ovn-ic (com --unixctl) → start                         (gotcha #6)
 ├── espera os DOIS ts aparecerem no ls-list local
 ├── loop planes → plane_transit.yml   lsp-add no ts + router-port
 ├── restart do ovn-ic                                              (gotcha #3)
 ├── espera o gateway desta AZ aparecer no IC-SB
 └── imprime as rotas de cada plane
```

A espera pelos dois transit switches é a generalização da espera antiga:

```yaml
until: transit_switches | select('in', ls_list.stdout) | list | length
       == transit_switches | length
```

> `ovn_topology` é a **única** role que não reporta `changed=0` numa re-execução: o restart
> do `ovn-ic` é deliberado (gotcha #3), então ela sempre reporta 1 mudança.

#### `ovn_services` — a role que mais cresceu (5 KB → 15,7 KB)

**A) O edge**

```bash
ls-add ls-ext-<az>                                    # switch provider
lsp-add ls-ext-<az> ln-ext-<az>  (localnet, physnet-ext)
ls-add ls-edge-<az>                                   # switch interno plane↔edge
# por plane (plane_edge.yml):
lrp-add <plane.lr> lrp-<plane>-<az>-edge <mac> 192.168.25x.N/24   # porta PLANA, sem gw chassis
lsp-add ls-edge-<az> lsp-edge-<plane>-<az>  (router patch)
lr-route-add <plane.lr> 0.0.0.0/0 192.168.25x.254     # default via edge
# o edge:
lr-add lr-edge-<az>
set logical_router lr-edge-<az> options:chassis=<chassis>          # gateway router de verdade
lrp-add lr-edge-<az> lrp-edge-<az>-int <mac> 192.168.25x.254/24
lrp-add lr-edge-<az> lrp-edge-<az>-ext <mac> 192.168.24x.2/24 192.168.24x.3/24  # 1 IP por plane
lr-route-add lr-edge-<az> 0.0.0.0/0 192.168.24x.1     # default via host no br-ex
lr-route-add lr-edge-<az> <supernet do plane> <edge_ip do plane>   # volta para cada plane
lr-policy-add lr-edge-<az> 100 "src==<supernet A> && dst==<supernet B>" drop   # Edge Firewall
lr-nat-add lr-edge-<az> snat <snat_ip do plane> <cidr do plane>    # um SNAT por plane
```

Dois detalhes que só se entende lendo com cuidado:

- A porta do plane router para o edge é **plana** — sem `lrp-set-gateway-chassis`. Se ela
  virasse DGP, o plane router teria duas e o load balancer seria recusado (gotcha #10).
- As rotas de volta são no **supernet** do plane, não na subrede desta AZ. É isso que permite
  ao edge alcançar o backend que vive na **outra** AZ (via plane router e transit) quando o
  load balancer o escolhe.
- A porta externa do edge carrega **os dois** endereços de SNAT numa única `lrp-add`, gerados
  por `planes | map(attribute='snat_ip')`.

**B) O load balancer — três attachments, três motivos diferentes**

Esta é a parte mais contra-intuitiva do lab inteiro. O VIP da AZ é anexado em **três** lugares:

| Anexado em | Por quê |
|---|---|
| `ls-client-<az>` (switch) | o VIP mora **dentro** da subrede de tenant, e só um LB no switch instala o **ARP responder**. Sem isso, um workload no switch nunca recebe resposta de ARP para o VIP e nem chega a mandar o primeiro pacote. |
| `lr-edge-<az>` (gateway router) | é o que atende clientes de **fora** da VPC. Não pode ser no plane router porque num router **distribuído** o OVN só programa o LB na *distributed gateway port*, e o tráfego externo não chega por ela. O `lr-edge` é gateway router de verdade, onde o LB vale em toda porta. |
| `lr-client-<az>` (plane router) | para que um cliente na **outra** AZ batendo neste VIP também seja balanceado: esse tráfego chega pela porta de trânsito deste router, que **é** a DGP dele — então ali o LB se aplica. |

E o force-SNAT, em dois lugares:

```bash
set logical_router lr-edge-az1   options:lb_force_snat_ip=192.168.251.254   # edge_int_ip
set logical_router lr-client-az1 options:lb_force_snat_ip=169.254.100.1     # ts_ip
```

Sem isso, um request balanceado para o backend da **outra** AZ chegaria lá carregando o
endereço do cliente original, e a resposta sairia daquela AZ pelo edge **dela** em vez de
voltar por aqui — a conexão penduraria. Forçando o SNAT, a resposta é obrigada a voltar pelo
mesmo caminho, o que também é o motivo de a subrede de edge ser anunciada na interconexão.

> **Atenção (gotcha #13):** a palavra-chave documentada `router_ip` é **rejeitada** por este
> build (`bad ip router_ip in options of router`). O endereço tem de ser literal.

**C) O service VIP cross-AZ (`10.10.1.200`)**

Um **segundo** load balancer, no client plane da AZ1, cujo backend é a camada que vive na
**AZ2**. O frontend chama esse VIP local, o OVN faz DNAT para o backend remoto, e o request
sai da AZ1 pelo `ts-client` **já balanceado**.

Ele é deliberadamente um VIP da AZ1, e não o VIP da AZ2, por causa do **gotcha #14**: um VIP
só é utilizável de dentro da AZ que o possui. Chegando pelo transit, o pacote entra no
`ls-client-az2` vindo de uma porta de router (o LB do switch é pulado, #12) e o OVN também não
programa o LB do router nesse caminho de ingresso — então ele é roteado como endereço comum e
morre no ARP.

### 4.9 Observabilidade e geração de carga

Cada célula roda o **próprio** Prometheus + Grafana, no **plane de gerência**.

```
obs-vm (só mgmt)             raspa, tudo pelo management plane
  ├── node_exporter do host de AZ   :9100   ← contadores por interface, incl. genev_sys_6081
  ├── agente de probe da infra      :9101   ← estado do OVN como métrica
  ├── cada workload                 :9100   ← bytes por NIC: client vs mgmt, lado a lado
  ├── backend Java                  :9102   ← RPS, histograma de latência, latência de DB
  ├── postgres_exporter             :9187   ← commits, rows, conexões
  └── gerador de carga              :9103   ← carga oferecida e latência vista pelo cliente
```

- **`genev_sys_6081` nos contadores do `node_exporter` do host** é o detalhe mais elegante:
  bytes/s no túnel vira métrica real, não inferência.
- **Cada célula raspa só a si mesma** — uma falha numa AZ não pode cegar o monitoramento da
  outra, e o tráfego de scrape nunca carrega a interconexão. O efeito colateral é que painéis
  alimentados por um componente que só existe numa AZ (o gerador está na AZ1) ficam vazios no
  Grafana da outra. Isso é o isolamento funcionando.
- **O Grafana é alcançado por um `proxy device` do Incus**, não por rota do host para o plane
  de gerência. O forwarding acontece dentro do namespace do container, então o host de AZ nunca
  precisa de rota para `10.20.x` e o plane continua fechado.
- **Retenção limitada nos dois eixos** (`6h` **e** `1GB`): retenção por tempo sozinha não limita
  bytes, e um TSDB enchendo o disco em silêncio é exatamente o modo de falha do **gotcha #9**.

#### O gerador de carga (`load-vm`, AZ1)

Dashboard vazio não prova nada. O `load-vm` é dual-homed como as camadas da app e dirige o
caminho **real** da aplicação:

| | |
|---|---|
| Alvo | o **VIP do frontend** da AZ1 — para que cada request passe por nginx, service VIP, `ts-client` sobre GENEVE, backend Java e PostgreSQL |
| Mix | `/api/status` 5 · `/api/report` 3 · `/api/orders` 2 (um `INSERT` real) · `/api/health` 2 |
| Taxa | uma senoide lenta em torno de `loadgen_base_rps: 6`, um ciclo a cada 10 min — os gráficos ganham **forma**, e uma linha reta passa a ser sinal em vez de norma |
| Carga no mgmt | uma consulta periódica direto ao banco na AZ2 pelo `ts-mgmt`. O caminho da app **nunca** cruza o `ts-mgmt` (backend e banco estão os dois na AZ2), então sem isso o transit de gerência não carregaria nada mensurável |

### 4.10 Ordem de execução

```bash
ansible-playbook ping.yml         # 0  SSH + Python nos três hosts
ansible-playbook common.yml       # 2  deps + compila OVN v24.03.6 / OVS v3.3.0 (~10-20 min)
ansible-playbook ic_cluster.yml   # 3  cluster RAFT de 3 membros do IC-NB/IC-SB
ansible-playbook central.yml      # 4  NB/SB + northd por AZ
ansible-playbook chassis.yml      # 5  chassis OVS + ovn-controller + br-ex + SNAT do host
ansible-playbook topology.yml     # 6  os dois planes + os dois transit switches + ovn-ic
ansible-playbook services.yml     # 7  edge gateway router + load balancer por AZ
ansible-playbook incus.yml        # 8  instala + inicializa o Incus
ansible-playbook workloads.yml    # 9  containers dual-NIC + Postgres + Java + React + loadgen

ansible-playbook verify.yml       # T1-T7, com assert
ansible-playbook verify_ha.yml    # H1-H4, HA da interconexão (disruptivo)
```

`site.yml` encadeia os estágios 2-9. E há um caminho novo:

```bash
ansible-playbook reset.yml && ansible-playbook site.yml
```

**`reset.yml` existe porque as roles são idempotentes para *valores*, mas não sabem renomear
nem remover objetos que um desenho anterior criou.** Quando a *forma* da topologia muda
(routers renomeados, um plane novo, outro transit switch), é preciso derrubar primeiro. Ele
apaga containers, veths `veth-*` do `br-int` (por prefixo, para pegar sobras de topologias
antigas), os `.db` locais e os `.db` do IC, e as rotas de host para os VIPs antigos — mas
**não** toca no build em `/opt/ovn-build` nem no storage pool do Incus.

### 4.11 O que o `verify.yml` prova (T1-T7)

Tudo é `assert`, então um caminho quebrado **falha a execução** em vez de imprimir algo para
você ler.

| Grupo | O que prova |
|---|---|
| **T1** control plane | os dois transit switches declarados no IC-NB; os dois gateways no IC-SB; túnel GENEVE para o encap-ip do peer; **e cada plane router aprendeu só a subrede remota do próprio plane** |
| **T2** isolamento | o client plane **não** alcança o banco; o mgmt plane **não** alcança o client plane; o `db-vm` não tem NIC de client nenhuma |
| **T3** client plane | o VIP de cada AZ responde 12/12 para a camada dela; o **service VIP** da AZ1 é respondido 8/8 pelo backend da AZ2 (o LB fazendo DNAT cross-AZ sobre `ts-client`) |
| **T4** mgmt plane | o backend lê o Postgres pela NIC de mgmt; uma sessão wire-protocol crua abre das duas AZs; RTT reportado cross-AZ vs intra-AZ |
| **T5** norte-sul | cada workload alcança a internet pelo SNAT do seu plane |
| **T6** MTU | `1414` B passa e `1415` B é rejeitado — o teto do GENEVE exatamente onde deveria |
| **T7** aplicação | o VIP do frontend serve o bundle React; `/api/status` através dele é respondido **pelo backend da outra AZ** com banco vivo; o backend reporta uma NIC client + uma mgmt a MTU 1442; o dashboard mostra linhas de infra das **duas** AZs, nenhuma em erro |

Dois detalhes de engenharia de teste que valem copiar:

- **T4 não cronometra o `psql` via `incus exec`.** O comentário no código explica: o relógio de
  parede seria dominado por startup de container e de `psql`, que afoga os poucos milissegundos
  de interconexão e faria o intra-AZ parecer *mais lento*. O custo real vem do backend de vida
  longa (T4.3) e do RTT cru (T4.4).
- **T5 tem `retries: 3`.** Ele sai do fabric inteiro até a internet de verdade; um timeout
  isolado diz mais sobre o link upstream do que sobre o lab. Sem o retry a suíte falharia de
  forma intermitente — e *um teste que grita lobo treina você a ignorá-lo*.

### 4.12 O que o `verify_ha.yml` prova (H1-H4)

Disruptivo: para os bancos IC do líder RAFT atual por alguns segundos. Por isso é separado.

| | |
|---|---|
| **H1** | o cluster começa com os três membros e um líder, sem eleição em curso |
| **H2** | com o **líder parado**, um sobrevivente é eleito **e ainda confirma escrita** — testado criando e apagando um transit switch de sonda (`ha-probe-ts`). Um cluster de 2 membros não conseguiria. |
| **H3** | o **data plane não percebe**: os workloads continuam conversando com todo o control plane de interconexão fora do ar |
| **H4** | o membro parado **volta** para o cluster limpo |

O H2 testa leitura **e** escrita de propósito: um cluster sem quórum ainda serve leitura
obsoleta de um follower, e pareceria saudável se só a leitura fosse verificada.

### 4.13 Resultados medidos

- **Control plane:** `ts-client` e `ts-mgmt` declarados e propagados; `az1-chassis` e
  `az2-chassis` registrados no IC-SB; GENEVE de pé entre `172.18.3.175 ↔ 172.18.33.126`.
- **Separação de planes:** `lr-client-az1` aprendeu `10.10.2.0/24` **e nada de `10.20.x`**;
  `lr-mgmt-az1` aprendeu `10.20.2.0/24` **e nada de `10.10.x`** — e simetricamente na AZ2.
- **Isolamento:** o client plane não alcança `10.20.2.10` em nenhuma AZ. **Antes** das policies
  do Edge Firewall, esse teste **pegou um vazamento real** pelo edge compartilhado.
- **Aplicação, ponta a ponta:** `GET /api/status` pelo VIP do frontend da AZ1 é respondido por
  `app-vm-2` **na AZ2** com banco vivo, em ≈ **45 ms** no total; o service VIP `10.10.1.200` foi
  respondido 8/8 pelo backend da AZ2; os dois VIPs próprios responderam 12/12. O dashboard
  renderiza **19 linhas de infraestrutura das duas AZs, nenhuma em erro**.
- **Cross- vs intra-AZ no `ts-mgmt`:** RTT ao banco de **3,191 ms** da AZ1 (pela interconexão)
  vs **0,059 ms** da AZ2 (local) — **~54x**, medido numa execução só.
- **Norte-sul:** os três workloads alcançam a internet (HTTP 200), cada um pelo SNAT do seu plane.
- **MTU:** `-s 1414 -M do` passa, `-s 1415` falha.
- **HA da interconexão:** parar o **líder** RAFT elegeu outro em segundos; o cluster degradado
  2-de-3 **ainda confirmou escrita**; o data plane não percebeu; o membro parado voltou limpo.
- **Idempotência:** re-rodar `ic_cluster`, `central`, `chassis` e `services` reporta `changed=0`
  (`topology` reporta 1 — o restart deliberado do `ovn-ic`).

### 4.14 Decisões de design (e as defesas delas)

| Decisão | Razão | Trade-off assumido |
|---|---|---|
| **OVN dirigido à mão; Incus só fornece o workload** | o Incus tem rede OVN nativa, mas gerencia o NB sozinho e **não expõe configuração de OVN-IC** | mais passos manuais na role `workloads` |
| **Isolamento por datapaths separados, não por fabrics separados** | é o que o desenho especifica: dois transit switches sobre um túnel GENEVE | é fronteira de roteamento/tenancy, **não** de criptografia |
| **O banco é single-NIC** | o isolamento não pode ser desfeito por erro de roteamento: **não existe endereço** para alcançá-lo do client plane | nenhum relevante — é garantia mais forte que regra de firewall |
| **Um tier de edge por célula** | pelo desenho, e pela restrição do OVN sobre LB × DGP (gotcha #10) | uma camada de roteamento a mais |
| **VIPs por AZ em vez de um VIP global** | o desenho é simétrico: cada célula oferece o serviço localmente | exigiu o service VIP para o caso cross-AZ (gotcha #14) |
| **Containers Incus, não VMs** | hosts de 2 vCPU / 8 GB; KVM aninhado é pesado. Containers exercitam **exatamente o mesmo** datapath GENEVE | isolamento menor |
| **A aplicação é partida entre AZs de propósito** | o fluxo sob teste vira o tráfego do próprio produto, não uma sonda sintética. E falha fica óbvia: se a interconexão quebra, o dashboard diz em um poll | acopla as camadas às AZs |
| **JDK puro no backend, sem Spring/Maven** | containers pequenos atrás de NAT duplo; árvore de dependências é download lento e frágil sem benefício nessa escala | sem ergonomia de framework |
| **Postgres único na AZ2, sem réplica** | dá de graça o contraste intra × inter-AZ | acopla domínios de falha, adiciona latência para a AZ1 |
| **O árbitro é deliberadamente mínimo** | sem chassis, sem `br-int`, sem GENEVE, e só `openvswitch-common` — para que não possa virar nó de datapath por acidente | uma VM só para votar |

### 4.15 Como evoluir para algo mais real

1. **Persistir as partes de rede do host** (IP do `br-ex`, iptables, rota do VIP) via netplan +
   `iptables-persistent`/unit systemd — hoje se perdem no reboot. *Baixo esforço, alto valor;
   o reboot que iniciou a sessão de debug desta revisão é exatamente esse modo de falha.*
2. **Segredos com `ansible-vault`** — a senha do banco está em texto puro em `group_vars`.
3. **Health checks no LB** (`health_check` do OVN LB) para derrubar backend morto.
4. **PostgreSQL primário/réplica com failover** — réplica na AZ1 mata a latência do caminho #2.
5. **TLS nos bancos** (`pssl`/`ssl` no lugar de `ptcp`) — *mais* valioso agora, já que o cluster
   IC fofoca pela rede nas portas 6647/6648.
6. **ACLs de verdade nos switches de plane** — hoje a separação é por roteamento; port groups +
   ACLs do OVN reforçariam em L2/L4 também.
7. **Federação cross-AZ do Prometheus pelo `ts-mgmt`** — colocaria tráfego operacional real e
   contínuo no transit de gerência: o monitoramento exercitando o fabric que ele monitora.
8. **Sondas `blackbox_exporter` que devem FALHAR** — alertar se o client plane algum dia
   alcançar o banco transforma um invariante de segurança em métrica.
9. **Injeção de falha como demo**: `tc netem delay 50ms dev genev_sys_6081` faz a latência
   cross-AZ subir no Grafana enquanto a intra-AZ fica plana.
10. **Mais de duas AZs** — os transit switches federam N zonas; basta `host_vars/az3.yml` e
    acrescentá-la a `[azs]`. O cluster IC já tolera um membro entrando.
11. **Workloads como VMs Incus** (KVM aninhado).
12. **Várias réplicas de app por AZ** atrás de cada VIP.
13. **Observabilidade mais profunda**: `ovn-trace`/`ovs-appctl` (ambos foram essenciais nesta
    revisão), métricas do OVS, logs centralizados.
14. **Qualidade do Ansible**: `molecule` e `ansible-lint` em CI.

---

## 5. Os 16 gotchas, explicados a fundo

> Os cinco primeiros vieram do `pratice-ovn-ic`; os #6-#8 da primeira revisão do
> `advanced-ovn-ic`; os **#9-#16 apareceram nesta revisão dual-plane**.

### Herdados do `pratice-ovn-ic`

### #1 — A identidade da AZ vem de `NB_Global.name`

**Sintoma:** topologia correta, `ovn-ic` rodando, e **nenhum gateway** no
`ovn-ic-sbctl list Gateway`.
**Causa:** o nome de AZ que o `ovn-ic` registra no IC-SB vem do campo `name` da tabela
`NB_Global` do NB **local**. Sem ele, o daemon não tem identidade para registrar.
**Correção:** `ovn-nbctl set NB_Global . name=az1`.

### #2 — Os transit switches são gerenciados pelo `ovn-ic`

**Sintoma:** colisão ao criar o `ts` à mão no NB local.
**Causa:** o `ovn-ic` detecta cada `Transit_Switch` declarado no IC-NB e **cria
automaticamente** o logical switch em cada AZ, já com a anotação `interconn-ts`.
**Correção:** `ovn-ic-nbctl ts-add <nome>` e **esperar** aparecer no `ls-list` local.
*Agora são dois*, e a espera virou uma comparação de tamanho de lista.

### #3 — Race condition na inicialização do `ovn-ic`

**Sintoma:** `Can't get router uuid for transit switch port` seguido de
`Route sync ignores port ... Deleting it` — a porta é descartada e **nunca reprocessada**.
**Causa:** o `ovn-ic` pode processar a notificação de `lsp-ts-*` **antes** de o cache OVSDB
local conter a `lrp-*-ts` correspondente.
**Correção:** **reiniciar o `ovn-ic`** depois da topologia completa. É a única fonte de
`changed` numa re-execução da role `ovn_topology`.

### #4 — `ic-route-adv` / `ic-route-learn` ficam em `NB_Global.options`

**Sintoma:** no log de debug, `Route ad: skip network 10.10.1.1/24 of lrp lrp-client-az1-ls.`
— recusa **silenciosa** de anunciar redes conectadas.
**Causa:** a documentação sugere que são opções por router, mas no OVN 24.03.6 só têm efeito
em `NB_Global`. Confirmado com `strings` no binário: a tabela `Transit_Switch` **nem tem
coluna `options`** nessa versão.
**Correção:** setar em `NB_Global` — junto com `ic-route-blacklist`, nesta revisão.

### #5 — Gateway chassis explícito em **cada** LRP de trânsito

**Sintoma:** control plane funcionando (rotas anunciadas e aprendidas) e **data plane quebrado**.
**Diagnóstico:** `lsp-ts-az2` com `chassis=[]` no `Port_Binding` do SB local, e campo `gateway`
vazio no IC-SB.
**Causa:** sem gateway chassis fixado, o `ovn-ic` não associa gateway ao `Port_Binding` e o
tráfego inter-AZ **nunca é encapsulado**.
**Correção:** `lrp-set-gateway-chassis lrp-<plane>-<az>-ts <chassis> 1` — agora **por plane**.

### Da primeira revisão do `advanced-ovn-ic`

### #6 — `ovn-ic` precisa de `--unixctl` explícito

**Sintoma:** crash loop com `binding failed: No such file or directory`; o transit switch nunca
é propagado.
**Causa:** o binário compilado do fonte aponta o socket de controle para
`/usr/local/var/run/ovn/`, que não existe.
**Correção:** a unit passa `--unixctl=/opt/ovn-lab/ovn-ic.ctl`.

### #7 — O cliente OVS nativo do Incus 6.0 não conecta no `br-int`

**Sintoma:** `Failed to connect to OVS: ... listdbs failure - unexpected EOF`, mesmo com
`ovs-vsctl` funcionando.
**Correção:** `nictype=p2p` — o Incus só constrói o par veth; nós adicionamos o lado host ao
`br-int` com `external_ids:iface-id=<lsp>`. Agora **uma vez por plane** em que o workload vive.

### #8 — `GRANT` da role da aplicação nas tabelas

**Sintoma:** `appuser` conecta bem mas recebe `permission denied for table lab_info`.
**Causa:** as tabelas são criadas pelo superusuário `postgres`.
**Correção:** `GRANT` de schema, tabelas e sequences no `db_setup.sh`.

### Novos nesta revisão

### #9 — `ovn-controller` precisa que `/run/ovn` exista, e não aceita ser redirecionado

**O gotcha que mais caro custou.**

**Sintoma:** os dois hosts reiniciando `ovn-controller` a cada dois segundos.
**Causa:** ele faz bind do socket de controle em `/var/run/ovn/<pid>.ctl` e **não cria** o
diretório. Diferente do `ovn-ic` (#6), o binário do apt **não tem opção `--unixctl`**. E `/run`
é tmpfs — depois de um reboot o diretório some e todo start falha com
`binding failed: No such file or directory`.
**O dano:** o crash loop escreveu um **`ovn-controller.log` de 8 GB** (mais ~25 GB das mesmas
linhas espelhadas em `/var/log/syslog`) e **encheu um disco de 38 GB**, derrubando o lab inteiro.
**Correção, em duas frentes:**
- `RuntimeDirectory=ovn` na unit — o systemd cria `/run/ovn` antes de cada start. É a única
  correção disponível para esse binário.
- `RestartSec=10` (deliberadamente lento) **mais** uma política de **logrotate** em
  `/opt/ovn-lab/*.log` (`size 50M`, `rotate 3`, `compress`, `copytruncate`) com um **timer
  horário**, porque o timer padrão do logrotate é diário e um crash loop produz gigabytes numa
  hora. Um restart loop passa a ser barulhento em vez de fatal.

### #10 — Load balancer exige router com no máximo uma distributed gateway port

**Sintoma:** `Load-balancer is not supported yet when there is more than one distributed
gateway port on the router` — em silêncio, com o VIP simplesmente nunca respondendo.
**Correção:** mover o norte-sul para um **edge router separado**, deixando cada plane router com
exatamente uma DGP (a de trânsito). É a razão arquitetural do tier de edge.

### #11 — Um VIP dentro da subrede de tenant precisa do LB no **switch**

**Sintoma:** nada responde ARP pelo VIP; o pacote morre em `ls_in_l2_unknown`.
**Correção:** anexar o LB ao logical **switch** — é isso que instala o ARP responder.

### #12 — …mas um LB no switch é pulado para tráfego que entra por porta de router

**Sintoma:** clientes externos não são balanceados mesmo com o LB no switch.
**Causa:** `ls_in_pre_lb: ip && inport == <router port> → next`.
**Correção:** o LB também precisa estar num **router** — e num router **distribuído** o OVN só
o programa na DGP, por onde o tráfego externo não chega. A combinação que funciona é: LB no
**switch** do client (intra-VPC) **e** no **edge gateway router** (externo), este último sendo
gateway router de verdade (`options:chassis`), onde o LB vale em toda porta.

### #13 — `lb_force_snat_ip=router_ip` é rejeitado por este build

**Sintoma:** `bad ip router_ip in options of router`.
**Correção:** dar o endereço **literalmente** (`192.168.251.254` no edge, `169.254.100.1` no
plane router).

### #14 — Um VIP só é utilizável de dentro da AZ que o possui

**Sintoma:** um workload na AZ1 não alcança o VIP da AZ2.
**Causa:** seguindo de #11/#12 — chegando pelo transit switch, o pacote entra no
`ls-client-az2` por uma porta de router (LB do switch pulado) e o OVN também não programa o LB
do router nesse caminho de ingresso. Ele é roteado como endereço comum e morre no ARP.
**Correção:** um **service VIP na AZ do consumidor**, cujo backend é o workload remoto. O DNAT
acontece localmente e o pacote reescrito cruza a interconexão como tráfego comum. É por isso
que a AZ1 tem `10.10.1.200`.

### #15 — Um VIP na subrede de tenant precisa do LB no router **além** do switch

**Sintoma:** as regras de DNAT estão instaladas e o `ovn-trace` as mostra, mas um cliente
naquele switch **nunca recebe resposta de ARP** pelo VIP — então nem chega a mandar pacote. A
falha *parece* problema de roteamento.
**Correção:** os **dois** attachments, por razões diferentes: o do switch faz o balanceamento,
o do router torna o endereço **respondível**.

### #16 — `GRANT DELETE`, não só `SELECT/INSERT/UPDATE`

**Sintoma:** o publicador de estado da infra falha ao substituir as linhas de uma AZ.
**Causa:** ele apaga e reinsere a cada execução, para que estado que desapareceu não fique
pendurado no dashboard.
**Correção:** acrescentar `DELETE` aos grants — mesma forma de falha do #8, um privilégio adiante.

### Restrições de build (valem para os dois labs)

- **OVN compilado do fonte na v24.03.6** — o pacote `ovn-central` do Ubuntu 24.04 não traz o
  binário `ovn-ic` nem os schemas IC-NB/IC-SB.
- **OVS fixado na v3.3.0** — a `main` do OVS renomeou `obs_domain_id` → `obs_domain_imm`,
  quebrando o build do OVN 24.03.

---

## 6. As três gerações do lab, lado a lado

| Aspecto | `pratice-ovn-ic` | `advanced-ovn-ic` v1 | `advanced-ovn-ic` **atual** |
|---|---|---|---|
| Issue | CLO-77 | CLO-73 | CLO-73 (revisão) |
| VMs | 2 | 2 | **3** (a terceira é só árbitro) |
| Planes | 1 | 1 (`10.10.x`) | **2** — client + mgmt, isolados |
| Transit switches | 1 (`ts`) | 1 (`ts`) | **2** — `ts-client`, `ts-mgmt`, um fabric GENEVE |
| NICs por workload | 1 | 1 | **2** nas camadas de app (`db-vm` fica só mgmt) |
| Bancos IC | standalone no az1 | standalone no az1 (SPOF) | **cluster RAFT de 3 membros** |
| Banco de dados | — | `10.10.2.10`, rede de tenant | **`10.20.2.10`, só plane de gerência** |
| Load balancer | — | um VIP global na AZ1 | **um VIP por AZ** + um **service VIP** cross-AZ |
| Norte-sul | — | plane router com porta externa própria | **um edge gateway router por célula** + Edge Firewall |
| Workloads | network namespaces | containers Incus | containers Incus **dual-homed** |
| Aplicação | ICMP | uma app FastAPI, igual nas duas AZs | **React (AZ1) + Java (AZ2) + Postgres (mgmt)**, partida para cruzar as AZs |
| Dashboard | — | página de status de um backend | **dashboard de infraestrutura** alimentado pelas duas AZs via banco |
| Observabilidade | — | — | **Prometheus + Grafana por célula**, probe agent, 3 dashboards, gerador de carga |
| Testes | prints no terminal | prints no terminal | **`assert`** (T1-T7) + suíte de HA separada (H1-H4) |
| Automação | scripts bash | Ansible | Ansible + `reset.yml` |
| Segurança de log | — | — | **logrotate** + unit do `ovn-controller` corrigida |
| Gotchas | descobre 1-5 | + 6-8 | **+ 9-16** |

**O que não mudou em nenhuma geração:** OVN v24.03.6 do fonte, OVS v3.3.0, GENEVE UDP 6081,
NB 6641 / SB 6642 locais em 127.0.0.1, IC-NB 6645 / IC-SB 6646, MTU 1442, e os cinco gotchas
originais.

A renomeação (`lr-az1` → `lr-client-az1`/`lr-mgmt-az1`, `ts` → `ts-client`/`ts-mgmt`) é
justamente o motivo de `reset.yml` existir.

---

## 7. Cheat-sheet de verificação e troubleshooting

### Variáveis de ambiente

```bash
export OVN_NB_DB=tcp:127.0.0.1:6641
export OVN_SB_DB=tcp:127.0.0.1:6642
# os TRÊS membros do cluster, separados por vírgula:
export OVN_IC_NB_DB=tcp:172.18.3.175:6645,tcp:172.18.33.126:6645,tcp:172.18.3.240:6645
export OVN_IC_SB_DB=tcp:172.18.3.175:6646,tcp:172.18.33.126:6646,tcp:172.18.3.240:6646
```

Sem `sudo` — os bancos locais estão em TCP local. Só `ovs-vsctl` e `ovs-appctl` precisam.

### Control plane

```bash
ovn-ic-nbctl show                       # ts-client E ts-mgmt
ovn-ic-sbctl show                       # os dois gateways + portas de trânsito
ovn-nbctl lr-route-list lr-client-az1   # 10.10.2.0/24 (learned) — e NENHUM 10.20.x
ovn-nbctl lr-route-list lr-mgmt-az1     # 10.20.2.0/24 (learned) — e NENHUM 10.10.x
sudo ovs-vsctl show | grep -A2 geneve   # o túnel para o encap-ip do peer

# saúde do cluster de interconexão (em qualquer membro, inclusive o quorum):
sudo ovs-appctl -t /opt/ovn-lab/ic-nb.ctl cluster/status OVN_IC_Northbound
sudo ovs-appctl -t /opt/ovn-lab/ic-sb.ctl cluster/status OVN_IC_Southbound
```

No `cluster/status`, olhe três coisas: `Role:` (leader/follower/**candidate**), a contagem de
linhas `at tcp:` (deve ser 3) e `Leader:` (nunca `unknown` num cluster saudável).

### Data plane — os dois planes

```bash
# client plane, inter-AZ (sobre ts-client):
sudo incus exec app-vm-1 -- ping -c3 -I eth0 10.10.2.20

# mgmt plane, inter-AZ (sobre ts-mgmt) e intra-AZ:
sudo incus exec app-vm-1 -- ping -c3 -I eth1 10.20.2.10     # AZ1 → db, cruza AZ
sudo incus exec app-vm-2 -- ping -c3 -I eth1 10.20.2.10     # AZ2 → db, local

# isolamento: estes TÊM que falhar
sudo incus exec app-vm-1 -- ping -c2 -W2 -I eth0 10.20.2.10   # client → db
sudo incus exec app-vm-1 -- ping -c2 -W2 -I eth1 10.10.2.20   # mgmt → client
```

### MTU

```bash
sudo incus exec app-vm-1 -- ping -c2 -M do -s 1414 10.20.2.10   # OK
sudo incus exec app-vm-1 -- ping -c2 -M do -s 1415 10.20.2.10   # falha
```

### L4 — a aplicação

```bash
# no az1 — a cadeia inteira: nginx → service VIP → ts-client → Java → Postgres
curl -s http://10.10.1.100/api/status | python3 -m json.tool   # respondido por app-vm-2, na AZ2
curl -s http://10.10.1.100/api/infra  | head -c 400            # estado da infra, vindo do banco
curl -s -o /dev/null -w '%{http_code} em %{time_total}s\n' http://10.10.1.100/api/status

# o service VIP cross-AZ sozinho (de dentro do client plane):
sudo incus exec app-vm-1 -- curl -s http://10.10.1.200/api/health ; echo

# no az2 — o VIP dele fronteia o backend localmente:
curl -s http://10.10.2.100/api/health ; echo

# Postgres cru pelo plane de gerência (cross-AZ do az1, local do az2):
sudo incus exec app-vm-1 -- env PGPASSWORD=apppass \
  psql -h 10.20.2.10 -U appuser -d appdb -tAc 'SELECT message FROM lab_info LIMIT 1'
```

### Norte-sul, dashboard e Grafana

```bash
sudo incus exec app-vm-1 -- curl -sI https://example.org | head -1   # 200 (client plane)
sudo incus exec db-vm    -- curl -sI https://example.org | head -1   # 200 (mgmt plane)

ssh -L 8080:10.10.1.100:80 az1     # dashboard → http://localhost:8080/
ssh -L 3000:localhost:3000 az1     # Grafana   → http://localhost:3000  (admin/admin)
ssh -L 9090:localhost:9090 az1     # Prometheus → http://localhost:9090
```

### Onde olhar quando quebra

```bash
sudo systemctl status ovn-ic ovn-northd ovn-controller ovn-ic-nb-db ovn-ic-sb-db
sudo tail -n 40 /opt/ovn-lab/ovn-ic.log
sudo tail -n 40 /opt/ovn-lab/ovn-northd.log     # reclamações de LB / NAT aparecem aqui
sudo ovs-vsctl show
sudo incus exec <nome> -- ip -4 addr show       # as duas NICs, mtu 1442?
df -h                                            # gotcha #9 — o disco enche?
```

### Método de depuração em camadas

| Sintoma | Camada suspeita | Comando decisivo |
|---|---|---|
| Nenhum gateway no IC-SB | identidade da AZ | `ovn-nbctl get NB_Global . name` |
| Um `ts` não aparece no NB local | `ovn-ic` não roda / não propaga | `systemctl status ovn-ic`; `tail ovn-ic.log` |
| Rotas não anunciadas | opções de rota no lugar errado | `ovn-nbctl get NB_Global . options` |
| Um plane aprendeu rota do outro | blacklist / transit trocado | `ovn-nbctl lr-route-list <lr>` nos dois |
| Rotas OK mas sem ping inter-AZ | gateway chassis / Port_Binding | `ovn-sbctl list Port_Binding lsp-ts-<az>` |
| Sem túnel no `ovs-vsctl show` | encap-ip / firewall | `ovs-vsctl get open_vswitch . external_ids`; `ufw status` |
| Ping OK, TCP grande trava | MTU | `ping -M do -s 1414` vs `-s 1415` |
| VIP não responde nem ARP | LB só no switch ou só no router | `ovn-nbctl ls-lb-list <ls>` e `lr-lb-list <lr>` (#11, #15) |
| VIP responde só local | é VIP da outra AZ | precisa de service VIP local (#14) |
| LB nunca programado | router com 2 DGPs | `ovn-nbctl lrp-get-gateway-chassis`; ver `ovn-northd.log` (#10) |
| Backend cross-AZ pendura | `lb_force_snat_ip` | `ovn-nbctl get logical_router <lr> options` (#13) |
| Escrita no IC trava | quórum RAFT | `ovs-appctl -t .../ic-nb.ctl cluster/status` — 3 membros? líder? |
| Container sem rede | binding da porta | `ovs-vsctl get interface <veth> external_ids:iface-id` |
| Disco cheio / daemon reiniciando | `/run/ovn` sumiu no reboot | `systemctl status ovn-controller`; `du -sh /opt/ovn-lab` (#9) |

### Reset

```bash
ansible-playbook reset.yml && ansible-playbook site.yml
```

Depois de um **reboot** perdem-se as partes não persistentes do host (IP do `br-ex`, iptables,
rota do VIP): re-rodar `chassis.yml services.yml`.

---

## 8. Banco de perguntas e respostas

### Conceituais

**O que é o OVN-IC em uma frase?**
É o componente do OVN que federa múltiplas *Availability Zones* — instalações OVN
independentes — numa única rede lógica, usando dois bancos globais (IC-NB/IC-SB), um daemon
`ovn-ic` por AZ e transit switches compartilhados, sem que nenhuma AZ perca a autonomia do seu
control plane.

**Por que não simplesmente esticar um único control plane entre regiões?**
Escala e *blast radius*: um NB/SB único atendendo chassis em várias regiões sofre com latência
de OVSDB, e uma falha no control plane derruba tudo. O OVN-IC é **aditivo** — se IC-NB/IC-SB
ficarem indisponíveis, o tráfego intra-AZ continua.

**Qual a diferença entre NB/SB e IC-NB/IC-SB?**
NB/SB são **por AZ** e privados (bind em 127.0.0.1): intenção local e flows compilados.
IC-NB/IC-SB são **globais e compartilhados**: o IC-NB guarda a intenção da federação (quais
transit switches, quais AZs) e o IC-SB o estado de runtime (gateways registrados, rotas
anunciadas/aprendidas).

**O que exatamente o daemon `ovn-ic` faz?**
Conecta **quatro** bancos ao mesmo tempo (IC-NB, IC-SB, NB local, SB local) e sincroniza nas
duas direções: propaga os transit switches do IC-NB para o NB local, registra a AZ e seu
gateway no IC-SB, publica as rotas conectadas locais (`ic-route-adv`) e injeta no NB local as
rotas publicadas pelas outras AZs (`ic-route-learn`).

**Por que GENEVE e não VXLAN?**
GENEVE é o padrão do OVN porque tem campos de opção extensíveis que o OVN usa para carregar
metadados lógicos (ID de datapath lógico, portas de ingresso/egresso). VXLAN não tem espaço
suficiente, o que limita recursos do OVN.

**Quando OVN-IC é a escolha certa — e quando não é?**
**Certa** para múltiplas nuvens/regiões independentes que precisam de conectividade L2/L3
entre workloads, e para isolamento de compliance/blast-radius com tráfego controlado.
**Errada** para: site único (OVN puro basta), federação com redes **não-OVN** (use BGP/EVPN) e
necessidade só de HA de control plane (use NB/SB clusterizados, não OVN-IC).

### Sobre os dois planes

**O que exatamente separa os dois planes?**
Eles são **datapaths OVN distintos**: switch, router e transit switch próprios. O `ovn-ic`
anuncia as rotas de cada plane só no transit switch daquele plane, então `lr-client-az1` nunca
aprende `10.20.x` e `lr-mgmt-az1` nunca aprende `10.10.x`. Dentro do container, cada NIC ainda
recebe rota explícita para o supernet do próprio plane.

**Mas eles compartilham o mesmo túnel GENEVE. Isso não anula o isolamento?**
Não anula o isolamento de **roteamento**, que é o que está sendo prometido. Os dois transit
switches são carregados pelo mesmo túnel entre os mesmos dois chassis, e o que os separa é
serem datapaths distintos. É uma fronteira de tenancy, **não** de criptografia: quem consegue
injetar no `br-int` ou ler o túnel não é barrado por ela. O README é explícito sobre isso.

**Por que o `db-vm` tem só uma NIC?**
Porque torna o isolamento impossível de desfazer por erro de roteamento — **não existe
endereço** no client plane para alcançá-lo. É garantia mais forte que regra de firewall, e é o
que dá sentido ao teste "isto tem que falhar".

**Por que o edge precisa do Edge Firewall?**
Porque o edge necessariamente conhece rota para os **dois** planes — ele precisa, para devolver
respostas. Sem policy, um workload do client alcança seu gateway, é roteado ao edge e é
roteado de volta para dentro do mgmt, desfazendo em silêncio o isolamento. Duas
`lr-policy-add ... drop` cobrem as duas direções, deixando o norte-sul intocado. **Esse teste
pegou um vazamento real** antes das policies existirem.

**Se eu quisesse um terceiro plane, o que mudaria no código?**
Só dados: uma entrada nova na lista `planes:` de cada `host_vars`, com nome, LS, LR, CIDR,
gateway, MACs, transit switch e endereços de edge/SNAT; mais o transit switch novo em
`transit_switches`. As roles já rodam em loop sobre `planes`. Mas como muda a *forma* da
topologia, precisa de `reset.yml` antes do `site.yml`.

### Sobre o cluster RAFT

**Por que três membros e não dois?**
RAFT exige **maioria estrita** para eleger líder e confirmar escrita. Maioria de 2 é 2 — perder
qualquer membro congela o banco, o que não é melhor que um nó só. Maioria de 3 é 2, então o
cluster sobrevive à perda de qualquer um.

**Por que uma VM inteira só para votar?**
Porque uma AZ a mais teria custado chassis, GENEVE, workloads e um domínio de falha novo — sem
necessidade. O árbitro é deliberadamente mínimo: sem chassis, sem `br-int`, sem GENEVE, sem
NB/SB local, e recebe só `openvswitch-common` (não `openvswitch-switch`) justamente para que
não possa virar nó de datapath por acidente.

**Por que o árbitro não compila o OVN?**
Porque 1 vCPU / 1 GB não é máquina de build, e ele não precisa: schema é um arquivo JSON. A
role faz `slurp` dos dois schemas do bootstrap e `copy` para ele.

**Quais portas o cluster usa, e por que duas por banco?**
`6645`/`6646` são as portas de **cliente**, onde os `ovn-ic` conectam. `6647`/`6648` são as
portas de **peer RAFT**, para replicação entre membros. A porta RAFT é gravada **dentro do
arquivo `.db`** pelo `create-cluster`/`join-cluster`, não passada no `ExecStart`.

**Como os clientes sabem onde está o líder?**
Não precisam saber. Um ovsdb clusterizado atende leitura em qualquer membro e **redireciona
escrita para o líder**. Por isso `ic_nb_remote` lista os três membros separados por vírgula: o
`ovn-ic` continua funcionando quando o membro com quem falava cai.

**Por que `create-cluster` e `join-cluster` têm `creates:`?**
Porque o endereço RAFT é gravado no arquivo `.db` no momento da criação/entrada. Re-executar
sem guarda poderia **reformar** um cluster que já existe — destrutivo. O `creates:` faz a task
virar no-op quando o `.db` já está lá.

**O que acontece com o tráfego se os dois bancos IC caírem?**
Nada, imediatamente. Os flows já programados continuam encaminhando — o data plane não depende
dos bancos de interconexão em regime. O que para são **mudanças de topologia**. O `H3` do
`verify_ha.yml` afirma exatamente isso.

**Por que o teste de HA verifica escrita, e não só leitura?**
Porque um cluster **sem** quórum ainda serve leitura obsoleta de um follower — pareceria
saudável. Só uma escrita confirmada prova que há maioria. Por isso o H2 cria e apaga um transit
switch de sonda (`ha-probe-ts`).

**Como o playbook descobre quem é o líder, para derrubá-lo?**
Cada membro lê o próprio `cluster/status`; um `set_fact` marca `is_leader` em quem tiver
`Role: leader`; e um `run_once` reduz isso a `leader_host` usando
`ansible_play_hosts | map('extract', hostvars) | selectattr('is_leader')`.

### Sobre o load balancer (a parte mais contra-intuitiva)

**Por que o VIP é anexado em três lugares?**
Porque cada attachment resolve um problema diferente:
- no **switch** do client: instala o **ARP responder**, sem o qual um workload naquele switch
  nunca recebe resposta de ARP e não manda nem o primeiro pacote (#11, #15);
- no **edge gateway router**: é o que atende clientes de fora da VPC. No plane router não
  funcionaria, porque num router distribuído o OVN só programa o LB na DGP, por onde tráfego
  externo não chega (#12);
- no **plane router**: para que um cliente na outra AZ batendo neste VIP também seja balanceado
  — esse tráfego chega pela porta de trânsito, que **é** a DGP daquele router.

**Qual foi o sintoma que levou ao #15, e por que ele engana?**
As regras de DNAT estavam instaladas e o `ovn-trace` as mostrava, mas o cliente nunca recebia
ARP pelo VIP — então nunca mandava pacote. A falha *parece* problema de roteamento, e você
perde tempo olhando rota em vez de olhar quem responde ARP.

**Por que existe um "service VIP" `10.10.1.200` além do VIP normal?**
Porque um VIP só é utilizável de dentro da AZ que o possui (#14). O frontend na AZ1 não
consegue usar o VIP da AZ2: chegando pelo transit, o pacote entra no `ls-client-az2` por porta
de router, o LB do switch é pulado e o do router também não é programado nesse ingresso. A
solução é um VIP **local** cujo backend é o workload remoto — o DNAT acontece na AZ1 e o
pacote reescrito cruza a interconexão como tráfego comum.

**O que `lb_force_snat_ip` resolve, e por que é literal aqui?**
Sem ele, um request balanceado para o backend da outra AZ chegaria lá com o endereço do cliente
original, e a resposta sairia pela borda **daquela** AZ em vez de voltar por aqui — a conexão
penduraria. Forçando o SNAT, a resposta volta pelo mesmo caminho e o LB consegue desfazer o
DNAT. A palavra-chave documentada `router_ip` é **rejeitada** por este build
(`bad ip router_ip in options of router`), então o endereço é dado literalmente (#13).

**Por que isso obriga a subrede de edge a ser anunciada na interconexão?**
Porque o edge faz SNAT para o **próprio** endereço de edge, e o backend remoto só consegue
responder se a AZ dele tiver aprendido rota de volta para aquele endereço. É por isso que
`ic_route_blacklist` lista só as subredes **provider** (`br-ex`), e não as de edge.

**Por que o edge é um gateway router e não um distribuído?**
Porque pinado a um chassis (`options:chassis`) ele faz NAT sem precisar de uma distributed
gateway port, e o load balancer vale em **todas** as portas dele — não só na DGP.

### Sobre a aplicação e a observabilidade

**Por que partir a aplicação entre as AZs?**
Para que o fluxo sob teste seja o tráfego do próprio produto, não uma sonda sintética. E torna
a falha óbvia: se a interconexão quebra, o dashboard diz em um poll de 5s.

**Por que nginx faz proxy de `/api/*` em vez de o browser chamar a AZ2?**
Para que o browser só precise alcançar a AZ1, enquanto o salto entre AZs continua acontecendo
**dentro** do fabric. Também evita CORS e um segundo túnel SSH.

**Por que o backend é JDK puro, sem Spring?**
Os workloads são containers de 2 vCPU atrás de um NAT duplo; puxar árvore de dependências por
ali é lento e frágil, e nada ali precisa de framework. HTTP, JSON, JDBC e enumeração de
interfaces já estão no JDK, e o driver JDBC vem da distro.

**Como um processo só serve em dois planes?**
Dois `HttpServer`: o da API em `new InetSocketAddress(PORT, 0)` (todas as interfaces, alcançado
pelo client plane) e o de métricas em `new InetSocketAddress(MGMT_IP, METRICS_PORT)` — bindado
**explicitamente** ao IP de gerência. Raspar o backend da rede de tenant é impossível por
construção, não por política.

**O que é o `infra_probe.py` e por que ele roda no host?**
É um agente **read-only** que responde os botões "Testar" do dashboard com estado OVN ao vivo.
Precisa rodar no host porque transit switches, rotas aprendidas, túneis GENEVE e o cluster RAFT
só existem nos bancos OVN e no OVS do host — nenhum workload enxerga isso. Ele é bindado ao
endereço de underlay, só aceita conexão do SNAT do mgmt plane e do peer, e todo comando é uma
lista fixa escolhida por nome de uma whitelist: nenhuma entrada de usuário chega a um shell.

**Como o dashboard consegue mostrar estado das duas AZs?**
Cada host de AZ lê o próprio estado OVN e **publica na tabela `infra_state`** pelo plane de
gerência — de dentro de um workload que tem NIC de mgmt, porque o host em si não tem rota para
o banco (o isolamento funcionando). O backend Java lê a tabela; o React exibe. A visão da
infraestrutura percorre a arquitetura inteira para chegar na tela.

**Por que cada AZ tem seu próprio Prometheus em vez de um federado?**
Para que uma falha numa AZ não cegue o monitoramento da outra, e para manter tráfego de scrape
fora da interconexão. O custo é que painéis alimentados por um componente que só existe numa AZ
ficam vazios no Grafana da outra — o que é o isolamento funcionando. Federação cross-AZ pelo
`ts-mgmt` é o próximo passo listado.

**De onde vem a métrica de "bytes na interconexão"?**
Do `node_exporter` do **host** da AZ: os contadores por interface incluem `genev_sys_6081`, que
é literalmente a interface do túnel. Vira métrica real, não inferência.

**Por que o gerador de carga usa uma senoide?**
Para os gráficos terem **forma**. Com carga constante, uma linha reta é a norma e você não
percebe quando ela vira sinal; com um ciclo lento de 10 min, qualquer achatamento ou pico
destoa imediatamente.

**Por que o gerador faz uma consulta separada direto ao banco?**
Porque o caminho da aplicação **nunca** cruza o `ts-mgmt` — backend e banco estão os dois na
AZ2. Sem essa sonda periódica, o transit switch de gerência não carregaria nada mensurável.

### Sobre o Ansible

**Como a ordenação do RAFT é garantida sem `serial`?**
Pela estratégia `linear` padrão: cada task termina em **todos** os hosts antes de a próxima
começar. Então a task `create-cluster` (com `when: inventory_hostname == ic_bootstrap`) está
completa antes de a task `join-cluster` rodar em qualquer outro. Mesmo truque que garante que o
`ts-add` no az1 aconteça antes de qualquer `ovn-ic` subir.

**Por que `ovn_topology` é a única role que não reporta `changed=0`?**
Porque ela sempre reinicia o `ovn-ic` (gotcha #3). É uma mudança deliberada, não um defeito de
idempotência.

**O que muda para adicionar uma AZ3 de verdade?**
Um `host_vars/az3.yml` com identidade, edge, a lista `planes` e os workloads; acrescentá-la a
`[azs]` (e opcionalmente a `[ic_cluster]`). As roles já rodam em loop sobre `planes` e
`workloads`, e o cluster IC tolera um membro entrando.

**Como os backends do LB são montados?**
Por cadeia de filtros Jinja sobre o inventário: extrai `workloads` dos hosts, achata, filtra por
`role` em `service_tiers`, desce para `nics`, filtra `plane == client`, mapeia para `ip` e junta
com a porta. Para o service VIP cross-AZ, a mesma cadeia roda sobre **os dois** hosts de `azs`.

**Para que serve o `reset.yml`, e por que ele não apaga tudo?**
Serve para quando a **forma** da topologia muda — as roles são idempotentes para *valores*, mas
não sabem renomear nem remover objetos de um desenho anterior. Ele apaga containers, veths
`veth-*` do `br-int` (por prefixo, para pegar sobras de topologias antigas), os `.db` locais e do
IC, e as rotas de host para VIPs antigos. **Não** toca no build em `/opt/ovn-build` nem no
storage pool do Incus, que são as partes caras.

### Defesa do projeto / entrevista

**Qual foi o incidente mais sério, e o que você mudou por causa dele?**
O gotcha #9. Depois de um reboot, `/run` (tmpfs) perdeu o diretório `/run/ovn`, o
`ovn-controller` não o cria e — diferente do `ovn-ic` — o binário do apt **não tem** opção
`--unixctl`. Ele entrou em crash loop a cada 2 s, escreveu um log de **8 GB** (mais ~25 GB
espelhados em `/var/log/syslog`) e **encheu um disco de 38 GB**, derrubando o lab. Corrigi em
duas frentes: `RuntimeDirectory=ovn` na unit (a única correção disponível para esse binário) e
uma política de **logrotate horária** sobre `/opt/ovn-lab/*.log`, mais `RestartSec=10`. A lição
é que a correção da causa não bastava: sem limitar o log, o próximo crash loop faria o mesmo.

**Qual foi o problema mais difícil de diagnosticar?**
A sequência #11 → #12 → #15 do load balancer. O VIP não respondia, o `ovn-trace` mostrava as
regras de DNAT instaladas, e a falha parecia de roteamento — mas o cliente nunca recebia
**resposta de ARP**, então nem chegava a mandar pacote. Foram necessários dois attachments por
razões diferentes: o do switch faz o balanceamento, o do router torna o endereço respondível.

**Como você provou que o isolamento entre planes é real?**
Com testes que **devem falhar**: o `verify.yml` afirma que o client plane não alcança o banco e
que o mgmt plane não alcança o client plane, nos dois sentidos. Além disso o T1.4 afirma a
propriedade no control plane — cada plane router aprendeu só a subrede remota do próprio plane.
E esse teste pegou um vazamento real pelo edge compartilhado antes das policies existirem.

**Qual é o SPOF hoje?**
Não é mais o banco de interconexão — esse virou cluster RAFT de 3 membros. O que sobra é o
**PostgreSQL único na AZ2** (sem réplica), e o fato de as configurações de rede do host não
serem persistentes a reboot. Os dois estão na lista de melhorias, nessa ordem de prioridade.

**O que os números 3,191 ms vs 0,059 ms significam?**
RTT ao banco pelo plane de gerência: da AZ1 atravessa o `ts-mgmt` sobre GENEVE, da AZ2 é local.
**~54x** de diferença, medido numa execução só. É o custo real de acoplar um serviço a um banco
em outra AZ — o argumento concreto para uma réplica de leitura na AZ1.

**Por que os testes usam `assert` em vez de imprimir?**
Porque saída impressa depende de alguém ler. Com `assert`, um caminho quebrado **falha a
execução** — o que permite rodar a suíte em CI e tratar a topologia como código testado.

**Você cronometrou o cross-AZ com `time psql`?**
Não, e de propósito. O relógio de parede de `incus exec` + startup do `psql` afoga os poucos
milissegundos de interconexão e faria o intra-AZ parecer *mais lento*. O custo real sai do
backend de vida longa (latência por query) e do RTT cru via ping.

**Por que o teste de internet tem retry e os outros não?**
Porque ele sai do fabric inteiro até a internet de verdade; um timeout isolado diz mais sobre o
link upstream do que sobre o lab. Sem retry a suíte falharia de forma intermitente — e um teste
que grita lobo treina você a ignorá-lo, o que é pior do que não testar.

---

## 9. Inconsistências encontradas no repositório

Achados de **leitura estática** do código — não executei nenhum playbook. Valem como pontos de
atenção antes da próxima execução, e como material honesto caso alguém pergunte.

### 9.1 O `H3` do `verify_ha.yml` referencia a aplicação antiga

`verify_ha.yml`, linhas 121-124:

```yaml
incus exec {{ (workloads | selectattr('role', 'equalto', 'app') | first).name }}
  -- curl -s --max-time 10
  http://{{ (workloads | selectattr('role', 'equalto', 'app') | first).nics
            | selectattr('plane', 'equalto', 'client') | map(attribute='ip') | first }}:{{ backend_port }}/
...
that: "'db_message' in h3.stdout"
```

Três problemas empilhados, todos herdados da revisão FastAPI:

1. **Não existe mais role `app`.** Os roles definidos nos `host_vars` são `frontend`, `backend`,
   `db`, `loadgen` e `observability`. `selectattr(...) | first` sobre lista vazia levanta erro de
   template no Jinja — e `failed_when: false` **não** protege contra falha de renderização.
2. **O backend Java não expõe `/`.** Os contextos registrados são só `/api/health`,
   `/api/status`, `/api/infra`, `/api/flow`, `/api/test`, `/api/orders` e `/api/report`.
3. **`db_message` não existe mais na resposta.** O `status()` do `Backend.java` devolve
   `db_ok`, `db_time` e `db_latency_ms`.

**Correção provável:** trocar `'app'` por `backend_az`/`role == 'backend'`, apontar para
`/api/status` e afirmar `db_ok` em vez de `db_message`. Algo como:

```yaml
- name: "H3 Query the app while the interconnect control plane is degraded"
  vars:
    be: "{{ workloads | selectattr('role', 'equalto', 'backend') | list }}"
  when: be | length > 0
  ansible.builtin.command:
    cmd: >
      incus exec {{ be[0].name }} -- curl -s --max-time 10
      http://{{ (be[0].nics | selectattr('plane','equalto','client') | map(attribute='ip') | first) }}:{{ backend_port }}/api/status
```

> Nota: o README afirma que `H1-H4` passaram. Ou o `H3` foi executado numa versão anterior do
> arquivo, ou ele está falhando/sendo pulado sem que isso tenha sido notado. Vale rodar
> `ansible-playbook verify_ha.yml` e olhar o recap antes de citar esse resultado.

### 9.2 `reset.yml` roda no `quorum`, que não define `workloads` nem `planes`

```yaml
- name: Reset the lab's logical state
  hosts: azs:ic_cluster        # inclui o quorum
  ...
  cmd: incus delete -f {{ workloads | map(attribute='name') | join(' ') }} ...   # linha 24
  loop: "{{ planes | selectattr('vip', 'defined') | list }}"                     # linha 87
```

O `host_vars/quorum.yml` define só `az_private_ip` e `is_ic_arbiter` — não tem `workloads` nem
`planes`. As tasks que só fazem sentido nas AZs deveriam ter `when: inventory_hostname in
groups['azs']`, ou usar `workloads | default([])` / `planes | default([])`. As tasks que
**realmente** precisam rodar no quorum são as de parar e apagar os `.db` do IC.

### 9.3 O README lista um arquivo que não existe

O bloco *Repository layout* do `advanced-ovn-ic/README.md` cita:

```
├── GUIA-DO-ZERO.md           # from-zero explanation, in Portuguese
```

Esse arquivo não está na pasta. Ou foi removido sem atualizar o README, ou nunca foi commitado.

### 9.4 Fragilidade na montagem de `lb_backends`

Em `ovn_services/tasks/main.yml`:

```yaml
lb_backends: >-
  {{ workloads | selectattr('role', 'in', service_tiers) | ... | map(attribute='ip')
     | zip(workloads | rejectattr('role', 'equalto', 'db') | map(attribute='service_port'))
     | map('join', ':') | join(',') }}
```

O lado esquerdo do `zip` filtra por `service_tiers` (`[frontend, backend]`), mas o lado direito
filtra apenas `role != db` — o que na AZ1 inclui `obs-vm` e `load-vm`, que **não** têm
`service_port`. O `zip` trunca pelo menor lado, então na prática isso provavelmente funciona;
mas depende da ordem em que `workloads` está escrito no `host_vars` e da avaliação preguiçosa do
`map`. Trocar o lado direito para o mesmo filtro (`selectattr('role', 'in', service_tiers)`)
deixaria a intenção explícita e removeria o acoplamento com a ordem da lista.

### 9.5 Um detalhe menor de documentação

O `workloads/tasks/main.yml` está atualizado, mas vale conferir se sobrou alguma menção a
FastAPI nos comentários das roles — a migração para Java/React foi grande e comentários são o
que envelhece primeiro.

