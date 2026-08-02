# Guia do zero: o que é o projeto `advanced-ovn-ic`

Este documento explica o projeto assumindo que você não sabe nada de redes, virtualização ou Ansible. A ordem é: primeiro os conceitos base, depois o que o projeto faz com eles.

---

## Parte 1 — Conceitos de rede, do absoluto zero

### 1.1 Endereço IP, sub-rede e gateway

Todo computador numa rede tem um **endereço IP** (ex: `10.10.1.10`), como um número de telefone. Máquinas na mesma **sub-rede** (ex: `10.10.1.0/24`, que significa "todos os IPs de `10.10.1.0` a `10.10.1.255`") conseguem se falar diretamente. Para falar com um IP fora da própria sub-rede, a máquina manda o pacote para o **gateway** (o roteador da sua rede), que decide para onde encaminhar.

### 1.2 Switch vs. roteador

- **Switch**: conecta máquinas *dentro* da mesma sub-rede. Opera na "camada 2" (endereços MAC).
- **Roteador**: conecta sub-redes *diferentes* entre si. Opera na "camada 3" (endereços IP), decidindo rotas.

No projeto isso aparece como **logical switch (LS)** e **logical router (LR)** — versões desses dois conceitos implementadas em software, não em hardware físico.

### 1.3 TCP, portas e o "wire protocol"

Duas máquinas conversam via **TCP** numa **porta** específica (ex: porta 80 para HTTP, 5432 para Postgres). "Postgres wire protocol" só significa: o protocolo de bytes que o cliente Postgres e o servidor Postgres trocam entre si pela rede.

### 1.4 NAT / SNAT

**NAT** (Network Address Translation) troca o IP de origem/destino de um pacote. **SNAT** (Source NAT) troca o IP de *origem*: quando um container com IP privado (`10.10.1.10`) sai para a internet, o roteador reescreve o pacote para parecer que veio do IP público do host. Sem isso, a internet não saberia responder a um IP privado.

### 1.5 MTU e fragmentação

**MTU** é o tamanho máximo de um pacote que pode passar por um link sem ser quebrado (fragmentado). O padrão Ethernet é 1500 bytes. Se você **encapsula** um pacote dentro de outro (ver GENEVE abaixo), sobra menos espaço para os dados reais — por isso o MTU "interno" precisa ser menor que o MTU "externo".

---

## Parte 2 — Virtualização

### 2.1 VM vs. container

- **VM (máquina virtual)**: simula um computador inteiro, com seu próprio kernel. Pesada, mas isolamento forte.
- **Container**: processo isolado que compartilha o kernel do host. Leve, sobe em segundos.

O projeto roda em **três VMs**: `az1` e `az2` simulam dois datacenters/zonas diferentes e, dentro de cada uma, rodam **containers Incus** (os "workloads": o frontend, o backend e o banco). A terceira, `quorum`, não é uma zona — ela existe só para dar o terceiro voto ao cluster de bancos do interconnect (explicado em 3.7).

### 2.2 Incus

**Incus** é um gerenciador de containers/VMs (like Docker, mas mais próximo de "VM leve"). Aqui ele só é usado para criar os containers da aplicação — a parte de rede (OVN) é conectada manualmente, não pelo Incus.

---

## Parte 3 — Redes virtuais / SDN (o coração do projeto)

### 3.1 Por que redes virtuais existem

Num datacenter com centenas de tenants (clientes), você não quer que todo mundo compartilhe a mesma rede física. **SDN** (Software-Defined Networking) cria redes lógicas isoladas (como a `10.10.1.0/24` de um cliente) que são simuladas em software por cima de uma rede física comum (o "underlay").

### 3.2 OVS (Open vSwitch)

É o **switch virtual** que roda dentro de cada host Linux. Ele cria bridges (ex: `br-int`, `br-ex`) e portas virtuais, e sabe encaminhar pacotes conforme regras (flows).

### 3.3 OVN (Open Virtual Network)

OVN é a camada **de controle** por cima do OVS. Em vez de configurar flows manualmente em cada switch, você declara a intenção (“quero uma sub-rede X, um roteador Y ligando X e Z”) num banco de dados central, e o OVN traduz isso em flows OVS reais em cada host. As peças:

- **NB (Northbound DB)**: onde você declara a topologia lógica (switches, roteadores, portas). É a "API" do OVN.
- **`ovn-northd`**: processo que lê o NB e traduz para o SB.
- **SB (Southbound DB)**: estado físico/operacional — quem está conectado onde, quais rotas foram aprendidas.
- **`ovn-controller`**: roda em cada host ("chassis"), lê o SB e programa o OVS local.
- **Logical Switch (LS)**: uma sub-rede virtual (equivalente a uma VLAN, mas por software).
- **Logical Router (LR)**: um roteador virtual ligando LSs (e o mundo externo).
- **Chassis**: um host físico/VM que roda `ovn-controller` e hospeda workloads.

No projeto: cada AZ (`az1`, `az2`) tem seu **próprio** NB/SB/`ovn-northd`/`ovn-controller` — são duas nuvens OVN independentes, não uma só.

### 3.3.1 Dois "planos" de rede

Este projeto não tem uma rede virtual só: tem **dois planos isolados** em cada AZ.

- **Plano client** (`10.10.x.0/24`): a rede do cliente/tenant, onde a aplicação web é servida.
- **Plano mgmt** (`10.20.x.0/24`): a "rede de gerência de serviço", onde vive o banco de dados.

Cada plano tem seu próprio switch, seu próprio roteador e seu próprio transit switch. O banco só existe no plano mgmt — ele **não tem endereço nenhum no plano client**, então é impossível alcançá-lo de lá. Os containers da aplicação têm **duas placas de rede** (uma em cada plano): recebem requisições pelo plano client e falam com o banco pelo plano mgmt.

O isolamento é **lógico, não físico**: os dois planos passam pelo mesmo túnel GENEVE. O que os separa é serem datapaths OVN distintos, com rotas propagadas separadamente.

### 3.4 GENEVE (o túnel)

Quando um pacote de um container em `az1` precisa chegar a um container em `az2`, ele não viaja "cru" pela internet: é encapsulado dentro de outro pacote UDP (protocolo **GENEVE**, porta 6081) que viaja entre os IPs físicos dos dois hosts (`172.18.3.175` ↔ `172.18.33.126`, chamados de **encap-ip**). No destino, o host desencapsula e entrega o pacote original ao container certo. Esse encapsulamento consome ~58 bytes, por isso o MTU dos containers é 1442 (1500 − 58), e não 1500.

### 3.5 OVN-IC (Interconnect) — o núcleo do projeto

Cada AZ tem sua nuvem OVN isolada. **OVN-IC** é o mecanismo que **federa** (interliga) essas nuvens independentes, sem misturar suas bases de dados privadas. Ele funciona assim:

- Existem **dois bancos globais e compartilhados**, separados dos NB/SB locais: **IC-NB** (porta 6645) e **IC-SB** (porta 6646). Neste projeto eles rodam em **cluster RAFT nas três VMs** (ver 3.7).
- Você declara, no IC-NB, os **transit switches** — sub-redes especiais "de trânsito" que ligam o roteador de uma AZ ao da outra. Aqui são **dois**: `ts-client` (`169.254.100.0/24`) para o plano client e `ts-mgmt` (`169.254.200.0/24`) para o plano mgmt.
- O daemon `ovn-ic` (um por AZ) sincroniza: copia o transit switch do IC-NB para o NB local de cada AZ, registra os gateways de cada AZ no IC-SB, e propaga as **rotas aprendidas** (ex: "AZ1 sabe que `10.10.2.0/24` fica atrás do gateway de AZ2").
- Cada roteador local de cada plano (`lr-client-az1`, `lr-mgmt-az1`, e os equivalentes em AZ2) ganha uma porta extra plugada no transit switch **do seu plano**, e é isso que permite tráfego de `10.10.1.0/24` (AZ1) chegar a `10.10.2.0/24` (AZ2) — passando pelo transit switch e pelo túnel GENEVE. O plano mgmt faz o mesmo pelo `ts-mgmt`, separadamente.

Analogia: cada AZ é uma empresa com sua própria rede interna (VPN privada); o OVN-IC é o "acordo de peering" entre as duas, mais o link físico que as conecta, sem que uma empresa veja os detalhes internos da outra.

### 3.7 Cluster RAFT: por que existe uma terceira VM

Os bancos IC-NB/IC-SB são a única coisa compartilhada entre as AZs. Se rodassem numa VM só, essa VM seria um **ponto único de falha** do plano de controle da federação.

Então eles rodam em **cluster RAFT** de 3 membros: `az1`, `az2` e `quorum`. RAFT exige **maioria** para gravar. Com 2 membros a maioria é 2 — perder qualquer um trava o banco, ou seja, não melhora nada. Com 3 membros a maioria é 2, então o cluster continua funcionando se perder **um** membro qualquer.

A VM `quorum` não tem chassis, nem `br-int`, nem GENEVE, nem workloads. Ela existe **só para ser o terceiro voto**.

Importante: se os bancos IC caírem, o **tráfego não para** — os fluxos já programados continuam encaminhando. O que se perde é a capacidade de *mudar a topologia*. O teste `verify_ha.yml` prova exatamente isso.

### 3.8 Gateway Nodes e Edge Firewall

Os dois roteadores de plano de cada AZ não falam com a internet diretamente: eles entregam o tráfego a um **roteador de borda** (`lr-edge-<az>`) — o bloco "Gateway Nodes" do desenho. Ele faz o SNAT de cada plano para um endereço externo diferente.

Isso existe por dois motivos: é o que o desenho especifica, e é o que torna o load balancer possível (o OVN se recusa a programar um LB num roteador com mais de uma "porta de gateway distribuída").

Mas o roteador de borda conhece o caminho para os **dois** planos — o que reabriria a comunicação entre eles pela porta dos fundos. Por isso há duas regras que **bloqueiam tráfego de um plano para o outro** na borda: é o "Edge Firewall" do desenho.

### 3.6 Load balancer OVN e SNAT

- O **OVN LB** (load balancer nativo do OVN, não um HAProxy separado) recebe requisições num **VIP** (IP virtual) e as entrega a backends reais. Aqui há três: `10.10.1.100` na frente do frontend (AZ1), `10.10.2.100` na frente do backend (AZ2), e `10.10.1.200` — um VIP em AZ1 cujo backend está em **AZ2**, que é o que faz a chamada do frontend cruzar o interconnect.
- O **SNAT** de cada roteador de AZ permite que os containers privados saiam para a internet (troca o IP interno pelo IP do roteador/host antes de sair).

---

## Parte 4 — Ansible, do zero

Ansible é uma ferramenta de **automação de configuração**: em vez de digitar comandos manualmente em cada máquina, você descreve o **estado desejado** em arquivos YAML e o Ansible garante esse estado.

- **Control node**: a máquina que roda o Ansible (aqui, seu WSL Ubuntu). O Ansible não precisa estar instalado nas VMs alvo.
- **Managed nodes**: as máquinas configuradas — aqui, `az1` e `az2`. Só precisam de SSH e Python.
- **Inventory** (`inventory.ini`): lista de hosts e grupos (aqui: grupo `azs` = `az1`+`az2`, e `ic_host` = só `az1`).
- **Module**: unidade de trabalho que sabe atingir um estado (`apt` garante um pacote instalado, `copy` garante o conteúdo de um arquivo, `systemd_service` garante um serviço rodando).
- **Task**: uma chamada a um módulo.
- **Playbook**: arquivo YAML que mapeia hosts → lista de tasks/roles.
- **Role**: pacote reutilizável de tasks + templates + handlers, um por responsabilidade (ex: `ovn_central`, `ovn_topology`).
- **Handler**: task que só roda se **notificada** por outra task que mudou algo (aqui, reinicia `ovn-ic` depois que a topologia muda).
- **Idempotência**: rodar o playbook duas vezes não muda nada na segunda vez (reporta `changed=0`). É a grande vantagem sobre scripts bash soltos.
- **`become`**: rodar a task com `sudo`.

Cada execução mostra, por task/host: `ok` (já estava certo), `changed` (corrigiu agora), `failed` ou `skipped`.

---

## Parte 5 — O projeto em si

### 5.1 Objetivo

Provar, com tráfego **real** (HTTP + Postgres), que duas nuvens Incus/OVN independentes (`az1`, `az2`) podem ser federadas por OVN-IC, com **dois planos de rede isolados**, load balancer nativo e um banco gerenciado do outro lado do túnel.

A aplicação é **dividida entre as AZs de propósito**: o frontend (React) roda em AZ1, o backend (Java) e o banco rodam em AZ2. Assim, **usar a aplicação já é o teste** — cada refresh da tela atravessa o transit switch do plano client e depois o plano mgmt. E o que a tela mostra é justamente o estado da infraestrutura que a está transportando.

### 5.2 Arquitetura (mapeando os conceitos acima)

```
        IC-NB (6645) + IC-SB (6646) em CLUSTER RAFT: az1 + az2 + quorum
        ovn-ic (az1) ─────────────────────────────── ovn-ic (az2)
 ══════════════ AZ1 ══════════════║══════════════ AZ2 ══════════════
 host az1  172.18.3.175           ║   host az2  172.18.33.126
 NB 6641 · SB 6642 · northd · ovn-controller (cada AZ tem os seus)

 PLANO CLIENT  10.10.1.0/24  ── ts-client ──  10.10.2.0/24
  • app-vm-1  React + nginx :80  ──►  app-vm-2  backend Java :8080
  • VIP 10.10.1.100 (frontend)        • VIP 10.10.2.100 (backend)
  • VIP 10.10.1.200 ──────────────────► backend em AZ2 (cross-AZ)

 PLANO MGMT    10.20.1.0/24  ── ts-mgmt  ──  10.20.2.0/24
  • app-vm-1 (2ª placa)               • app-vm-2 (2ª placa)
                                      • db-vm  Postgres :5432

 cada AZ: lr-edge (Gateway Nodes) → SNAT por plano → br-ex → internet
```

O caminho de uma requisição:

```
navegador → VIP 10.10.1.100 → nginx+React (AZ1) → VIP 10.10.1.200
          → ts-client/GENEVE → backend Java (AZ2) → JDBC → db-vm (plano mgmt)
```

- **Underlay** (camada física): as duas VMs se enxergam pelos IPs privados; o túnel GENEVE roda ali.
- **Overlay lógico** (OVN): switch + roteador por AZ, ligados pelo transit switch; rotas aprendidas automaticamente.
- **Aplicação**: containers Incus plugados direto no `br-int` (o bridge OVS interno), rodando React+nginx (AZ1), backend Java (AZ2) e PostgreSQL (AZ2, plano mgmt).

### 5.3 As 4 rotas de tráfego que o projeto testa

1. **A própria aplicação cruzando AZs**: o frontend em AZ1 chama o backend em AZ2 através de um VIP com load balancer — cada refresh atravessa o `ts-client`.
2. **Consulta DBaaS cross-AZ**: `app-vm-1` (AZ1) → `db-vm` (AZ2), conexão Postgres real pelo transit switch — aqui o MTU/GENEVE realmente pesa na latência.
3. **Consulta intra-AZ**: `app-vm-2` (AZ2) → `db-vm` (AZ2), caminho local — comparação de latência limpa contra a rota 2 (medido: ~35ms cross-AZ vs. ~14ms intra-AZ).
4. **North-south via SNAT**: containers saem para a internet pelo roteador da AZ; o VIP também é alcançável de fora da malha.

### 5.4 Como os dois bancos OVN se organizam

| Banco | Porta | Escopo | Roda em | Quem acessa |
|---|---|---|---|---|
| NB local | 6641 | privado por AZ | cada AZ | `ovn-northd`, `ovn-nbctl`, `ovn-ic` daquela AZ |
| SB local | 6642 | privado por AZ | cada AZ | `ovn-northd`, `ovn-controller`, `ovn-ic` daquela AZ |
| **IC-NB** | 6645 (+6647 RAFT) | **global, em cluster** | az1 + az2 + quorum | `ovn-ic` das **duas** AZs |
| **IC-SB** | 6646 (+6648 RAFT) | **global, em cluster** | az1 + az2 + quorum | `ovn-ic` das **duas** AZs |

Ou seja: o tráfego de um workload nunca depende do NB/SB da *outra* AZ — só desses dois bancos globais + o túnel GENEVE.

### 5.5 As roles Ansible, uma a uma

| Role | O que faz, em termos simples |
|---|---|
| `common` | Instala dependências e **compila o OVN do zero** (a versão do pacote Ubuntu não inclui o `ovn-ic` nem seus schemas). Só roda a compilação (~10-20 min) uma vez, graças à idempotência. |
| `ovn_central` | Cria os bancos NB/SB e sobe `ovn-northd` como serviço systemd em cada AZ; em `az1` também sobe IC-NB/IC-SB. |
| `ovn_chassis` | Configura o OVS local (define o `encap-ip` para o GENEVE), sobe `ovn-controller`, cria o bridge externo `br-ex`, habilita roteamento IP + MASQUERADE no host, e libera firewall para as portas do GENEVE e do IC. |
| `ovn_topology` | Cria a identidade da AZ (`NB_Global.name`), o switch e roteador lógicos (LS/LR/LRP), fixa um "gateway chassis" na porta de trânsito, cria o transit switch no IC-NB, sobe `ovn-ic`, espera propagar, conecta o roteador ao transit switch, e reinicia `ovn-ic`. É aqui que moram as "pegadinhas" (gotchas) do OVN-IC. |
| `ovn_services` | Cria a porta externa + rota default + **SNAT do OVN** + o **load balancer** (VIP → backends) + rota de host até o VIP. |
| `incus` | Instala o Incus e roda `admin init --minimal`. |
| `ic_cluster` | Sobe IC-NB/IC-SB como **cluster RAFT de 3 membros** (az1, az2, quorum). |
| `workloads` | Cria as portas lógicas OVN (**uma por placa de rede**), os containers Incus com uma interface `p2p` por plano plugada no `br-int`, configura IP/MTU/rotas via netplan, e sobe as três camadas: PostgreSQL → backend Java → publicação do estado da infra → frontend React. |

### 5.6 Os playbooks (ordem de execução)

```
ping.yml       -> SSH + Python ok nas VMs
common.yml     -> dependências + compila OVN/OVS  (~10-20 min) + logrotate
ic_cluster.yml -> cluster RAFT IC-NB/IC-SB (az1 + az2 + quorum)
central.yml    -> NB/SB + northd de cada AZ
chassis.yml    -> OVS chassis + ovn-controller + br-ex + SNAT do host
topology.yml   -> LS/LR/LRP + transit switch + ovn-ic (a federação em si)
services.yml   -> load balancer OVN + SNAT OVN
incus.yml      -> instala e inicializa Incus
workloads.yml  -> containers (2 placas) + Postgres + backend Java + frontend React
verify.yml     -> T1-T7, com `assert` (falha o build se algo quebrar)
verify_ha.yml  -> H1-H4: derruba o líder do cluster RAFT e prova que sobrevive
reset.yml      -> desmonta tudo (necessário quando a FORMA da topologia muda)
site.yml       -> roda common.yml…workloads.yml em sequência
```

Cada estágio pode ser rodado e inspecionado isoladamente; `site.yml` roda tudo de uma vez.

### 5.7 As "gotchas" (por que cada uma existe)

1. A identidade de uma AZ vem de `NB_Global.name` — é assim que o OVN-IC sabe "quem é quem".
2. O transit switch só pode ser criado no IC-NB (nunca manualmente no NB local) — senão o `ovn-ic` não consegue sincronizá-lo.
3. É preciso reiniciar `ovn-ic` depois que toda a topologia estiver pronta, senão ele entra numa condição de corrida no startup.
4. As opções de propagação/aprendizado de rota (`ic-route-adv`/`ic-route-learn`) ficam em `NB_Global.options`, não no roteador — fácil de procurar no lugar errado.
5. É preciso fixar um "gateway chassis" na porta de trânsito do roteador, senão as rotas são anunciadas mas o plano de dados não funciona de verdade.
6. O `ovn-ic` compilado do source precisa de um `--unixctl` explícito, senão ele entra em crash-loop porque o diretório padrão do socket de controle não existe.
7. O cliente OVS nativo do Incus 6.0 não consegue conectar no `br-int`, então os containers usam NIC tipo `p2p` (o Incus só cria o par veth; a role conecta manualmente o lado do host ao `br-int` via `ovs-vsctl` com o `iface-id` certo).
8. A tabela de demonstração do banco é criada pelo superusuário `postgres`, então é preciso rodar `GRANT` explícito para o usuário da aplicação (`appuser`) conseguir ler/escrever nela (e `DELETE` também, para a tabela de estado da infra).
9. O `ovn-controller` precisa que `/run/ovn` exista e — diferente do `ovn-ic` — **não tem** opção `--unixctl` para apontar para outro lugar. Como `/run` é tmpfs, depois de um reboot o diretório some e ele entra em crash-loop. A correção é `RuntimeDirectory=ovn` no systemd. Esse loop chegou a escrever **8 GB de log e encher o disco de 38 GB**, derrubando o laboratório inteiro — por isso agora há um `logrotate` de hora em hora.
10. O OVN **não programa load balancer** em roteador com mais de uma "porta de gateway distribuída". Por isso o north-south foi movido para um roteador de borda separado.
11. Um VIP dentro da sub-rede do tenant precisa do LB **no switch** — só assim alguém responde ao ARP do VIP.
12. …mas o LB no switch é **ignorado** para tráfego que entra vindo de um roteador. Então clientes externos precisam do LB também num roteador de borda.
13. `lb_force_snat_ip=router_ip` é rejeitado nesta versão; tem que ser o IP literal.
14. Um VIP **só funciona de dentro da AZ que o hospeda**. Por isso AZ1 tem um VIP de serviço (`10.10.1.200`) apontando para o backend que está em AZ2.
15. Um VIP na sub-rede do tenant precisa do LB **no roteador além do switch**, senão ninguém responde ao ARP e o cliente nem chega a enviar o primeiro pacote.

### 5.8 Rodando do zero

No control node (WSL):

```bash
sudo apt update && sudo apt install -y pipx
pipx ensurepath && pipx install --include-deps ansible
exec $SHELL

cd ~/magalu/sdn/projects/ovn-ic/advanced-ovn-ic/ansible

ansible-playbook ping.yml
ansible-playbook common.yml
ansible-playbook central.yml
ansible-playbook chassis.yml
ansible-playbook topology.yml
ansible-playbook services.yml
ansible-playbook incus.yml
ansible-playbook workloads.yml
ansible-playbook verify.yml
```

(ou `site.yml` para rodar os estágios 2-8 de uma vez.)

### 5.9 Como verificar que está tudo funcionando

O jeito rápido: `ansible-playbook verify.yml` (T1-T7) e `ansible-playbook verify_ha.yml` (H1-H4). Tudo é `assert`, então falha sozinho se algo quebrar.

Na mão:

```bash
# plano de controle: os DOIS transit switches e as rotas por plano
ovn-ic-nbctl --db=tcp:172.18.3.175:6645 show
ovn-nbctl lr-route-list lr-client-az1   # 10.10.2.0/24 (aprendida), e NENHUMA 10.20.x
ovn-nbctl lr-route-list lr-mgmt-az1     # 10.20.2.0/24 (aprendida), e NENHUMA 10.10.x

# cluster RAFT dos bancos do interconnect
sudo ovs-appctl -t /opt/ovn-lab/ic-nb.ctl cluster/status OVN_IC_Northbound

# ISOLAMENTO: estes DEVEM falhar
sudo incus exec app-vm-1 -- ping -c2 -W2 -I eth0 10.20.2.10   # client -> banco
sudo incus exec app-vm-1 -- ping -c2 -W2 -I eth1 10.10.2.20   # mgmt -> client

# MTU (58 bytes de GENEVE)
sudo incus exec app-vm-1 -- ping -c2 -M do -s 1414 10.20.2.10   # ok
sudo incus exec app-vm-1 -- ping -c2 -M do -s 1415 10.20.2.10   # falha

# a aplicação inteira, de ponta a ponta (respondida pela AZ2)
curl -s http://10.10.1.100/api/status | python3 -m json.tool

# o dashboard no navegador
ssh -L 8080:10.10.1.100:80 az1   # depois abra http://localhost:8080/
```

### 5.10 Decisões de design (por quê, não só o quê)

- **OVN controlado manualmente, Incus só cria os workloads**: o Incus tem rede OVN nativa, mas gerencia o NB sozinho e não expõe configuração de OVN-IC. Para manter controle total do interconnect, o OVN é operado à mão.
- **Containers, não VMs**: os hosts são pequenos (2 vCPU/8GB) e KVM aninhado seria pesado; containers sobem rápido e passam pelo mesmo caminho de dados GENEVE/MTU.
- **Load balancer nativo do OVN em vez de HAProxy**: menos peças móveis, demonstra um recurso do próprio OVN.
- **Um único Postgres em AZ2 (sem réplica)**: simplicidade proposital — gera de graça o contraste de latência intra vs. inter-AZ; o próximo passo natural seria primary/replica.
- **Aplicação dividida entre as AZs de propósito**: assim o fluxo testado é o tráfego real do produto, não uma sonda artificial. E se o interconnect quebrar, o dashboard avisa em segundos.
- **Backend em Java puro (JDK), sem Spring/Maven**: os containers são pequenos e estão atrás de NAT duplo; baixar uma árvore de dependências ali é lento e frágil, e nada aqui precisa de framework.
- **Banco com uma placa só**: como `db-vm` não tem endereço no plano client, o isolamento não depende de regra de firewall — simplesmente não existe caminho.

### 5.11 Próximos passos documentados no README

Persistir configuração de rede do host após reboot, usar `ansible-vault` para a senha do banco (hoje em texto puro), health checks no load balancer, Postgres primary/replica, TLS nos bancos, ACLs de verdade nos switches (hoje o isolamento é por roteamento), suporte a mais de 2 AZs, workloads como VMs Incus, múltiplas réplicas por AZ, observabilidade, e `molecule`/`ansible-lint` no CI.

(A alta disponibilidade dos bancos IC **já foi feita** nesta revisão — é o cluster RAFT da seção 3.7.)

---

## Glossário rápido

| Termo | Significado |
|---|---|
| AZ | Availability Zone — aqui, cada VM (`az1`/`az2`) simula uma zona de disponibilidade independente |
| VPC | Rede virtual privada de um tenant, aqui federada entre as duas AZs |
| LS / LR / LRP | Logical Switch / Logical Router / Logical Router Port — peças da topologia OVN |
| NB / SB | Northbound / Southbound DB — intenção declarada vs. estado operacional do OVN |
| IC-NB / IC-SB | Northbound/Southbound **globais** usados só para federação (OVN-IC) |
| Transit switch (`ts`) | Sub-rede especial que liga os roteadores de AZs diferentes |
| Chassis | Host que roda `ovn-controller` e hospeda workloads |
| GENEVE | Protocolo de túnel (encapsulamento) usado entre os hosts das AZs |
| Plano client / mgmt | As duas redes isoladas do projeto: tenant (`10.10.x`) e gerência (`10.20.x`) |
| RAFT | Algoritmo de consenso; exige maioria dos membros para gravar — daí a 3ª VM |
| Gateway Nodes | O roteador de borda de cada AZ, que faz o SNAT dos dois planos |
| VIP | IP virtual do load balancer |
| SNAT | Tradução do IP de origem, usada para tráfego que sai para a internet |
| Idempotência | Rodar de novo não muda nada se já está no estado desejado |
