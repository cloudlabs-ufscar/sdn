# OVN do kernel ao Incus

> OVN do kernel ao Incus — estudo de baixo nível

Documento de estudo consolidado. Base: o repositório [`cloudlabs-ufscar/sdn`](https://github.com/cloudlabs-ufscar/sdn) (treinamento + projetos OVN-IC + os fontes vendorizados de OVN/OVS), o código-fonte do OVN (`post-v26.03.0`, vendorizado em `projects/core/ovn`), do Open vSwitch (`post-v3.7.0`, em `projects/core/ovs`) e do [Incus](https://github.com/lxc/incus) (HEAD de setembro/2026).

Onde o laboratório do repositório usa **OVN 24.03.6 / OVS 3.3.0**, e o fonte vendorizado é **26.03**, eu marco a diferença explicitamente. Números de tabela e nomes de estágio mudam entre releases — a estrutura não.

`OVN post-v26.03.0 · OVS post-v3.7.0 · Laboratório OVN 24.03.6 / OVS 3.3.0 · Incus HEAD 09/2026 · 6 partes`


---

## Como usar este documento

Ele tem seis partes, em ordem de profundidade crescente e depois de volta para cima:

| Parte | O que cobre | Quando ler |
| --- | --- | --- |
| **I** | Recapitulação dos módulos e projetos do repositório `sdn` | Primeiro — é o mapa do que você já construiu |
| **II** | O chão: kernel Linux, netlink, OVS, OpenFlow, conntrack, Geneve | Antes de tocar em OVN "de verdade" |
| **III** | OVN por dentro: bancos, northd, logical flows, ovn-controller, pinctrl, OVN-IC | O núcleo do estudo |
| **IV** | Como o OVN conversa com o sistema operacional — sockets, syscalls, netlink, o caminho completo de um pacote | A resposta direta ao "como ele se comunica com o SO" |
| **V** | Incus + OVN: do comando de CLI até a instrução executada pelo SO | A segunda pergunta do pedido |
| **VI** | Laboratórios, troubleshooting, referência rápida, lacunas de conhecimento | Para praticar e consultar |

Um aviso metodológico que vale para tudo: **OVN não é um daemon que encaminha pacotes**. Nenhum processo do OVN toca um pacote no caminho rápido (exceto os casos de `pinctrl`, tratados na Parte III). OVN é um **compilador distribuído**: ele transforma intenção declarativa (banco Northbound) em fluxos OpenFlow em cada hipervisor, e o `ovs-vswitchd` + datapath do kernel fazem o trabalho real. Toda vez que algo "não funciona", a pergunta certa é *em qual etapa da compilação isso parou*.

---

# Parte I — Recapitulação do repositório `cloudlabs-ufscar/sdn`

O repositório tem dois eixos: `training/` (trilha de aprendizado modular) e `projects/` (implementações abertas, de nível de produção).

## I.1 — A trilha `training/`

A metodologia declarada é *"Ground-to-Cloud"*: entender um pacote atravessando um switch virtual antes de gerenciar milhões deles num datacenter.

### Fase 0 — Pré-requisitos

**Módulo 0 — Fundamentos de redes e SO** (`training/module0/`, com `script0.sh`).
É o único módulo com README extenso em Markdown. Cobre:

- **Linguagens do ecossistema**: Python (control plane, Neutron, Ansible), Go (Kubernetes, Cilium, **Incus**), C/C++ (OVS/OVN core), Rust (Linkerd, Aya/eBPF), Bash (cola).
- **Modelo OSI**, hardware de rede, protocolos (TCP/UDP, DNS, DHCP, **ARP**, **NAT**).
- **Virtual networking**: VM, vNIC, vSwitch, vRouter; VLAN, VPN, **VXLAN**.
- **Primitivas Linux**: *namespaces* (isolamento), *veth* + *bridges* (a "camada física em software"), *netfilter* (onde ACLs são aplicadas).
- **Plataformas de orquestração**: Kubernetes/CNI, OpenStack/Neutron, **Incus/LXD** (bridges Linux e OVN).
- **Laboratório prático completo**: OVS instalado na mão, duas VMs QEMU/KVM com *cloud-init* (uma com driver `e1000`, outra `virtio` — comparação de desempenho), TAPs adicionados a uma bridge OVS, NAT via iptables/MASQUERADE, `dnsmasq` como DHCP, um *network namespace* com *veth pair* plugado na mesma bridge, e *benchmark* com `iperf3`.

O ponto pedagógico do e1000 vs virtio é exatamente o assunto da Parte II deste documento: quantas cópias e quantas trocas de contexto um pacote sofre entre o *guest* e o datapath.

### Fase 1 — Fundamentos de comutação virtual

- **Módulo 1 — Introdução ao OVS**: arquitetura `ovs-vswitchd`, `ovsdb-server` e o *kernel datapath*.
- **Módulos 2 e 3 — OpenFlow**: o protocolo e o uso de **múltiplas flow tables** para lógica de processamento composta (`resubmit`, *pipelines*).

### Fase 2 — SDN e redes de sobreposição

- **Módulo 4 — Arquitetura SDN**: o desacoplamento entre *control plane* e *data plane*.
- **Módulo 5 — Overlay Geneve com OVS**: tunelamento e encapsulamento.

### Fase 3 — Orquestração avançada e OVN

- **Módulo 6 — Introdução ao OVN**: bancos Northbound/Southbound e como o OVN abstrai o OVS numa rede lógica.
- **Módulo 7 — OpenStack Neutron**: como o SDN sustenta nuvens grandes e como o Neutron fala com OVS/OVN.

### Fase 4 — Diagnóstico e desempenho

- **Módulo 8 — Packet Walkthrough**: auditoria completa da travessia de um pacote pela pilha virtual.
- **Módulo 9 — OVS Troubleshooting**: depuração de fluxos, conectividade e otimização do datapath.

> Os módulos 1–9 estão no repositório como **PDFs de slides** (`CLOUD-net-0X - ...pdf`). O conteúdo textual expandido dos módulos 8 e 9 é essencialmente o que as Partes II e IV deste documento desenvolvem.

**Requisitos de laboratório declarados**: Linux (Ubuntu 22.04+ ou Arch), virtualização aninhada habilitada, pacotes `openvswitch-switch`, `qemu-kvm`, `cloud-utils`.

## I.2 — `projects/core/` — o ferramental

`projects/core/README.md` é o catálogo de ferramentas. Vale registrar a avaliação feita ali, porque ela orienta escolha de ambiente:

| Ferramenta | Papel | Limitação registrada |
| --- | --- | --- |
| **OVN** (`core/ovn`) | Fonte canônica: código, *schemas*, documentação. Primeira parada em qualquer dúvida de comportamento | — |
| **OVS** (`core/ovs`) | A base: `ovs-vswitchd`, `ovsdb-server`, `ovs-vsctl`/`ovs-ofctl` | Versões OVN↔OVS precisam casar (ex.: OVN 24.03 ↔ OVS 3.3.0) |
| **ovn-fake-multinode** | Simula múltiplos nós OVN em containers numa máquina só | **Compartilha o kernel**: mascara o datapath real (túnel Geneve resolvido no mesmo kernel, sem firewall entre chassis, MTU/encap não exercitados) |
| **ovn-heater** | Framework de teste de carga/escala sobre `ovn-fake-multinode` | Foco em control plane |
| **ovn-kubernetes** | CNI do Kubernetes baseada em OVN | Referência de integração em produção |
| **ovn-event-exporter** (CloudFerro) | Exporter Prometheus de eventos *create/update/delete* nas tabelas OVN-SB | Substituiu o desenvolvimento interno |

A conclusão registrada: **para validação production-like (datapath real, Geneve entre hosts, firewall entre chassis), nenhuma dessas ferramentas substitui VMs/hosts separados.** Essa é a razão de o projeto ter migrado de *namespaces* para VMs.

## I.3 — `projects/ovn-exporter/` — observabilidade do OVN-SB (CLO-57)

**Problema**: as métricas nativas do OVN não cobrem granularmente os eventos de *create/update/delete* nas tabelas do **Southbound** — onde vivem `Chassis`, `Port_Binding`, `MAC_Binding`, `Logical_Flow`. Sem isso, é difícil diagnosticar gargalos ou entender o ritmo de mudança aplicado pelo control plane.

**Proposta**: um *exporter* Python para Prometheus + dashboard Grafana, desenvolvido sobre `ovn-fake-multinode`.

**Desfecho**: o exporter foi construído e capturava os eventos, mas a integração final não avançou porque a **CloudFerro publicou o [`ovn-event-exporter`](https://github.com/CloudFerro/ovn-event-exporter)** open source. O valor retido foi o entendimento de baixo nível de **como o OVN-SB comunica as ações que passam por ele** — que é exatamente o mecanismo de *monitor/update2* do OVSDB descrito na Parte III.

## I.4 — `projects/ovn-ic/pratice-ovn-ic/` — a prova de conceito (CLO-77)

Dois AZs OVN independentes federados por OVN-IC.

**Fase 1** (descartada): um único VM, dois AZs simulados inteiramente com *network namespaces* — um namespace por AZ com seu próprio `ovs-vswitchd` e `ovn-controller`. Validou a topologia lógica de graça, mas mascarou o datapath (túnel resolvido no mesmo kernel, sem firewall entre chassis, MTU/overhead de encap não exercitados, bugs de sincronização entre `ovn-controller`s escondidos).

**Fase 2**: duas VMs Ubuntu 24.04 separadas (AZ1 `172.18.3.181`, AZ2 `172.18.17.9`), IC-NB/IC-SB hospedados na VM1 e acessados por TCP da VM2.

Obstáculo de empacotamento que definiu o resto do projeto: **o pacote `ovn-central` do Ubuntu 24.04 não entrega o binário `ovn-ic` nem os schemas IC-NB/IC-SB.** Foi necessário compilar **OVN v24.03.6 do fonte**, com **OVS fixado em v3.3.0**.

### As cinco lições de depuração (reutilizadas em todo o resto do repositório)

1. **A identidade do AZ vem de `NB_Global.name`.** Sem esse campo preenchido no NB local, nenhum *gateway* é registrado no IC-SB, mesmo com a topologia perfeita.
2. **O *transit switch* é gerenciado pelo `ovn-ic`.** O `ts` **não** deve ser criado à mão no NB local: o `ovn-ic` detecta o `ts` declarado no IC-NB e o cria automaticamente em cada AZ, já com a anotação `interconn-ts`. Criar manualmente causa colisão.
3. **Race condition na inicialização do `ovn-ic`.** A primeira instância pode processar a notificação de `lsp-ts-az*` antes de o cache local conter o `lrp-az*-ts` correspondente, produzindo `Can't get router uuid for transit switch port` seguido de `Route sync ignores port ... Deleting it` — a porta é descartada e nunca reprocessada. Correção: **reiniciar o `ovn-ic` depois de a topologia estar completa**, forçando uma leitura limpa pelo monitor OVSDB.
4. **`ic-route-adv` e `ic-route-learn` ficam em `NB_Global.options`, não no `Logical_Router`** (no OVN 24.03.6). Confirmado inspecionando `strings` no binário `ovn-ic` — a tabela `Transit_Switch` nem tem coluna `options` nessa versão. Sem isso, o log de debug mostra `Route ad: skip network 10.0.1.1/24 of lrp lrp-az1-ls.`
5. **Gateway chassis explícito no LRP de trânsito.** Mesmo com o control plane correto (rotas anunciadas e aprendidas no IC-SB), o data plane fica quebrado: `lsp-ts-az2` aparece com `chassis=[]` no `Port_Binding` local. É preciso `ovn-nbctl lrp-set-gateway-chassis lrp-az1-ts az1-chassis 1`; sem isso o `ovn-ic` não associa gateway ao Port_Binding e o tráfego inter-AZ nunca é encapsulado em Geneve.

**Resultado**: gateways registrados nos dois AZs, túneis Geneve estabelecidos, propagação automática de rotas conectadas (`10.0.1.0/24` e `10.0.2.0/24` visíveis como `(learned)` do outro lado), pings intra e inter-AZ com 0% de perda. Scripts idempotentes (`setup-az1.sh`, `setup-az2.sh`, `verify.sh`).

## I.5 — `projects/ovn-ic/advanced-ovn-ic/` — o laboratório multi-AZ estilo DBaaS (CLO-73)

A evolução: três VMs, provisionamento inteiramente por **Ansible**, e uma aplicação real de três camadas deliberadamente **partida entre os AZs**, de modo que *usar a aplicação já é o teste do interconnect*.

### Arquitetura

- **Duas células (AZ1, AZ2)**: cada uma com nuvem Incus, control plane OVN próprio (NB 6641, SB 6642, `northd`), `ovn-ic`, um chassis e um *edge tier*.
- **Uma VM árbitro (quorum)**: sem chassis, sem `br-int`, sem Geneve — apenas o terceiro membro do cluster RAFT dos bancos de interconnect.
- **IC-NB (6645 cliente / 6647 RAFT)** e **IC-SB (6646 / 6648)** como **cluster RAFT de 3 membros**.

### Os dois planos

|  | **Plano de cliente** | **Plano de gerência** |
| --- | --- | --- |
| Propósito | tráfego de tenant (web) | tráfego de operador (o DBaaS) |
| Switch/router | `ls-client-<az>` / `lr-client-<az>` | `ls-mgmt-<az>` / `lr-mgmt-<az>` |
| Federado por | `ts-client` (`169.254.100.0/24`) | `ts-mgmt` (`169.254.200.0/24`) |
| Sub-redes | `10.10.1.0/24` (AZ1), `10.10.2.0/24` (AZ2) | `10.20.1.0/24`, `10.20.2.0/24` |
| Load balancer | sim — um VIP por AZ + um *service VIP* em AZ1 | não |
| Membros | `app-vm-1` (React), `app-vm-2` (Java) | `app-vm-1`, `app-vm-2`, **`db-vm`** |

O isolamento é **lógico, não físico**: os dois *transit switches* trafegam pelo **mesmo túnel Geneve** entre os mesmos dois chassis. O que os separa é serem *datapaths* OVN distintos, e o `ovn-ic` anunciar as rotas de cada plano apenas no *transit switch* daquele plano. A suíte de verificação afirma isso diretamente: `lr-client-*` precisa aprender a sub-rede `10.10.x` do peer e **nenhuma** rota `10.20.x`.

`db-vm` tem **uma única NIC**, no plano de gerência — não existe endereço pelo qual o plano de cliente pudesse sequer nomeá-lo. Isso é uma garantia mais forte que uma regra de firewall.

### O *edge* por célula (Gateway Nodes)

Ambos os roteadores de plano têm rota default para um **roteador de borda por célula** (`lr-edge-<az>`), dono do uplink para `br-ex`, que faz SNAT de cada plano para um endereço externo próprio. Existe por duas razões:

1. É o que o desenho especifica — um *gateway tier* por célula.
2. É o que torna o **load balancer legal**: o OVN recusa programar um LB num roteador com mais de uma *distributed gateway port* (`Load-balancer is not supported yet when there is more than one distributed gateway port on the router`). Um roteador de plano que também tivesse porta externa teria duas: a de trânsito e a externa.

Como o edge necessariamente conhece rota para **ambos** os planos (para devolver respostas), duas **logical router policies** do OVN descartam qualquer trânsito plano-a-plano no edge. Esse é o *Edge Firewall* do diagrama, e `verify.yml` o testa nas duas direções. Antes dessas policies existirem, o teste **pegou um vazamento real**.

### Aplicação e observabilidade

React+nginx em AZ1 → (`/api/*` via *service VIP* `10.10.1.200`) → backend Java em AZ2 → JDBC → PostgreSQL em `db-vm` (plano de gerência). Cada refresh do dashboard atravessa `ts-client` sobre Geneve e depois o plano de gerência. O dashboard renderiza o **estado real da própria infraestrutura que o carrega** (lido via `ovn-nbctl`, `ovn-ic-sbctl`, `ovs-vsctl`, `ovs-appctl cluster/status` e publicado na tabela `infra_state`).

Observabilidade por célula: Prometheus + Grafana **no plano de gerência**, com `node_exporter` (inclusive contadores de `genev_sys_6081`), um *probe agent* que expõe estado OVN como métricas, métricas do backend Java, `postgres_exporter` e um gerador de carga senoidal. Retenção limitada nos dois eixos (`6h` **e** `1GB`).

### Os papéis Ansible (o mapa de quem faz o quê)

| role | o que faz |
| --- | --- |
| `common` | base apt (OVS/OVN), desabilita os serviços OVN do apt, **compila `ovn-ic` + schemas IC do fonte**, instala política de `logrotate` |
| `ic_cluster` | o cluster RAFT de 3 membros IC-NB/IC-SB (`create-cluster` + `join-cluster`, espera por líder) |
| `ovn_central` | arquivos `.db` NB/SB por AZ e `ovn-northd` como unidades systemd |
| `ovn_chassis` | config do chassis no OVS (encap-ip Geneve), `ovn-controller`, `br-ex`, `ip_forward` + MASQUERADE no host, firewall |
| `ovn_topology` | identidade e opções de rota em `NB_Global`, **ambos** os transit switches no IC-NB, e por plano: LS/LR/LRP, gateway chassis no LRP de trânsito, anexação ao TS propagado |
| `ovn_services` | o roteador de borda (uplink, SNAT por plano, policies do Edge Firewall) e o **load balancer por AZ** |
| `incus` | instala Incus e `admin init --minimal` |
| `workloads` | uma LSP OVN **por NIC**, containers Incus com veth `p2p` por plano plugado em `br-int`, netplan estático, e as camadas em ordem de dependência |

### Gotchas adicionais (6 a 16) — o material mais valioso do repositório

6. **`ovn-ic` precisa de `--unixctl` explícito.** O build do fonte usa `/usr/local/var/run/ovn/`, que não existe → crash loop com `binding failed: No such file or directory`.
7. **O cliente OVS nativo do Incus 6.0 não consegue anexar a `br-int`** → NICs de workload usam `nictype=p2p`: o Incus cria o par veth e *nós* adicionamos o lado host ao `br-int` com `ovs-vsctl ... external_ids:iface-id=<lsp>`.
8. **Conceder a role da aplicação na tabela de demo**, senão `appuser` conecta mas toma `permission denied for table lab_info`.
9. **`ovn-controller` precisa que `/run/ovn` exista, e não aceita ser instruído a outro lugar.** Ele liga o socket de controle em `/var/run/ovn/<pid>.ctl` e não cria o diretório. Diferente do `ovn-ic`, o binário do apt **não tem opção `--unixctl`** — a única correção é `RuntimeDirectory=ovn` na unidade systemd. `/run` é tmpfs, então após reboot o diretório some. **O crash loop escreveu um log de 8 GB e encheu um disco de 38 GB**, derrubando o laboratório inteiro.
10. **Um load balancer exige um roteador com no máximo uma distributed gateway port** — falha silenciosa, o VIP simplesmente não responde.
11. **Um VIP dentro da sub-rede do tenant precisa do LB no *switch* lógico**; só no roteador, nada responde ARP pelo VIP e o pacote morre em `ls_in_l2_unknown`. No switch, o OVN instala o *ARP responder*.
12. **…mas um LB no switch é ignorado para tráfego que entra por porta de roteador** (`ls_in_pre_lb: ip && inport == <router port> → next`). Clientes externos precisam do LB **também** num roteador — e num roteador *distribuído* o OVN só o programa na distributed gateway port, onde o tráfego externo não chega. A combinação que funciona: LB no **switch** de cliente (intra-VPC) **e** no **roteador de borda** (externo), este sendo um *gateway router* de verdade (`options:chassis`).
13. **`lb_force_snat_ip=router_ip` é rejeitado por esse build** — `bad ip router_ip in options of router`. O endereço tem de ser literal.
14. **Um VIP só é utilizável de dentro do AZ que o possui.** Chegando pelo transit switch, o pacote entra em `ls-client-az2` por porta de roteador (LB de switch pulado, #12) e o OVN também não programa o LB do roteador nesse caminho de ingresso. A solução é um **service VIP no AZ do consumidor** cujo backend é o workload remoto — o DNAT acontece localmente e o pacote reescrito cruza o interconnect como tráfego comum. É por isso que AZ1 tem `10.10.1.200`.
15. **Um VIP na sub-rede do tenant precisa do LB no roteador *além* do switch**, ou nada responde ARP — falha que se parece exatamente com problema de roteamento.
16. **Conceder `DELETE`, não só `SELECT/INSERT/UPDATE`** — o publicador de estado de infraestrutura substitui as linhas do AZ a cada execução.

### Resultados medidos

- `GET /api/status` pelo VIP do AZ1 é respondido pelo `app-vm-2` **em AZ2** com banco vivo, em ≈ **45 ms**.
- RTT ao banco: **3,191 ms** de AZ1 (sobre o interconnect) vs **0,059 ms** de AZ2 (local) — **~54x**, o custo real do interconnect medido numa execução.
- MTU: `-s 1414 -M do` passa, `1415` falha (workload MTU 1442 = 1500 − 58 de Geneve).
- HA: parar o **líder** RAFT elege outro em segundos, o cluster degradado 2-de-3 ainda **commita escritas**, e o data plane não percebe.

### A decisão de design que mais importa para a Parte V

> **"OVN dirigido manualmente, Incus só fornece os workloads. O Incus tem rede OVN nativa, mas gerencia o NB ele mesmo e não expõe configuração de OVN-IC. Para manter controle total do interconnect, rodamos OVN/`ovn-ic` na mão e anexamos instâncias Incus ao `br-int` via portas OVS com o `iface-id` correto."**

Essa frase é o motivo pelo qual a Parte V existe: ela descreve com precisão o **limite** da integração Incus↔OVN na versão usada. Como se verá, o Incus **mais recente** já tem *network integrations* que falam IC-NB/IC-SB diretamente — o que muda a conclusão.

---

# Parte II — O chão: kernel Linux, OVS e OpenFlow

Nada em OVN faz sentido sem essa camada. Se você domina esta parte, o OVN vira "só" um gerador de fluxos.

## II.1 — As primitivas de rede do kernel que o OVN usa

### Network namespaces

Um *network namespace* é uma instância independente da pilha de rede do kernel: interfaces, tabelas de rota, regras netfilter, `/proc/sys/net`, sockets. Criado por `clone(CLONE_NEWNET)` ou `unshare(CLONE_NEWNET)`; `ip netns` apenas gerencia bind mounts em `/var/run/netns/<nome>` para os arquivos `/proc/<pid>/ns/net`.

Relevância para OVN: cada container Incus (e cada workload dos labs) vive num netns. O que o OVN vê é apenas a **ponta host do veth** plugada no `br-int`. O OVN não entra no namespace, não configura IP dentro dele, e não sabe que ele existe.

### veth, tap, internal ports

- **veth pair**: duas interfaces ligadas ponta a ponta. Escrita numa ponta = recepção na outra, dentro do kernel, sem cópia para userspace. É como containers se conectam ao `br-int`.
- **tap**: interface cujo "outro lado" é um *file descriptor* em userspace (`/dev/net/tun`). É como QEMU/KVM conectam uma VM: o vhost-net ou o próprio QEMU lê/escreve no fd. O módulo 0 do treinamento usa exatamente isso.
- **OVS internal port**: interface criada pelo próprio OVS (`ovs-vsctl add-port br0 x -- set interface x type=internal`). O kernel a vê como netdev normal; o datapath a vê como vport. `br-int` e `br-ex` são *internal ports* homônimas das bridges.

### Netlink

A interface de controle kernel↔userspace. Três famílias importam aqui:

| Família | Uso | Quem usa |
| --- | --- | --- |
| **`NETLINK_ROUTE` (rtnetlink)** | criar/remover links, endereços, rotas, vizinhos (ARP/ND), regras, VRFs | `ip`, `ovs-vswitchd` (via `netdev-linux`), `ovn-controller` (route-exchange, EVPN) |
| **`NETLINK_GENERIC` (genetlink)** | a API do datapath do OVS: famílias `ovs_datapath`, `ovs_vport`, `ovs_flow`, `ovs_packet`, `ovs_meter`, `ovs_ct_limit` | `ovs-vswitchd` (`lib/dpif-netlink.c`) |
| **`NETLINK_NETFILTER`** | conntrack: listar, apagar, contar entradas, zonas, timeouts | `ovs-vswitchd` (`ct-dpif`), `conntrack` CLI |

O `ovn-controller` moderno também usa rtnetlink diretamente: `controller/route-exchange-netlink.c` e `controller/neighbor-exchange-netlink.c` escrevem rotas e vizinhos em **VRFs do kernel** para a funcionalidade de *dynamic routing* (BGP/EVPN) — esse é um dos poucos pontos onde o OVN escreve no estado de rede do host.

### conntrack (`nf_conntrack`)

O rastreamento de conexões do Linux, reutilizado pelo OVS via a ação `ct()`. Conceitos que o OVN usa intensamente:

- **zona**: um espaço de nomes de conexões (16 bits). O OVN aloca zonas por porta lógica e por roteador — é por isso que existem os registradores `MFF_LOG_CT_ZONE` (reg13, bits 0–15), `MFF_LOG_DNAT_ZONE` (reg11) e `MFF_LOG_SNAT_ZONE` (reg12).
- **estados**: `new`, `est`, `rel`, `rpl`, `inv`, `trk`, `dnat`, `snat`. Expostos ao OpenFlow no campo `ct_state`.
- **`ct_mark`, `ct_label`**: metadados de 32 e 128 bits carimbados na entrada de conntrack, persistentes por conexão. O OVN usa `ct_mark` para ACLs *allow-established* e para marcar conexões de load balancer (`ct_lb_mark`).
- **NAT no conntrack**: `ct(nat(dst=...))` faz o kernel criar a tradução e tratar automaticamente o tráfego de retorno.

O mapeamento zona↔porta é publicado de volta no OVSDB local, em `Open_vSwitch.external_ids:ct-zone-<nome>` — um detalhe útil em depuração (`controller/ct-zone.c`).

## II.2 — Arquitetura do Open vSwitch

Três peças, três responsabilidades:

```
            ovs-vsctl / ovsdb-client                 ovs-ofctl / controlador OpenFlow
                    │  JSON-RPC                                │  OpenFlow 1.0–1.5
                    ▼  (unix socket db.sock)                   ▼  (unix socket br-int.mgmt / TCP 6653)
          ┌──────────────────┐   OVSDB     ┌──────────────────────────────────┐
          │  ovsdb-server    │◄───────────►│           ovs-vswitchd           │
          │  conf.db         │  monitor/   │  ofproto → ofproto-dpif → dpif   │
          └──────────────────┘  transact   └──────────────┬───────────────────┘
                                                          │ genetlink (ovs_flow, ovs_packet, ovs_vport)
                                                          ▼
                                           ┌──────────────────────────────────┐
                                           │  datapath (openvswitch.ko)       │
                                           │  flow table exata + megaflow     │
                                           └──────────────────────────────────┘
```

### `ovsdb-server`

Servidor de banco transacional que fala **OVSDB (RFC 7047)** sobre JSON-RPC. Métodos que importam: `transact`, `monitor` / `monitor_cond` / `monitor_cond_since`, `update3`, `lock`, `get_schema`. Escuta em socket unix (`/var/run/openvswitch/db.sock`) e, opcionalmente, em `ptcp:`/`pssl:`. Guarda `conf.db` — a configuração persistente do switch (bridges, portas, interfaces, `external_ids`).

Propriedades importantes:

- **Monitores são push**: um cliente registra interesse e recebe *updates* incrementais. É exatamente assim que `ovn-controller` observa o SB e que o Incus observa o NB.
- **`monitor_cond_since`** permite retomar de um ponto (`last-txn-id`), evitando redownload completo após reconexão — essencial em escala.
- **Clustering RAFT**: `ovsdb-server` suporta cluster (`ovsdb-tool create-cluster` / `join-cluster`), com eleição de líder, e é isso que o laboratório avançado usa para IC-NB/IC-SB. Escritas exigem maioria estrita; leituras podem ser servidas por *relay*.

### `ovs-vswitchd`

O daemon de plano de controle local. Camadas internas (nomes de arquivo do fonte, úteis para ler o código):

- **`bridge.c`**: reconcilia o que está no OVSDB com o que existe de fato.
- **`ofproto`** (`ofproto/ofproto.c`): implementação genérica de um switch OpenFlow — tabelas, grupos, meters, barreiras, `packet-in`/`packet-out`.
- **`ofproto-dpif`** (`ofproto/ofproto-dpif*.c`): a implementação que usa um *datapath*. Contém:
- **`ofproto-dpif-xlate.c`** — o **tradutor**: pega um pacote (ou uma chave de fluxo) e "executa" o pipeline OpenFlow em software, produzindo uma lista de **ações de datapath** e uma **máscara** (quais bits foram realmente examinados).
- **`ofproto-dpif-upcall.c`** — as *handler threads* (processam *upcalls*) e as *revalidator threads* (revalidam e removem fluxos do datapath).
- **`dpif`** (`lib/dpif.c`): a abstração de datapath. Dois provedores principais:
- **`dpif-netlink`** → datapath do kernel (`openvswitch.ko`).
- **`dpif-netdev`** → datapath em userspace (DPDK/AF_XDP), com PMD threads.

### O datapath do kernel

O módulo `openvswitch.ko` implementa "datapaths" (análogos a bridges) com "vports" (portas) e uma **tabela de fluxos exata + megaflow**. O documento `Documentation/topics/datapath.rst` do OVS descreve o contrato:

> Quando um pacote chega num vport, o módulo extrai sua *flow key* e busca na tabela de fluxos. Se há correspondência, executa as ações. Se não há, **enfileira o pacote para userspace** (um *upcall*), e o userspace tipicamente instalará um fluxo para tratar os pacotes seguintes inteiramente no kernel.

Um detalhe de design que explica muita coisa: **o kernel envia junto com o pacote a flow key que ele próprio extraiu**. O userspace compara com a sua própria noção de flow key e:

- se coincidem, tudo bem;
- se o kernel decodificou **mais** campos, tudo bem (o userspace usa a chave do kernel);
- se o userspace decodificou mais campos que o kernel, ele **encaminha o pacote manualmente** sem instalar fluxo — correto, mas lento.

Isso é o que permite kernel e userspace de versões diferentes coexistirem.

### Caches: EMC, megaflow e o custo de um *upcall*

No caminho do kernel:

1. **Flow table (megaflow)**: entradas com **máscara** — `ip,nw_dst=10.0.0.0/24` cobre 256 endereços com uma entrada. A máscara vem exatamente dos bits que o tradutor de userspace **olhou**. Quanto mais campos suas regras examinam, mais específicas (e mais numerosas) as megaflows.
2. No datapath de **userspace** (`dpif-netdev`) há ainda o **EMC** (Exact Match Cache) e o **SMC**, antes do `dpcls` (classificador com *subtables*).

O custo de um *miss*: pacote → `ovs_packet` genetlink (`OVS_PACKET_CMD_MISS`) → *handler thread* → `xlate` do pipeline OpenFlow inteiro → `OVS_FLOW_CMD_NEW` instalando a megaflow → `OVS_PACKET_CMD_EXECUTE` para o pacote original. Ordens de magnitude mais caro que o *hit*. É por isso que:

- explosão de regras OpenFlow com muitos campos ⇒ explosão de megaflows ⇒ *miss rate* alto ⇒ CPU do `ovs-vswitchd` em 100%;
- `ovs-appctl upcall/show` e `dpctl/show -s` são as primeiras ferramentas num problema de desempenho.

As **revalidator threads** varrem periodicamente os fluxos do datapath, re-traduzem e removem os que ficaram obsoletos ou ociosos (`max-idle`, `max-revalidator`, `flow-limit` no `Open_vSwitch.other_config`).

### Vports de túnel

Túneis Geneve/VXLAN/GRE aparecem como **vports do datapath**, não como interfaces normais. O OVS cria uma netdev "coletora" por tipo/porta (`genev_sys_6081`, `vxlan_sys_4789`) e usa **tunnel metadata no fluxo** (`tun_id`, `tun_src`, `tun_dst`, `tun_metadata*`) em vez de uma interface por peer. Isso é o que permite milhares de túneis com um único device — e é por isso que `tcpdump -i genev_sys_6081` vê o tráfego **desencapsulado** de todos os peers.

### Offload de hardware

Duas rotas:

- **`tc-flower` offload** (`other_config:hw-offload=true`): o `ovs-vswitchd` traduz megaflows para regras `tc` na `ingress qdisc` da NIC, e a NIC (com `switchdev` + *representors*) faz o encaminhamento. É o modelo usado com SmartNICs/DPUs, e o que o Incus expõe como `acceleration=sriov`/`vdpa` nas NICs OVN.
- **DPDK / AF_XDP**: datapath em userspace, PMD threads em *poll mode*, sem o kernel no caminho.

## II.3 — OpenFlow como o OVN o usa

O OVN usa OpenFlow 1.3+ **com extensões Nicira**. Não é OpenFlow "de livro": é um assembly de pipeline.

### Elementos essenciais

- **Tabelas numeradas** (0–254) e `goto_table` / `resubmit`.
- **`resubmit(port, table)`** (extensão NX): reprocessa o pacote a partir de outra tabela **e volta** — é uma chamada de sub-rotina, não um salto. O OVN o usa massivamente.
- **Registradores**: `reg0`–`reg15` (32 bits), `xreg0`–`xreg7` (64), `xxreg0`–`xxreg3` (128), mais `metadata` (64 bits). O OVN atribui significados fixos, listados na Parte III.
- **`conjunction`**: permite expressar produto cartesiano de conjuntos (ex.: 100 IPs × 50 portas) sem 5000 regras. O OVN gera `conjunction` para ACLs com *address sets* e *port groups*. Fluxos conjuntivos usam cookie 0 e casam em `conj_id`.
- **`learn`**: instala fluxos dinamicamente a partir de um pacote (usado em *MAC learning* / FDB).
- **`ct(...)`**: chama o conntrack — `ct(table=N, zone=..., nat, commit, exec(...))`.
- **`controller(...)`**: envia o pacote ao controlador local via *packet-in* — é o gatilho do `pinctrl` do OVN.
- **Grupos** `select` com *buckets*: ECMP e balanceamento.
- **Meters**: limitação de taxa; o OVN os usa para *copp* (control plane protection policy).

### Ferramentas

```
ovs-ofctl -O OpenFlow15 dump-flows br-int          # todos os fluxos (grande!)
ovs-ofctl -O OpenFlow15 dump-flows br-int table=8  # uma tabela
ovs-appctl ofproto/trace br-int in_port=3,dl_src=...,dl_dst=...  # simula a travessia
ovs-dpctl dump-flows                                # megaflows do kernel
ovs-appctl dpctl/dump-flows -m                      # com máscaras e contadores
ovs-appctl upcall/show                              # taxa de upcalls por handler
ovs-appctl coverage/show                            # contadores internos
```

`ofproto/trace` é a ferramenta mais importante da caixa: ela mostra **tabela a tabela** o que aconteceu, incluindo os `resubmit`, e no final a lista de ações de datapath e a megaflow que seria instalada.

## II.4 — Geneve, e por que o OVN o prefere

**Geneve** (RFC 8926) encapsula o quadro Ethernet original em UDP (porta destino **6081**), com um cabeçalho que carrega:

- **VNI de 24 bits**;
- **opções TLV de comprimento variável** — a diferença essencial em relação ao VXLAN.

O overhead típico em IPv4: 14 (Ethernet externo) + 20 (IP) + 8 (UDP) + 8 (Geneve base) + 8 (uma opção TLV de 4 bytes + cabeçalho) = **~58 bytes**, daí o MTU de workload 1442 usado nos laboratórios (1500 − 58). Esse número aparece no teste T6 do lab avançado: `ping -M do -s 1414` passa, `1415` falha (1414 + 8 ICMP + 20 IP = 1442).

### O que o OVN coloca no túnel

Da documentação de arquitetura do OVN (`ovn-architecture.7.xml`, seção *Tunnel Encapsulations*), o OVN anota cada pacote com três metadados:

- **identificador de datapath lógico, 24 bits** — da coluna `tunnel_key` da tabela `Datapath_Binding` do SB;
- **porta lógica de ingresso, 15 bits** — ID 0 reservado; 1..32767 para portas (`Port_Binding.tunnel_key`);
- **porta lógica de egresso, 16 bits** — 0..32767 portas; **32768..65535 grupos multicast** (`Multicast_Group.tunnel_key`).

Com **Geneve**, o datapath lógico vai no **VNI**, e as portas de ingresso/egresso vão numa **opção TLV com class `0x0102`, type `0x80`, valor de 32 bits**.

Com **VXLAN**, não há espaço: o OVN entra em "modo VXLAN" e reduz os espaços para 12 bits de datapath e 12 bits de porta de egresso, **sem porta de ingresso**. As consequências, textualmente do doc:

- máximo de **4096** redes;
- máximo de **2048** portas por rede;
- **ACLs que casam na porta lógica de ingresso não funcionam**;
- **o recurso de interconnect (OVN-IC) não é suportado**.

Mais dois argumentos práticos a favor do Geneve citados no doc: portas UDP de origem randomizadas (melhor distribuição em ECMP no underlay) e disponibilidade de offload de encap/decap em NICs.

> **Consequência direta para o seu laboratório**: OVN-IC exige Geneve. Não há caminho de VXLAN.

### Como ver o túnel

```
ovs-vsctl show                       # mostra as portas ovn-<chassis>-N tipo geneve
ovs-appctl dpif/show                 # vports do datapath
ip -d link show genev_sys_6081       # o device coletor
tcpdump -ni <nic-underlay> udp port 6081   # encapsulado
tcpdump -ni genev_sys_6081                 # desencapsulado, todos os peers
```

---

# Parte III — OVN por dentro

## III.1 — O fluxo de informação

O OVN é uma cadeia de compilação com quatro elos. Do mais abstrato ao mais concreto:

```
  CMS (Incus, Neutron, ovn-kubernetes, você com ovn-nbctl)
        │  OVSDB: transact na Northbound
        ▼
  ┌──────────────────┐
  │  OVN Northbound  │  intenção: Logical_Switch, Logical_Router, ACL, Load_Balancer, NAT, DHCP_Options
  └────────┬─────────┘
           │  ovn-northd  (compilador; roda 1 ativo por AZ, com standby)
           ▼
  ┌──────────────────┐
  │  OVN Southbound  │  Logical_Flow, Datapath_Binding, Port_Binding, Chassis, Encap, MAC_Binding,
  └────────┬─────────┘  Multicast_Group, Address_Set, Port_Group, DNS, Meter, Load_Balancer...
           │  ovn-controller (1 por chassis; monitor OVSDB condicional)
           ▼
  ┌──────────────────┐
  │  OpenFlow no br-int │  via socket unix do ovs-vswitchd
  └────────┬─────────┘
           │  ovs-vswitchd → genetlink
           ▼
       datapath (kernel ou userspace)
```

Duas direções de informação, e isso importa:

- **Para baixo (intenção)**: NB → SB → OpenFlow. Empurrada por `ovn-northd` e `ovn-controller`.
- **Para cima (estado físico)**: cada `ovn-controller` **escreve** no SB — registra seu `Chassis`, seus `Encap`, faz *claim* de `Port_Binding` (coluna `chassis`), publica `MAC_Binding` aprendidos, `ovn-installed`. O `ovn-northd` pode sincronizar parte disso de volta ao NB (ex.: `Logical_Switch_Port.up`).

## III.2 — Os bancos e seus schemas

Todos são `ovsdb-server` com schemas distintos. Portas por convenção:

| Banco | Schema | Porta padrão | Socket unix | Quem escreve | Quem lê |
| --- | --- | --- | --- | --- | --- |
| **NB** | `ovn-nb.ovsschema` | 6641 | `/run/ovn/ovnnb_db.sock` | CMS | `ovn-northd`, `ovn-ic` |
| **SB** | `ovn-sb.ovsschema` | 6642 | `/run/ovn/ovnsb_db.sock` | `ovn-northd`, `ovn-controller`, `ovn-ic` | `ovn-controller` |
| **IC-NB** | `ovn-ic-nb.ovsschema` | 6645 | `ovn_ic_nb_db.sock` | admin / CMS | `ovn-ic` de todos os AZs |
| **IC-SB** | `ovn-ic-sb.ovsschema` | 6646 | `ovn_ic_sb_db.sock` | `ovn-ic` | `ovn-ic` |

### Tabelas do Northbound que você precisa conhecer

- **`NB_Global`** — singleton. `name` (**a identidade do AZ para o OVN-IC** — gotcha #1), `nb_cfg` (contador de sequência para *barriers*), `options` (incluindo `ic-route-adv`, `ic-route-learn`, `vxlan_mode`, `northd_probe_interval`, `use_logical_dp_groups`), `connections`, `ssl`.
- **`Logical_Switch`** — um domínio L2. Colunas: `ports`, `acls`, `qos_rules`, `load_balancer`, `dns_records`, `other_config` (`subnet`, `exclude_ips`, `mcast_*`), `forwarding_groups`.
- **`Logical_Switch_Port`** — a porta. `name` (**é isso que vira `external_ids:iface-id` no OVS**), `addresses` (lista de `"MAC IP"`, ou `"dynamic"`, ou `"unknown"`, ou `"router"`), `port_security`, `type` (`""`, `router`, `localnet`, `localport`, `vtep`, `external`, `remote`, `virtual`), `options` (ex.: `router-port`, `network_name`, `requested-chassis`, `requested-tnl-key`), `dhcpv4_options`, `dhcpv6_options`, `up`, `enabled`, `tag`/`parent_name` (containers dentro de VM).
- **`Logical_Router`** — `ports`, `static_routes`, `policies`, `nat`, `load_balancer`, `options` (`chassis` ⇒ **gateway router**; `dnat_force_snat_ip`, `lb_force_snat_ip`, `always_learn_from_arp_request`).
- **`Logical_Router_Port`** — `networks` (CIDRs), `mac`, `peer`, `gateway_chassis` / `ha_chassis_group` (⇒ **distributed gateway port**), `ipv6_ra_configs`, `options` (`redirect-type=bridged`, `reside-on-redirect-chassis`, `requested-encap-ip`).
- **`NAT`** — `type` (`snat`/`dnat`/`dnat_and_snat`), `external_ip`, `logical_ip`, `logical_port`, `external_mac`, `allowed_ext_ips`, `options:stateless`.
- **`Load_Balancer`** / **`Load_Balancer_Group`** / **`Load_Balancer_Health_Check`** — `vips` (mapa `"IP:porta" → "IP1:porta1,IP2:porta2"`), `protocol`, `selection_fields`, `options` (`reject`, `skip_snat`, `affinity_timeout`, `hairpin_snat_ip`).
- **`ACL`** — `direction` (`from-lport`/`to-lport`), `priority` (0–32767), `match` (a linguagem de match do OVN), `action` (`allow`, `allow-related`, `allow-stateless`, `drop`, `reject`, `pass`), `log`, `severity`, `tier`, `sample_new`/`sample_est`.
- **`Address_Set`**, **`Port_Group`** — conjuntos nomeados usados nos matches; viram `conjunction` no OpenFlow.
- **`DHCP_Options`**, **`DNS`**, **`QoS`**, **`Meter`**, **`Mirror`**, **`Chassis_Template_Var`**, **`Static_MAC_Binding`**.
- **`Gateway_Chassis`**, **`HA_Chassis`**, **`HA_Chassis_Group`** — prioridades para a porta de gateway distribuída.

### Tabelas do Southbound

- **`SB_Global`** — `nb_cfg` (ecoado do NB), `options` (ex.: `mac_prefix`, `svc_monitor_mac`, `ignore_lsp_down`), `ipsec`.
- **`Chassis`** / **`Chassis_Private`** — um registro por hipervisor, **escrito pelo `ovn-controller`**. `name` = `external_ids:system-id` do OVS. `hostname`, `encaps`, `other_config` (`ovn-bridge-mappings`, `ovn-cms-options`, `is-interconn`, `ct-no-masked-label`, `datapath-type`, `iface-types`), `nb_cfg` (para *barriers*: quando todo chassis reporta `nb_cfg >= N`, a configuração N convergiu).
- **`Encap`** — `type` (`geneve`/`vxlan`/`stt`), `ip`, `options:csum`, `chassis_name`.
- **`Datapath_Binding`** — um por switch/router lógico. **`tunnel_key`** = o VNI de 24 bits.
- **`Port_Binding`** — a peça central. `logical_port` (nome), `datapath`, **`tunnel_key`**, `chassis` (**o *claim*** — quem tem a porta agora), `type`, `mac`, `nat_addresses`, `up`, `requested_chassis`, `additional_chassis` (para *live migration*), `encap`.
- **`Logical_Flow`** — o produto do `ovn-northd`. Colunas: `logical_datapath` (ou `logical_dp_group`), `pipeline` (`ingress`/`egress`), `table_id`, `priority`, `match`, `actions`, `external_ids:stage-name`, `controller_meter`.
- **`MAC_Binding`** — cache ARP/ND **distribuído**, populado por `ovn-controller` via `put_arp`. Colunas: `logical_port`, `ip`, `mac`, `datapath`, `timestamp`.
- **`Multicast_Group`** — `tunnel_key` ≥ 32768, `ports`.
- **`Port_Group`**, **`Address_Set`**, **`DHCP_Options`**, **`DNS`**, **`Service_Monitor`**, **`Meter`**, **`Controller_Event`**, **`IGMP_Group`**, **`BFD`**, **`FDB`**, **`Static_MAC_Binding`**, **`Load_Balancer`**, **`Advertised_Route`** / **`Learned_Route`** (roteamento dinâmico).
- **`Connection`**, **`SSL`**, **`RBAC_Role`**, **`RBAC_Permission`** — segurança de acesso ao SB.

Comando que vale memorizar:

```
ovn-sbctl list Port_Binding <lsp>       # o claim, o tunnel_key, o tipo
ovn-sbctl lflow-list <datapath>         # os logical flows daquele datapath
ovn-sbctl dump-flows                    # tudo (em labs pequenos)
```

## III.3 — `ovn-northd`: o compilador

Um processo por AZ (ativo/standby via *lock* OVSDB `ovn_northd`). Ele:

1. Monitora **todo** o NB e **todo** o SB.
2. Constrói um modelo em memória da topologia lógica.
3. Emite **`Logical_Flow`s**: regras `(datapath, pipeline, table, priority, match, actions)`.
4. Mantém `Datapath_Binding`, `Port_Binding` (a parte "lógica" deles), `Multicast_Group`, `Address_Set`/`Port_Group` sincronizados do NB para o SB.
5. Sincroniza de volta ao NB o que o SB aprendeu (`en-sync-from-sb.c`): `Logical_Switch_Port.up`, endereços dinâmicos, rotas aprendidas.

### Processamento incremental (I-P)

O `northd` moderno **não recompila tudo** a cada mudança. Ele usa um **motor de processamento incremental** (`lib/inc-proc-eng.c`, `northd/inc-proc-northd.c`): um DAG de "nós" (`en-*.c` — `en-northd`, `en-lflow`, `en-lb-data`, `en-lr-nat`, `en-lr-stateful`, `en-ls-stateful`, `en-port-group`, `en-multicast`, `en-sync-sb`, `en-global-config`, `en-learned-route-sync`, `en-advertised-route-sync`…). Cada nó tem um *handler* de mudança; se o handler sabe tratar a mudança incrementalmente, só o subgrafo afetado recomputa. Se não sabe, cai no *full recompute*.

Consequência operacional: **`ovn-appctl -t ovn-northd stopwatch/show`** mostra onde o tempo está indo, e o log `northd` avisa quando caiu para recompute completo. Em escala, "por que meu northd está a 100% de CPU" quase sempre é "alguma mudança está derrubando o I-P".

Outra otimização importante: **`use_logical_dp_groups`** — quando muitos datapaths compartilham exatamente os mesmos logical flows, o `northd` emite **um** flow associado a um *datapath group* em vez de N cópias. Reduz drasticamente o tamanho do SB.

### A linguagem dos logical flows

Um logical flow é texto, não binário. O `match` usa uma linguagem tipo-C sobre campos simbólicos (`lib/expr.c`, `lib/lex.c`, `lib/logical-fields.c`):

```
inport == "lsp1" && eth.dst == 00:00:00:00:00:01 && ip4.dst == 10.0.0.0/24
ct.new && !ct.est && tcp.dst == {80, 443}
outport == @pg_web && ip
```

As `actions` (`lib/actions.c`) são uma pequena linguagem imperativa:

| Ação | Semântica |
| --- | --- |
| `next;` / `next(table)` | vai para o próximo estágio (vira `resubmit`) |
| `output;` | entrega à porta em `outport` (vira `resubmit` para a tabela de saída) |
| `drop;` | descarta |
| `field = valor;` / `field <-> field` | set/swap (`set_field`, `move`) |
| `ct_next;`, `ct_commit { ... }`, `ct_dnat`, `ct_snat`, `ct_lb_mark(...)`, `ct_mark_snat` | conntrack |
| `arp { ... }`, `nd_na { ... }`, `nd_ns`, `icmp4`, `icmp6`, `igmp`, `tcp_reset` | **gera um pacote novo** com o corpo dado |
| `get_arp(P,A)` / `get_nd` | consulta o cache `MAC_Binding` |
| `put_arp(P,A,E)` / `put_nd` | envia ao `ovn-controller` para popular `MAC_Binding` |
| `R = lookup_arp(P,A,M)` | consulta sem modificar |
| `put_dhcp_opts`, `put_dhcpv6_opts`, `put_nd_ra_opts`, `dns_lookup`, `dhcp_relay_*` | serviços nativos (todos via `pinctrl`) |
| `set_queue`, `set_meter`, `log(...)`, `sample(...)` | QoS, policing, logging de ACL, amostragem |
| `bind_vport`, `handle_svc_check`, `chk_lb_hairpin`, `commit_ecmp_nh`, `select(...)` | mecanismos internos |
| `clone { ... }` | duplica o pacote e executa o bloco na cópia |
| `reg = select(...)` | seleção de bucket (ECMP/LB) via grupo OpenFlow |

## III.4 — Os pipelines lógicos (as "flow tables" do OVN)

Aqui está a resposta concreta a *"como funcionam as flowtables"*. O `northd` organiza os logical flows em **estágios nomeados**. Abaixo estão os estágios do OVN vendorizado no repositório (**26.03**), extraídos de `northd/northd.h`. No **24.03** a lista é mais curta (não há `MIRROR`, `ACL_SAMPLE`, `PRE_NF`/`NF`, `NETWORK_ID`, `DHCP_RELAY_*`), mas a espinha dorsal é idêntica.

### Logical Switch — ingress (`ls_in_*`)

| # | Estágio | O que faz |
| --- | --- | --- |
| 0 | `ls_in_check_port_sec` | verifica port security de entrada |
| 1 | `ls_in_apply_port_sec` | aplica/descarta |
| 2 | `ls_in_mirror` | espelhamento de porta |
| 3–4 | `ls_in_lookup_fdb`, `ls_in_put_fdb` | MAC learning para portas `unknown` |
| 5 | `ls_in_pre_acl` | decide se o pacote entra no conntrack |
| 6 | `ls_in_pre_lb` | decide se entra no conntrack por causa de LB (**aqui está o `inport == <router port> → next` do gotcha #12**) |
| 7 | `ls_in_pre_stateful` | `ct_next` |
| 8–11 | `ls_in_acl_hint`, `ls_in_acl_eval`, `ls_in_acl_sample`, `ls_in_acl_action` | as ACLs, antes do LB |
| 12 | `ls_in_qos` | marcação/limitação |
| 13 | `ls_in_ct_extract` | extrai tuplas originais do conntrack |
| 14–16 | `ls_in_lb_aff_check`, `ls_in_lb`, `ls_in_lb_aff_learn` | **load balancer** (DNAT) e afinidade de sessão |
| 17–19 | `ls_in_pre_hairpin`, `ls_in_nat_hairpin`, `ls_in_hairpin` | *hairpin*: backend acessando o próprio VIP |
| 20–22 | `ls_in_acl_after_lb_*` | ACLs depois do LB (porque só agora o destino real é conhecido) |
| 23–25 | `ls_in_pre_nf`, `ls_in_stateful`, `ls_in_network_function` | commit no conntrack, *service chaining* |
| 26 | `ls_in_arp_rsp` | **ARP/ND responder**: responde localmente por IPs conhecidos, sem broadcast |
| 27–28 | `ls_in_dhcp_options`, `ls_in_dhcp_response` | **servidor DHCPv4 nativo** |
| 29–30 | `ls_in_dns_lookup`, `ls_in_dns_response` | **servidor DNS nativo** |
| 31 | `ls_in_external_port` | portas `external` (HA de serviços para VLAN) |
| 32 | `ls_in_l2_lkup` | **a comutação L2 propriamente dita**: `eth.dst` → `outport` |
| 33 | `ls_in_l2_unknown` | destino desconhecido (flood ou drop) — **onde o pacote morre quando o VIP não tem ARP responder** |

### Logical Switch — egress (`ls_out_*`)

`ls_out_lookup_fdb` (0), `ls_out_put_fdb` (1), `ls_out_pre_acl` (2), `ls_out_pre_lb` (3), `ls_out_pre_stateful` (4), `ls_out_acl_hint` (5), `ls_out_acl_eval` (6), `ls_out_acl_sample` (7), `ls_out_acl_action` (8), `ls_out_mirror` (9), `ls_out_qos` (10), `ls_out_pre_nf` (11), `ls_out_stateful` (12), `ls_out_network_function` (13), `ls_out_check_port_sec` (14), `ls_out_apply_port_sec` (15).

### Logical Router — ingress (`lr_in_*`)

| # | Estágio | O que faz |
| --- | --- | --- |
| 0 | `lr_in_admission` | o pacote é para este roteador? (MAC de destino) |
| 1–2 | `lr_in_lookup_neighbor`, `lr_in_learn_neighbor` | consulta/aprende `MAC_Binding` |
| 3 | `lr_in_ip_input` | ICMP echo, TTL, opções IP, tráfego destinado ao próprio roteador |
| 4 | `lr_in_dhcp_relay_req` | DHCP relay |
| 5–6 | `lr_in_unsnat`, `lr_in_post_unsnat` | desfaz SNAT do tráfego de retorno |
| 7 | `lr_in_defrag` | desfragmentação (para LB em L4) |
| 8–11 | `lr_in_ct_extract`, `lr_in_lb_aff_check`, **`lr_in_dnat`**, `lr_in_lb_aff_learn` | **DNAT e load balancer no roteador** |
| 12 | `lr_in_ecmp_stateful` | respostas simétricas em ECMP |
| 13–14 | `lr_in_nd_ra_options`, `lr_in_nd_ra_response` | **Router Advertisement IPv6 nativo** |
| 15–17 | `lr_in_ip_routing_pre`, **`lr_in_ip_routing`**, `lr_in_ip_routing_ecmp` | **a decisão de roteamento** (LPM sobre rotas conectadas, estáticas e aprendidas) |
| 18–19 | `lr_in_policy`, `lr_in_policy_ecmp` | **logical router policies** — policy-based routing; é aqui que vive o "Edge Firewall" do lab avançado |
| 20–21 | `lr_in_dhcp_relay_resp_chk`, `lr_in_dhcp_relay_resp` | resposta do relay |
| 22 | **`lr_in_arp_resolve`** | resolve o MAC do next-hop (via `MAC_Binding` ou tabela estática) |
| 23–24 | `lr_in_chk_pkt_len`, `lr_in_larger_pkts` | **checagem de MTU** e geração de ICMP *Fragmentation Needed* / *Packet Too Big* |
| 25 | **`lr_in_gw_redirect`** | redireciona para o **chassis de gateway** (o coração do roteamento distribuído com saída centralizada) |
| 26 | `lr_in_network_id` | identificador de rede (EVPN) |
| 27 | `lr_in_arp_request` | emite o ARP request quando não há binding |
| 28 | `lr_in_ecmp_stateful_egr` | — |

### Logical Router — egress (`lr_out_*`)

`lr_out_check_dnat_local` (0), `lr_out_undnat` (1), `lr_out_post_undnat` (2), **`lr_out_snat`** (3), `lr_out_post_snat` (4), `lr_out_egr_loop` (5), `lr_out_delivery` (6).

### Como ler isso na prática

```
ovn-sbctl lflow-list | grep ls_in_l2_lkup      # a tabela de comutação
ovn-nbctl lr-route-list lr-client-az1          # as rotas que alimentam lr_in_ip_routing
ovn-nbctl lr-policy-list lr-edge-az1           # as policies de lr_in_policy
```

E a ferramenta decisiva:

```
ovn-trace --db=tcp:127.0.0.1:6642 ls-client-az1 \
  'inport=="lsp-app-vm-1" && eth.src==...&& ip4.src==10.10.1.10 && ip4.dst==10.10.2.20 && ip.ttl==64'
```

`ovn-trace` simula o pacote **no nível lógico**, estágio a estágio, atravessando switches e roteadores — inclusive por *patch ports*. É o `ofproto/trace` do mundo lógico. Quando os dois discordam, o problema está na tradução do `ovn-controller`.

## III.5 — `ovn-controller`: o tradutor local

Um processo por chassis. Ele é o único componente do OVN que fala com o sistema operacional local de forma significativa. Tem três conexões permanentes:

1. **OVSDB local** (`unix:/var/run/openvswitch/db.sock`) — lê `Open_vSwitch`, `Bridge`, `Port`, `Interface`; escreve `Interface.external_ids:ovn-installed`, zonas de conntrack, portas de túnel.
2. **OVSDB Southbound** (`tcp:`/`ssl:`/`unix:` conforme `external_ids:ovn-remote`) — leitura massiva, escrita de `Chassis`, `Encap`, `Port_Binding.chassis`, `MAC_Binding`.
3. **OpenFlow com o `ovs-vswitchd`** (socket unix `/var/run/openvswitch/br-int.mgmt`, OpenFlow 1.5 com extensões) — instala e remove fluxos, recebe *packet-in*, envia *packet-out*.

### Configuração: tudo vem do `Open_vSwitch.external_ids`

O `ovn-controller` **não tem arquivo de configuração**. Ele lê tudo do OVSDB local. As chaves (de `controller/ovn-controller.8.xml`):

| Chave | Papel |
| --- | --- |
| `system-id` | **o nome do chassis** |
| `ovn-remote` | onde está o SB (`tcp:...:6642`, `unix:/run/ovn/ovnsb_db.sock`, lista para cluster) |
| `ovn-encap-type` | `geneve`, `vxlan`, `stt` (lista) |
| `ovn-encap-ip` | **o IP do underlay usado como origem do túnel** |
| `ovn-encap-ip-default`, `ovn-encap-ip-otherhv` | múltiplos IPs de encap |
| `ovn-encap-csum`, `ovn-encap-df`, `ovn-encap-tos` | checksum, bit DF, ToS do túnel |
| `ovn-bridge` | a bridge de integração (padrão `br-int`) |
| `ovn-bridge-mappings` | `<nome-da-rede-física>:<bridge>` — como portas `localnet` chegam ao mundo (ex.: `physnet1:br-ex`) |
| `ovn-chassis-mac-mappings` | MACs por rede física (para VLAN + roteador distribuído) |
| **`ovn-is-interconn`** | marca o chassis como elegível para **OVN-IC** |
| `ovn-cms-options` | opções opacas para o CMS (`enable-chassis-as-gw`, `availability-zones=...`) |
| `ovn-monitor-all` | monitora **todo** o SB em vez de usar monitor condicional |
| `ovn-remote-probe-interval` | keepalive do OVSDB SB (ms) |
| `ovn-openflow-probe-interval` / `ovn-bridge-remote-probe-interval` | keepalive OpenFlow |
| `ovn-enable-lflow-cache`, `ovn-limit-lflow-cache`, `ovn-memlimit-lflow-cache-kb`, `ovn-trim-*` | o cache de tradução de logical flows |
| `ovn-transport-zones` | particiona quais chassis formam túneis entre si |
| `ovn-match-northd-version` | recusa operar se a versão do northd não bate |
| `ovn-cleanup-on-exit` | remove os fluxos ao sair |
| `ct-zone-<porta>`, `ct-zone-range` | zonas de conntrack alocadas (escrito de volta pelo controller) |
| `ovn-evpn-*`, `dynamic-routing-port-mapping` | roteamento dinâmico / EVPN |

O mínimo para um chassis funcionar (é o que o role `ovn_chassis` do lab faz):

```
ovs-vsctl set open_vswitch . \
  external_ids:system-id=az1-chassis \
  external_ids:ovn-remote=tcp:127.0.0.1:6642 \
  external_ids:ovn-encap-type=geneve \
  external_ids:ovn-encap-ip=172.18.3.175 \
  external_ids:ovn-is-interconn=true
```

### Como uma porta é "reivindicada" (o mecanismo central)

1. Alguém (Incus, Ansible, você) roda `ovs-vsctl add-port br-int veth0 -- set interface veth0 external_ids:iface-id=lsp-app-vm-1`.
2. O `ovn-controller` vê a mudança no OVSDB **local** (`controller/binding.c`).
3. Procura no SB um `Port_Binding` com `logical_port == "lsp-app-vm-1"`.
4. Se achar, escreve `Port_Binding.chassis = <este chassis>` — **o claim**.
5. Passa a considerar o `Datapath_Binding` daquela porta como *local*, e por isso passa a traduzir os logical flows daquele datapath (e dos datapaths alcançáveis a partir dele).
6. Instala os fluxos físicos (tabela 0 e 65) que mapeiam `ofport` ↔ porta lógica.
7. Marca `Interface.external_ids:ovn-installed` e `Port_Binding.up=true`; o `northd` propaga isso para `Logical_Switch_Port.up` no NB — que é o que o Incus espera para considerar a NIC pronta.

**`external_ids:iface-id` é a única cola entre o mundo físico e o mundo lógico.** Errar esse nome é a causa nº 1 de "a instância sobe mas não tem rede".

### Monitor condicional

Um chassis não precisa saber sobre datapaths onde não tem portas. O `ovn-controller` usa `monitor_cond` para pedir ao SB apenas os `Logical_Flow` / `Port_Binding` / `Multicast_Group` dos datapaths relevantes (`controller/ovn-controller.c`, `local_data.c`). Isso é o que torna clusters de milhares de nós viáveis. `ovn-monitor-all=true` desliga essa otimização — útil em labs pequenos (reduz churn de condições), ruim em escala.

### A tradução: `lflow.c` → `ofctrl.c`

- **`controller/lflow.c`** pega cada `Logical_Flow`, faz o *parse* do `match` (em `struct expr`) e das `actions` (em `struct ofpact`), expande `Address_Set`/`Port_Group` (gerando `conjunction` quando vale a pena), resolve nomes de porta para `tunnel_key`, e produz fluxos OpenFlow "desejados".
- **`controller/lflow-cache.c`** guarda os resultados intermediários dessa tradução (as expressões já compiladas), porque traduzir é caro e a maioria das mudanças não invalida tudo.
- **`controller/ofctrl.c`** mantém o **estado desejado** e reconcilia com o **estado real** do `ovs-vswitchd`: ele lê todos os fluxos existentes, calcula o delta e envia `OFPT_FLOW_MOD` em lote, usando **barreiras** para saber quando aplicou. Cookie de cada fluxo = os primeiros 32 bits do UUID do `Logical_Flow` que o gerou — é assim que `ovn-detrace` faz o caminho de volta de um fluxo OpenFlow para o logical flow.
- **`controller/physical.c`** gera os fluxos que **não** vêm de logical flows: a tradução físico↔lógico (tabelas 0 e 65), os fluxos de saída (tunelar vs entregar local), e os fluxos de túnel.

O `ovn-controller` também tem seu próprio **motor I-P** (`lib/inc-proc-eng.c` compartilhado com o northd), com nós para binding, physical flows, lflow output, etc.

### Os registradores: o contrato de metadados

De `include/ovn/logical-fields.h` (válido para o 26.03; estável há anos):

| Campo OVS | Uso no OVN |
| --- | --- |
| `metadata` (64 bits) | **datapath lógico** (`MFF_LOG_DATAPATH`) |
| `reg10` | **flags lógicas** (`MLF_ALLOW_LOOPBACK`, `MLF_RCV_FROM_RAMP`, `MLF_FORCE_SNAT_FOR_DNAT`, `MLF_FORCE_SNAT_FOR_LB`, `MLF_LOCAL_ONLY`, `MLF_NESTED_CONTAINER`, `MLF_LOOKUP_MAC`, `MLF_LOOKUP_LB_HAIRPIN`, `MLF_LOOKUP_FDB`, `MLF_SKIP_SNAT_FOR_LB`, `MLF_LOCALPORT`, `MLF_USE_SNAT_ZONE`, `MLF_CHECK_PORT_SEC`…) |
| `reg11` | zona de conntrack **DNAT** do roteador |
| `reg12` | zona de conntrack **SNAT** do roteador |
| `reg13` | bits 0–15: zona de conntrack da **porta lógica**; bits 16–31: **Encap ID** |
| **`reg14`** | **porta lógica de ingresso** (`inport`) |
| **`reg15`** | **porta lógica de egresso** (`outport`) |
| `reg0`–`reg9` | registradores de propósito geral disponíveis para os logical flows |
| `reg4` / `xxreg1` | IP destino original do LB (v4/v6), afinidade |
| `reg2` | porta destino original do LB |
| `reg5` (bits 16–31) | ofport do túnel |
| `reg1` | porta de saída remota |

Saber isso transforma um `dump-flows` de ruído em texto legível: `reg14` e `reg15` são as portas, `metadata` é a rede.

### As tabelas OpenFlow físicas no `br-int`

O `ovn-controller` usa um mapa fixo de tabelas (de `controller/lflow.h`, versão 26.03 — **no 24.03 os números da faixa intermediária são menores**, mas os papéis são os mesmos):

| Tabela | Papel |
| --- | --- |
| **0** | `OFTABLE_PHY_TO_LOG` — **físico → lógico**. Casa no `in_port` (e VLAN, se container aninhado); seta `metadata` (datapath) e `reg14` (inport); `resubmit` para 8. Também trata pacotes vindos de túneis: extrai os metadados do tunnel key e `resubmit` direto para a tabela de egresso |
| **8 …** | `OFTABLE_LOG_INGRESS_PIPELINE` — o **pipeline lógico de ingresso**. Logical table N ⇒ OpenFlow table 8+N |
| **42** | `OFTABLE_OUTPUT_LARGE_PKT_DETECT` — ponto de entrada do `output`; detecta pacotes maiores que o MTU |
| **43** | `OFTABLE_OUTPUT_LARGE_PKT_PROCESS` — gera ICMP *Frag Needed* / *Too Big* de volta à origem |
| **44** | `OFTABLE_REMOTE_OUTPUT` — **destinos remotos**: seta o tunnel key e envia pela porta de túnel correta |
| **45** | `OFTABLE_REMOTE_VTEP_OUTPUT` — gateways VTEP |
| **46** | `OFTABLE_LOCAL_OUTPUT` — **destinos locais** (inclui patch ports e expansão de multicast) |
| **47** | `OFTABLE_CHECK_LOOPBACK` — descarta pacotes cujo inport == outport sem `MLF_ALLOW_LOOPBACK` |
| **48 …** | `OFTABLE_LOG_EGRESS_PIPELINE` — o **pipeline lógico de egresso** |
| **64** | `OFTABLE_SAVE_INPORT` — contorna a prevenção de loopback do OpenFlow |
| **65** | `OFTABLE_LOG_TO_PHY` — **lógico → físico**: casa em `reg15` e faz `output` na porta OVS real |
| **66 / 67** | `OFTABLE_MAC_BINDING` / `OFTABLE_MAC_LOOKUP` — alvo de `get_arp()` / `lookup_arp()`, populadas a partir da tabela `MAC_Binding` do SB |
| **68–70** | hairpin de load balancer (`CHK_LB_HAIRPIN`, `CHK_LB_HAIRPIN_REPLY`, `CT_SNAT_HAIRPIN`) |
| **71 / 72** | `GET_FDB` / `LOOKUP_FDB` — MAC learning |
| **73–75** | checagens de port security (in, ND, out) |
| **76 / 77** | ECMP next-hop |
| **78** | afinidade de LB |
| **79–88** | cache de MAC, lookup de zona CT, carga de tuplas originais do CT, flood para chassis remotos, FDB remoto |

> **Regra de ouro de depuração**: tabela **0** = "o pacote entrou e foi reconhecido?"; tabelas **8+** = "a lógica decidiu o quê?"; tabela **44** = "foi tunelado?"; tabela **46** = "foi entregue local?"; tabela **65** = "saiu pela interface certa?".

### `pinctrl`: quando o OVN *é* o caminho do pacote

`controller/pinctrl.c` (quase 9 mil linhas) é uma thread do `ovn-controller` que trata **packet-ins** vindos da ação `controller(...)` e injeta pacotes com **packet-out**. É a implementação dos "serviços nativos":

- **ARP/ND responder** (`arp`, `nd_na`) e **GARP/RARP** para IPs de NAT e portas de roteador (`garp_rarp.c`);
- **`put_arp` / `put_nd`** — popular a tabela `MAC_Binding` do SB;
- **servidor DHCPv4 e DHCPv6** (`put_dhcp_opts`, `put_dhcpv6_opts`) e **DHCP relay**;
- **Router Advertisement IPv6** (`put_nd_ra_opts`);
- **servidor DNS** (`dns_lookup`, em `ovn-dns.c`) a partir da tabela `DNS` do SB;
- **IGMP/MLD snooping** e criação de `IGMP_Group`;
- **ICMP de erro** (`icmp4`/`icmp6`: TTL excedido, fragmentação necessária) e **TCP reset** (`tcp_reset`, para ACL `reject`);
- **health checks de load balancer** (`Service_Monitor`) e o evento `empty_lb_backends` (`Controller_Event`);
- **BFD** (`bfd.c`) para monitorar túneis e next-hops.

Isso responde a uma pergunta frequente: *"quem responde o ARP da minha instância?"* Em OVN, normalmente **ninguém no caminho do pacote** — o `ls_in_arp_rsp` tem um fluxo que já responde. Quando o OVN precisa gerar algo que não é derivável (um DHCP OFFER, por exemplo), o pacote sobe ao `ovn-controller` via packet-in, ele monta a resposta, e a devolve via packet-out. Há *rate limiting* via **meters** (`copp`) para que isso não vire vetor de DoS.

## III.6 — Conceitos de topologia que sempre confundem

### Patch ports e roteadores lógicos

Um `Logical_Router` **não existe fisicamente em lugar nenhum**. Ele é um datapath lógico, e a conexão switch↔roteador é um **logical patch port** — um par de portas (`type=router` no switch, `Logical_Router_Port` no roteador) que o `ovn-controller` implementa como um simples `resubmit` mudando `metadata`. Roteamento em OVN é **distribuído por construção**: cada hipervisor roteia localmente.

### Distributed gateway port (DGP) e `chassis_redirect`

Quando um roteador precisa de uma saída centralizada (NAT stateful, conexão a uma rede física por `localnet`), define-se `gateway_chassis` ou `ha_chassis_group` num `Logical_Router_Port`. Isso cria uma **distributed gateway port** e, no SB, uma porta `chassisredirect` (`cr-<nome>`). O estágio `lr_in_gw_redirect` reescreve `outport` para a porta `cr-*` quando o tráfego precisa passar pelo chassis eleito, e a tabela 44 o tuneliza para lá.

Regras que caem de lá e que explicam gotchas do lab:
- **um roteador só pode ter uma DGP para efeitos de load balancer** (gotcha #10);
- tráfego **east-west** continua distribuído; só o que precisa de NAT/uplink é redirecionado;
- `options:redirect-type=bridged` e `reside-on-redirect-chassis` mudam esse comportamento.

### Gateway router (roteador centralizado)

Diferente da DGP: colocar `options:chassis=<chassis>` num `Logical_Router` fixa o roteador **inteiro** num chassis. É o que o lab avançado faz com `lr-edge-*`, e é por isso que o LB funciona nele em todas as portas (gotcha #12).

### `localnet`, `localport`, `external`, `virtual`, `remote`

| Tipo de LSP | Semântica |
| --- | --- |
| `localnet` | ponte para uma rede física nomeada; cada chassis mapeia esse nome para uma bridge via `ovn-bridge-mappings`. **Existe em todo chassis** |
| `localport` | existe em todo chassis mas **nunca tunela** — usado para serviços de metadata (o OpenStack coloca o agente de metadados aqui) |
| `external` | porta cujo tráfego de serviço (DHCP, ARP) é tratado por um chassis eleito via HA chassis group — para VLANs com hardware externo |
| `virtual` | endereço IP flutuante entre portas (VRRP/keepalived): o OVN aprende dinamicamente qual porta o detém |
| `remote` | porta em outro AZ, vista via interconnect |
| `vtep` | gateway de hardware VTEP |

### NAT

O OVN implementa NAT nos estágios `lr_in_unsnat`, `lr_in_dnat`, `lr_out_undnat`, `lr_out_snat`, usando o conntrack do kernel com zonas separadas (reg11/reg12). Tipos:

- **`snat`**: `logical_ip` (rede interna) → `external_ip`. É o que o Incus cria para `ipv4.nat=true`.
- **`dnat`**: `external_ip` → `logical_ip`, só entrada.
- **`dnat_and_snat`**: IP flutuante 1:1 nos dois sentidos. Com `external_mac` + `logical_port`, o OVN faz o NAT **distribuído** no hipervisor da instância em vez de no gateway — é o "FIP distribuído".
- `options:stateless=true`: reescrita pura de cabeçalho, sem conntrack.

### Load balancer

Um `Load_Balancer` do NB é anexado a `Logical_Switch`es e/ou `Logical_Router`es. O `northd` gera fluxos com **`ct_lb_mark(backends=...)`**, que faz o conntrack escolher um backend e gravar a decisão — o tráfego subsequente da conexão segue o mesmo backend automaticamente. Detalhes que os gotchas #10–#15 do lab documentam melhor que a documentação oficial:

- anexado ao **switch**: o OVN instala também o **ARP responder** para o VIP (necessário se o VIP está na sub-rede do tenant);
- anexado ao **roteador**: funciona para tráfego que entra pelo roteador, mas num roteador distribuído só na DGP;
- `ls_in_pre_lb` tem `ip && inport == <router port> → next`, ou seja, **o LB de switch é pulado para quem entra por porta de roteador**;
- consequência: **um VIP não é alcançável de outro AZ** através do transit switch — é preciso um VIP local cujo backend seja o workload remoto.

### ACLs, Port Groups e Address Sets

ACLs são avaliadas nos estágios `*_acl_eval` / `*_acl_action`, com prioridade de 0 a 32767 e as ações `allow` (stateless para esse pacote), `allow-related` (commit no conntrack, tráfego relacionado liberado), `allow-stateless`, `drop`, `reject` (gera TCP RST / ICMP unreachable via pinctrl), `pass` (delega ao próximo *tier*).

Escala: um match como `ip4.src == $as_web && tcp.dst == {80,443,8080}` viraria um produto cartesiano. O `northd` emite **`conjunction`**, com um `conj_id` por combinação. Por isso ACLs com conjuntos grandes são baratas em número de fluxos OpenFlow, mas caras em *megaflows* se examinarem muitos campos.

`Port_Group` também gera automaticamente um `Address_Set` com os IPs de suas portas (`$<pg>_ip4`, `$<pg>_ip6`) — é o que o Incus usa para implementar suas *network ACLs*.

## III.7 — OVN Interconnect por dentro

O OVN-IC federa **AZs** — instalações OVN independentes, cada uma com seu `ovn-northd`, NB, SB e chassis. A federação é **aditiva**: se IC-NB/IC-SB caem, o tráfego intra-AZ continua funcionando; o que se perde são *mudanças de topologia*.

### Os dois bancos globais

**IC-NB** (`ovn-ic-nb.ovsschema`) — a intenção da federação:

| Tabela | Conteúdo |
| --- | --- |
| `IC_NB_Global` | opções globais |
| **`Transit_Switch`** | os switches de trânsito compartilhados; `name`, `other_config` (`subnet`, `ipv6_prefix`), `external_ids` |
| `SSL`, `Connection` | acesso |

**IC-SB** (`ovn-ic-sb.ovsschema`) — o estado em tempo de execução:

| Tabela | Conteúdo |
| --- | --- |
| `IC_SB_Global` | — |
| **`Availability_Zone`** | um registro por AZ, criado a partir de `NB_Global.name` |
| **`Gateway`** | os chassis de gateway registrados por cada AZ (`name`, `availability_zone`, `encaps`, `hostname`) |
| **`Encap`** | endereços de túnel dos gateways |
| **`Port_Binding`** | as portas do transit switch e onde estão ancoradas |
| **`Route`** | rotas anunciadas/aprendidas entre AZs (`prefix`, `nexthop`, `origin`, `transit_switch`, `availability_zone`, `route_table`) |
| `Datapath_Binding` | o datapath do transit switch, com tunnel key global |

### O daemon `ovn-ic`

Um por AZ (`ic/ovn-ic.c`, `ic/en-ic.c`). Conecta-se a **quatro** bancos: NB local, SB local, IC-NB e IC-SB. O que faz, em ciclo:

1. **Registra o AZ**: lê `NB_Global.name`; se vazio, não faz nada (**gotcha #1**). Cria/atualiza `Availability_Zone` no IC-SB.
2. **Registra gateways**: procura no SB local os `Chassis` com `other_config:is-interconn=true` (vindo de `ovs-vsctl set open_vswitch . external_ids:ovn-is-interconn=true`) e cria os `Gateway` correspondentes no IC-SB, com seus `Encap`.
3. **Propaga transit switches**: para cada `Transit_Switch` no IC-NB, cria no **NB local** um `Logical_Switch` de mesmo nome, com `other_config:interconn-ts=<nome>`. **Por isso não se cria o `ts` à mão** (gotcha #2).
4. **Cria as portas de trânsito**: para cada AZ participante, cria no transit switch local uma `Logical_Switch_Port` do tipo `remote` representando a porta do *outro* AZ, com `options:requested-chassis` apontando ao gateway remoto, e sincroniza o `tunnel_key` global via IC-SB `Port_Binding`. É assim que os dois AZs concordam sobre a chave de túnel do datapath compartilhado.
5. **Anuncia rotas**: se `ic-route-adv=true`, lê as rotas do roteador local conectado ao TS e publica em `IC_SB.Route`.
6. **Aprende rotas**: se `ic-route-learn=true`, lê `IC_SB.Route` dos outros AZs e cria `Logical_Router_Static_Route` no NB local, marcadas como aprendidas (aparecem com `(learned)` em `lr-route-list`).

Filtros: `ic-route-adv-default` / `ic-route-learn-default` (para a rota default), `ic-route-blacklist`, e nas versões novas `ic-route-deny-adv` / `ic-route-deny-learn` por roteador/porta.

**Onde essas opções vivem** é a gotcha #4 do repositório: no **OVN 24.03.6, em `NB_Global.options`**, não no `Logical_Router`. Em releases mais novos elas existem também por roteador. Verifique sempre com `strings $(which ovn-ic) | grep ic-route` ou lendo `ic/ovn-ic.c` da sua versão.

### O caminho de dados inter-AZ

```
 workload AZ1 ──► ls-client-az1 ──► lr-client-az1
                                        │ (lrp-ts, com gateway chassis fixado)
                                        ▼
                                   ts-client  (datapath lógico compartilhado, tunnel key global)
                                        │
        chassis gateway AZ1 ═══ Geneve UDP 6081 (underlay) ═══ chassis gateway AZ2
                                        │
                                   ts-client
                                        ▼
                                  lr-client-az2 ──► ls-client-az2 ──► workload AZ2
```

O ponto que quebra silenciosamente: a porta do transit switch precisa estar **ancorada num chassis**. Se `Port_Binding.chassis` estiver vazio para `lsp-ts-*`, nada é encapsulado. Daí o `ovn-nbctl lrp-set-gateway-chassis lrp-az1-ts az1-chassis 1` (**gotcha #5**). Isso corresponde, no modelo da seção III.6, a transformar a porta de trânsito numa *distributed gateway port* com chassis eleito.

### Isolamento por múltiplos transit switches

Dois `Transit_Switch` distintos (`ts-client`, `ts-mgmt`) são **dois datapaths lógicos** com tunnel keys diferentes, trafegando **no mesmo túnel Geneve**. O `ovn-ic` anuncia as rotas de cada plano apenas no TS daquele plano. O isolamento é de *roteamento e tenancy*, não criptográfico — quem consegue injetar em `br-int` ou ler o underlay não é barrado por ele. Para confidencialidade, o caminho é **IPsec** (seção III.8).

### Checklist de diagnóstico OVN-IC

```
ovn-ic-nbctl show                 # os transit switches declarados
ovn-ic-sbctl show                 # AZs, gateways e portas registradas
ovn-nbctl get NB_Global . name    # a identidade do AZ  (gotcha #1)
ovn-nbctl get NB_Global . options # ic-route-adv / ic-route-learn (gotcha #4)
ovn-nbctl ls-list | grep ts       # o TS foi propagado para o NB local? (gotcha #2)
ovn-sbctl list Port_Binding | grep -A5 lsp-ts   # chassis está vazio? (gotcha #5)
ovs-vsctl show | grep -A3 geneve  # o túnel existe?
tail -f /var/log/ovn/ovn-ic.log   # "Route ad: skip network ..." é o sintoma da gotcha #4
```

## III.8 — Escala, alta disponibilidade e segurança

### HA do control plane

- **NB/SB clusterizados**: `ovsdb-server` em modo RAFT, 3 ou 5 membros. `ovsdb-tool create-cluster` / `join-cluster`; `ovs-appctl -t <ctl> cluster/status <DB>` mostra líder, termo e membros. **Maioria estrita para escrever**: 2 membros não têm HA nenhuma (maioria de 2 é 2) — é exatamente o argumento do árbitro de quorum no lab avançado.
- **`ovn-northd` ativo/standby**: vários processos, um pega o lock `ovn_northd` no SB.
- **`ovsdb-relay`**: para clusters muito grandes, servidores de leitura que descarregam os monitores dos `ovn-controller`s do cluster RAFT.
- **Dados vs controle**: com o control plane inteiro parado, os fluxos já instalados continuam encaminhando. O que se perde é convergência.

### HA do data plane

- **`Gateway_Chassis`** com prioridades numa DGP, ou **`HA_Chassis_Group`**.
- **BFD** entre chassis para detectar falha de túnel rapidamente (`controller/bfd.c`, tabela `BFD` no SB).
- **`ovn-controller` em modo *fail-safe***: fluxos permanecem no `br-int` mesmo se o `ovn-controller` morre (a menos que `ovn-cleanup-on-exit=true`).

### Escala — o que realmente dói

| Sintoma | Causa típica | Onde olhar |
| --- | --- | --- |
| `ovn-northd` a 100% de CPU | I-P caindo em recompute completo; muitos LBs/ACLs | `ovn-appctl -t ovn-northd stopwatch/show`, logs `northd` |
| SB gigantesco | logical flows duplicados por datapath | `use_logical_dp_groups=true` |
| `ovn-controller` a 100% de CPU | monitor não condicional, ou cache de lflow desabilitado, ou churn de `MAC_Binding` | `ovn-appctl -t ovn-controller lflow-cache/show-stats`, `ovn-monitor-all` |
| `ovs-vswitchd` a 100% de CPU | *upcalls* demais; megaflows específicas demais | `ovs-appctl upcall/show`, `dpctl/dump-flows -m` |
| Convergência lenta ao ligar | tradução inicial completa de todos os lflows | `ovn-ofctrl-wait-before-clear` |

Ferramentas de medição: `ovn-nbctl --wait=hv sync` (espera todos os chassis reportarem `nb_cfg`), `ovn-sbctl --timeout=… list Chassis_Private` (o `nb_cfg` de cada um), e o par `ovn-heater` + `ovn-fake-multinode` do repositório.

### Segurança

- **RBAC do Southbound**: como cada `ovn-controller` escreve no SB, um chassis comprometido poderia reivindicar portas alheias. O SB tem `RBAC_Role` / `RBAC_Permission` que restringem o que um chassis pode escrever (por exemplo, só `Port_Binding` cuja `chassis` seja a sua). Ativado com conexões `pssl:` e `--ovn-sb-db-...`-role.
- **TLS**: todas as conexões OVSDB podem ser `ssl:`/`pssl:` com CA própria. O Incus tem chaves de configuração para isso (`network.ovn.ca_cert`, `client_cert`, `client_key`).
- **IPsec para os túneis**: `ovn-nbctl set NB_Global . ipsec=true` + o daemon `ovn-ipsec` / `ovs-monitor-ipsec`, que estabelece SAs entre chassis com certificados. É a única forma de confidencialidade no overlay — o Geneve em si é texto claro.
- **Port security** (`Logical_Switch_Port.port_security`) impede *spoofing* de MAC/IP na origem. Sem ela, o isolamento por roteamento é frágil.

## III.9 — O que chegou recentemente (e por que importa para o seu roadmap)

Do `NEWS` do OVN vendorizado (post-26.03):

- **Dynamic routing / BGP / EVPN**: o OVN passou a integrar-se ao *fabric* nativamente. `ovn-controller` aprende e injeta rotas usando **VRFs do kernel** via netlink (`controller/route-exchange-netlink.c`, `neighbor-exchange-netlink.c`), com as tabelas `Advertised_Route` e `Learned_Route` no SB; um daemon de roteamento externo (FRR) fala BGP/EVPN com o fabric. Extensão de EVPN em switches lógicos com aprendizado automático de vizinhos (rotas Type-2 MAC+IP) e redistribuição (`other_config:dynamic-routing-redistribute=ip`).
- **`ic-route-deny-adv` / `ic-route-deny-learn`** em `Logical_Router` / `Logical_Router_Port` — filtros negativos de rota no interconnect (resolve limitações que a gotcha #4 contorna).
- **`requested-encap-ip`** em LSP/LRP — escolher o IP de encapsulamento por porta, pensado justamente para portas de transit switch em modo interconnect.
- **`disable_garp_rarp`** em `Logical_Router`.
- Estatísticas de DNS via *coverage counters* (`ovn-appctl -t ovn-controller coverage/read-counter`).
- **SNI/TLS** (`--ssl-server-name`) em todos os utilitários e daemons.

Para um estudo que quer ser "definitivo": **BGP/EVPN é o concorrente direto do OVN-IC** para federação. OVN-IC federa OVN com OVN; EVPN federa OVN com *qualquer* fabric. Vale saber quando cada um se aplica — o próprio README de OVN-IC do repositório já diz "federação entre OVN e redes **não-OVN**: use BGP/EVPN".

---

# Parte IV — Como o OVN conversa com o sistema operacional

Esta é a resposta direta ao pedido central. Vou separar em **quais interfaces do SO cada processo usa** e depois **o caminho completo de um pacote**.

## IV.1 — O mapa de interfaces

| Processo | Interface com o SO | Detalhe |
| --- | --- | --- |
| `ovsdb-server` (NB/SB/IC) | sockets **unix** (`/run/ovn/*.sock`, `*.ctl`) e **TCP/TLS**; arquivos `.db` em disco (append de transações + compactação) | fala JSON-RPC (OVSDB/RFC 7047). Nada de rede de dados |
| `ovn-northd` | apenas sockets OVSDB (NB e SB) + socket unixctl | **nunca toca a rede do host** |
| `ovn-ic` | sockets OVSDB (NB, SB, IC-NB, IC-SB) + unixctl | idem. Precisa que o diretório do unixctl exista (**gotcha #6**) |
| `ovn-controller` | OVSDB local (unix), OVSDB SB (TCP/unix), **OpenFlow** (unix `br-int.mgmt`), **rtnetlink** (route/neighbor exchange, VRFs), unixctl | é a ponte |
| `ovs-vswitchd` | OVSDB local; **genetlink** (`ovs_datapath`, `ovs_vport`, `ovs_flow`, `ovs_packet`, `ovs_meter`, `ovs_ct_limit`); **rtnetlink** (criar/consultar netdevs); **netfilter netlink** (conntrack); `AF_PACKET`/`AF_XDP`/DPDK conforme o datapath | é quem realmente programa o kernel |
| `openvswitch.ko` | é o kernel | tabela de fluxo, vports, ações, integra com `nf_conntrack`, `nf_nat`, dispositivos de túnel |

Pontos que costumam surpreender:

1. **O `ovn-controller` não cria interfaces de workload.** Quem cria o veth é o Incus/libvirt/você. O `ovn-controller` só reage ao `external_ids:iface-id`.
2. **O `ovn-controller` cria, sim, as portas de túnel.** `controller/encaps.c` adiciona ao `br-int` portas `ovn-<chassis-id-abreviado>-<n>` do tipo `geneve`, com `options:remote_ip` = o `Encap.ip` do chassis remoto e `options:key=flow` (a chave vem do fluxo, não da porta). É por isso que você nunca configura túneis à mão.
3. **O `ovn-controller` cria patch ports** entre `br-int` e as bridges de provider (`controller/patch.c`), conforme `ovn-bridge-mappings`. Um `localnet` em `physnet1` vira um par de patch ports `br-int`↔`br-ex`.
4. **Zonas de conntrack são alocadas pelo `ovn-controller`** e registradas em `Open_vSwitch.external_ids:ct-zone-*` (`controller/ct-zone.c`), para sobreviver a reinícios.
5. **O único lugar onde o OVN escreve estado de rede do host** é o roteamento dinâmico (VRFs e rotas via rtnetlink) e, indiretamente, o conntrack.

## IV.2 — O caminho completo de um pacote, do container ao container remoto

Cenário: `app-vm-1` (10.10.1.10, AZ1) faz `curl` para `app-vm-2` (10.10.2.20, AZ2) — atravessando roteador lógico, transit switch e Geneve. Vou numerar cada transição de camada.

### Origem — dentro do container

1. `connect()` no socket do processo. A pilha TCP/IP do **netns do container** monta o segmento.
2. Roteamento no netns: `10.10.2.20` não é local ⇒ default via `10.10.1.1` (o LRP do `lr-client-az1`).
3. Cache de vizinho (`neigh`): não tem o MAC de `10.10.1.1` ⇒ emite **ARP request** no `eth0` do container.
4. `eth0` é a ponta do **veth pair**; o kernel entrega o quadro na ponta do host (`veth...`).

### `br-int` — a entrada no OVS

5. A ponta host é uma **porta OVS** no `br-int`. O kernel entrega o quadro ao **vport** correspondente do datapath.
6. O datapath extrai a *flow key* e busca na tabela de megaflows. Primeiro pacote ⇒ **miss** ⇒ `OVS_PACKET_CMD_MISS` por genetlink para o `ovs-vswitchd`.
7. Uma *handler thread* chama `ofproto-dpif-xlate`, que **executa o pipeline OpenFlow do `br-int` em software**:
   - **tabela 0**: casa `in_port=<ofport do veth>` ⇒ seta `metadata` = tunnel_key do datapath `ls-client-az1`, `reg14` = tunnel_key da LSP; `resubmit(,8)`.
   - **tabelas 8+**: pipeline de ingresso do switch lógico. Para o **ARP request**, o estágio `ls_in_arp_rsp` (OF 8+26) casa e executa a ação `arp { ... }` — o OVN **responde o ARP localmente**, reescrevendo o pacote e devolvendo pela porta de entrada. Não há broadcast, não há túnel.
8. O `ovs-vswitchd` instala a megaflow correspondente (`OVS_FLOW_CMD_NEW`) e executa o pacote (`OVS_PACKET_CMD_EXECUTE`). Os próximos ARPs iguais são resolvidos **inteiramente no kernel**.

### O pacote IP, agora com MAC de destino do roteador

9. O container envia o pacote TCP com `eth.dst` = MAC do LRP. Mesmo caminho até o datapath; se houver megaflow, *hit*; senão, *upcall* e tradução:
   - **tabela 0** ⇒ metadata/reg14 do `ls-client-az1`.
   - **`ls_in_check/apply_port_sec`**: MAC/IP de origem batem com `port_security`? Se não, drop silencioso (causa clássica de "funciona ping mas não funciona X").
   - **`ls_in_pre_acl` / `acl_eval` / `acl_action`**: ACLs do plano de cliente.
   - **`ls_in_l2_lkup`**: `eth.dst` = MAC do roteador ⇒ `outport = "ls-client-az1-lsp-router"`; `output;`.
   - `output` ⇒ `resubmit` para a tabela de saída (42), que passa por 44 (remoto? não), 46 (local — e o destino é um **patch port lógico**, que existe em todo hipervisor), 47 (loopback), e entrega ao **datapath lógico do roteador**: muda `metadata` para o tunnel_key de `lr-client-az1`, `reg14` para o LRP, e reentra em 8.
10. **Pipeline do roteador** (`lr_in_*`):
    - `lr_in_admission`: o `eth.dst` é meu ⇒ ok.
    - `lr_in_ip_input`: TTL, ICMP para mim, etc.
    - `lr_in_unsnat`: nada a desfazer.
    - `lr_in_dnat`: sem LB aqui.
    - **`lr_in_ip_routing`**: LPM. `10.10.2.0/24` é uma rota **aprendida pelo OVN-IC** ⇒ next-hop = o endereço do LRP do AZ2 no `ts-client` (`169.254.100.2`), `outport` = `lrp-ts-az1`.
    - `lr_in_policy`: as policies do edge não se aplicam aqui.
    - **`lr_in_arp_resolve`**: preciso do MAC de `169.254.100.2`. Se houver `MAC_Binding` no SB, um fluxo na **tabela 66** resolve. Se não houver, `lr_in_arp_request` gera um ARP (via pinctrl) e o pacote original é descartado — o retry do TCP resolve.
    - **`lr_in_chk_pkt_len` / `lr_in_larger_pkts`**: se o pacote for maior que o MTU do LRP de saída e tiver DF, o OVN **gera ICMP Fragmentation Needed** (via pinctrl) de volta. **É exatamente o teste T6 do lab.**
    - **`lr_in_gw_redirect`**: a porta de trânsito tem gateway chassis fixado ⇒ se este chassis não for o eleito, `outport` vira a porta `cr-lrp-ts-az1` e o pacote será tunelado para o chassis de gateway. No lab de 1 chassis por AZ, já estamos nele.
    - `lr_out_snat`: nada (tráfego inter-AZ não é NATeado).
    - `lr_out_delivery`: entrega no `ts-client`.
11. Novo patch lógico ⇒ `metadata` = tunnel_key do **`ts-client`** (uma chave **global**, acordada via IC-SB), `reg14` = a LSP local do TS.
12. **Pipeline do transit switch**: `ls_in_l2_lkup` casa `eth.dst` = MAC do LRP do AZ2 ⇒ `outport` = `lsp-ts-az2`, que é uma porta `remote` cujo `Port_Binding.chassis` é o **gateway do AZ2**.

### O túnel

13. `output` ⇒ tabela 42 → **44 (`REMOTE_OUTPUT`)**: existe um fluxo casando `metadata` (o datapath do TS) + `reg15` (a porta remota). Suas ações:
    - montam o **tunnel key**: `tun_id` = 24 bits do datapath; a opção Geneve TLV `0x0102:0x80` = (ingress 15 bits | egress 16 bits);
    - `output:<ofport da porta ovn-xxxx-0 do tipo geneve>`.
14. O datapath executa a ação de **encapsulamento**: novo cabeçalho Ethernet/IP (src = `ovn-encap-ip` local, dst = `Encap.ip` do chassis remoto) / UDP (dport 6081) / Geneve (VNI + TLV). Sai pela NIC do underlay, roteado pelo kernel normalmente.
15. **Aqui é onde o MTU importa**: 1442 + 58 = 1500. Se o workload tivesse MTU 1500, o pacote encapsulado teria 1558 e seria fragmentado ou descartado no underlay.

### Chegada no chassis remoto

16. A NIC do AZ2 recebe UDP:6081. O kernel entrega ao **vport de túnel** `genev_sys_6081`, que decapsula e coloca os metadados do túnel na flow key (`tun_id`, `tun_metadata0`, `tun_src`…).
17. **Tabela 0** do `br-int` remoto: casa `in_port = <porta de túnel>`; as ações leem o tunnel key e restauram `metadata` (datapath), `reg14` (inport lógico) e **`reg15` (outport lógico — já conhecido!)**; `resubmit` direto para a **tabela de egresso** (48 no 26.03), pulando todo o pipeline de ingresso. **O pipeline de ingresso só roda uma vez, no chassis de origem** — esse é o princípio de design do OVN.
18. Pipeline de egresso do `ts-client` (ACLs de saída, port security) ⇒ `output` ⇒ tabela 65 ⇒ o destino é um **patch lógico** para `lr-client-az2`.
19. Pipeline do `lr-client-az2`: admissão, roteamento para `10.10.2.0/24` (rota **conectada**), `lr_in_arp_resolve` para o MAC de `10.10.2.20`, entrega em `ls-client-az2`.
20. `ls_in_l2_lkup` do `ls-client-az2` ⇒ `outport` = a LSP de `app-vm-2`, que é **local** ⇒ tabela 46 ⇒ pipeline de egresso ⇒ **tabela 65** ⇒ `output:<ofport do veth de app-vm-2>`.
21. O kernel entrega na ponta host do veth; a outra ponta está no netns do container; a pilha TCP do container recebe o segmento.

### O retorno

Simétrico, com uma diferença que vale internalizar: se houvesse **SNAT** ou **load balancer** no caminho, o retorno dependeria do **conntrack** — e o conntrack é **por chassis**. É por isso que NAT stateful exige um chassis de gateway fixo (ou NAT distribuído com `dnat_and_snat` + `external_mac`).

## IV.3 — A caixa de ferramentas de depuração, camada por camada

| Pergunta | Ferramenta |
| --- | --- |
| A intenção está correta? | `ovn-nbctl show`, `ovn-nbctl list <tabela>` |
| O northd compilou? | `ovn-sbctl lflow-list <datapath>`, `ovn-sbctl list Datapath_Binding` |
| A lógica funciona? | **`ovn-trace`** |
| O chassis reivindicou a porta? | `ovn-sbctl list Port_Binding <lsp>` (coluna `chassis`) |
| O controller traduziu? | `ovs-ofctl -O OpenFlow15 dump-flows br-int table=N` |
| Que logical flow gerou este fluxo OpenFlow? | **`ovn-detrace`** (usa o cookie) |
| O pipeline físico funciona? | **`ovs-appctl ofproto/trace br-int <flow>`** |
| O kernel instalou? | `ovs-appctl dpctl/dump-flows -m` |
| Está tunelando? | `ovs-vsctl show`, `tcpdump -ni <underlay> udp port 6081` |
| Está chegando desencapsulado? | `tcpdump -ni genev_sys_6081` |
| Conntrack está atrapalhando? | `conntrack -L -z <zona>`, `ovs-appctl dpctl/dump-conntrack` |
| O controller está saudável? | `ovn-appctl -t ovn-controller lflow-cache/show-stats`, `ovn-appctl -t ovn-controller debug/status` |
| O northd está saudável? | `ovn-appctl -t ovn-northd stopwatch/show` |
| Convergiu em todos os chassis? | `ovn-nbctl --wait=hv sync` |
| Cluster OVSDB saudável? | `ovs-appctl -t /var/run/ovn/ovnsb_db.ctl cluster/status OVN_Southbound` |

Uma sequência que resolve a maioria dos casos, na ordem:

```
ovn-nbctl show                      # 1. a intenção existe?
ovn-sbctl list Port_Binding <lsp>   # 2. alguém reivindicou a porta?
ovn-trace <switch> '<pacote>'       # 3. a lógica leva onde eu espero?
ovs-appctl ofproto/trace br-int ... # 4. o físico concorda com o lógico?
tcpdump -ni genev_sys_6081          # 5. saiu/chegou no túnel?
```

Quando (3) e (4) discordam, o problema é do `ovn-controller` (tradução, binding, ou fluxos ausentes). Quando os dois concordam mas o pacote não chega, o problema é underlay: MTU, firewall bloqueando UDP 6081, ou roteamento entre os hosts.

---

# Parte V — Incus + OVN

Aqui a pergunta é: *"como o Incus usa o OVN, como é chamado, e qual é o fluxo do comando até a instrução executada pelo SO?"*

## V.1 — Arquitetura do Incus, na medida necessária

- **`incus`** (CLI, Go) → fala **REST/HTTP** com o daemon, por socket unix `/var/lib/incus/unix.socket` ou HTTPS (porta 8443) com autenticação por certificado.
- **`incusd`** (daemon, Go) → contém tudo: gerenciamento de instâncias (LXC para containers de sistema, QEMU para VMs), *storage drivers*, *network drivers*, *devices*, e o banco de cluster (**Cowsql/dqlite**, SQLite replicado via RAFT).
- **Network drivers** em `internal/server/network/`: `bridge`, `ovn`, `physical`, `macvlan`, `sriov`. O driver `ovn` é o `driver_ovn.go` — **8.776 linhas**, o maior driver de rede do projeto.
- **Devices** em `internal/server/device/`: `nic_bridged`, `nic_ovn`, `nic_p2p`, `nic_routed`, `nic_sriov`, `nic_physical`, `nic_macvlan`, `nic_ipvlan`.

### Como o Incus fala com o OVN — o ponto decisivo

**O Incus não executa `ovn-nbctl`.** Ele fala **OVSDB nativo**, via a biblioteca Go [`libovsdb`](https://github.com/ovn-kubernetes/libovsdb) (o fork do ovn-kubernetes), com **modelos gerados a partir dos schemas**:

```
internal/server/network/ovn/
├── ovn_nb.go           ← cliente NB      (schema/ovn-nb)
├── ovn_nb_actions.go   ← 4.879 linhas de operações NB
├── ovn_sb.go           ← cliente SB      (schema/ovn-sb)
├── ovn_sb_actions.go / ovn_sb_events.go
├── ovn_icnb.go         ← cliente IC-NB   (schema/ovn-ic-nb)
├── ovn_icnb_actions.go ← transit switches
├── ovn_icsb.go         ← cliente IC-SB   (schema/ovn-ic-sb)
└── ovn_icsb_actions.go ← consulta de gateways
internal/server/network/ovs/
├── ovs.go / ovs_actions.go  ← cliente OVSDB do Open_vSwitch local (schema/ovs)
```

Na construção do cliente NB (`NewNB`), o Incus:

1. monta o modelo do schema e adiciona **índices de cliente** por `name` para `Load_Balancer`, `Logical_Router`, `Logical_Switch`, `Logical_Switch_Port` (o `Get()` padrão do libovsdb só usa índices do schema);
2. aceita **múltiplos endpoints separados por vírgula** — suporte a cluster RAFT;
3. configura **TLS** se a string contiver `ssl:` (usando `network.ovn.client_cert` / `client_key` / `ca_cert`);
4. conecta, faz `Echo()` e **`MonitorAll()`** — ou seja, mantém um **cache local completo do NB**, atualizado por push;
5. registra um *inactivity check* de 20 s e um finalizador que cancela o monitor.

Consequência prática: o `incusd` é um **cliente OVSDB de primeira classe**, como o `ovn-northd` ou o `ovn-controller`. Ele lê do cache local (rápido) e escreve com transações OVSDB atômicas.

### As chaves de configuração do servidor

| Chave | Padrão | Papel |
| --- | --- | --- |
| `network.ovn.northbound_connection` | `unix:/run/ovn/ovnnb_db.sock` | onde está o NB (aceita lista `tcp:a:6641,tcp:b:6641,...`) |
| `network.ovn.integration_bridge` | `br-int` | a bridge OVS onde o Incus pluga os veths |
| `network.ovn.ca_cert` / `client_cert` / `client_key` | — | TLS para o NB |

```
incus config set network.ovn.northbound_connection tcp:10.0.0.1:6641,tcp:10.0.0.2:6641
incus config set network.ovn.integration_bridge br-int
```

**O Incus nunca fala com o SB para operar redes** (só o cliente SB existe para consultas pontuais e eventos). Quem consome o SB é o `ovn-controller` de cada nó — que o Incus **não gerencia**: ele é instalado e configurado fora do Incus (via pacote `ovn-host` e `ovs-vsctl set open_vswitch . external_ids:ovn-remote=... ovn-encap-type=geneve ovn-encap-ip=...`).

## V.2 — O modelo lógico que o Incus cria

Toda rede OVN do Incus tem **`id`** numérico no banco de cluster, e todo nome derivado dele. De `acl.OVNNetworkPrefix()` e `driver_ovn.go`:

```
prefixo = incus-net<ID>
```

| Objeto OVN | Nome gerado | Função |
| --- | --- | --- |
| `Logical_Router` | `incus-net<N>-lr` | o roteador da rede |
| `Logical_Router_Port` (externo) | `incus-net<N>-lr-lrp-ext` | uplink |
| `Logical_Router_Port` (interno) | `incus-net<N>-lr-lrp-int` | gateway das instâncias |
| `Logical_Switch` (externo) | `incus-net<N>-ls-ext` | ponte para a rede de uplink |
| LSP do roteador no ls-ext | `incus-net<N>-ls-ext-lsp-router` | patch para o LR |
| LSP `localnet` no ls-ext | `incus-net<N>-ls-ext-lsp-provider` | ponte para a rede física |
| `Logical_Switch` (interno) | `incus-net<N>-ls-int` | **onde as instâncias vivem** |
| LSP do roteador no ls-int | `incus-net<N>-ls-int-lsp-router` | patch para o LR |
| LSP de instância | `incus-net<N>-instance-<uuid-da-instância>-<nome-do-device>` | **o `iface-id`** |
| `HA_Chassis_Group` | `incus-net<N>` | quais nós podem ser gateway |
| `Load_Balancer` | `incus-net<N>-lb-<endereço>` | forwards e load balancers |
| `Port_Group` | `incus-net<N>` + grupos por ACL | ACLs |
| `Address_Set` | prefixo por rede | sub-redes e rotas para ACLs |
| LRP de peering | `incus-net<N>-lr-lrp-peer-net<M>` | *network peers* |

Topologia resultante (rede não isolada):

```
       rede de uplink (bridge incusbr0 ou physical)
                    │  localnet
        ┌───────────┴────────────┐
        │   incus-net3-ls-ext    │
        └───────────┬────────────┘
                    │ lsp-router  ⇄  lrp-ext  (IP alocado de ipv4.ovn.ranges do uplink)
        ┌───────────┴────────────┐
        │   incus-net3-lr        │   SNAT de 10.x/24 → IP externo   (ipv4.nat=true)
        └───────────┬────────────┘   rotas default, policies, LBs
                    │ lrp-int (o gateway .1 das instâncias)  ⇄  lsp-router
        ┌───────────┴────────────┐
        │   incus-net3-ls-int    │   DHCPv4/DHCPv6, RA, DNS, IPAM, ACLs
        └──┬────────────┬────────┘
           │            │
     instância c1   instância c2      (LSPs incus-net3-instance-<uuid>-eth0)
```

Repare que **o Incus cria exatamente a topologia canônica de VPC do OVN**: um switch interno privado, um roteador com NAT, e um switch externo que conecta a um `localnet`. É o mesmo desenho que o `lab avançado` monta na mão — só que com nomes gerados e o `ovn-ic` de fora.

## V.3 — `incus network create --type=ovn`: o comando, passo a passo

Comando:

```
incus network set incusbr0 ipv4.dhcp.ranges=10.0.0.10-10.0.0.100 ipv4.ovn.ranges=10.0.0.101-10.0.0.200
incus network create ovntest --type=ovn network=incusbr0 ipv4.address=10.10.10.1/24 ipv4.nat=true
```

### Etapa 1 — CLI → API

`incus` monta `POST /1.0/networks` com o JSON da rede e envia pelo socket unix. Autenticação: uid/gid do peer no socket unix, ou certificado TLS.

### Etapa 2 — `incusd`: validação e persistência

`Validate()` do driver `ovn` checa as chaves; `FillConfig()`/`populateAutoConfig()` preenche defaults (inclusive **gerar sub-redes automaticamente** se `ipv4.address=auto`). A rede é gravada no **banco de cluster (dqlite)** e recebe seu `id` — que determina todos os nomes OVN. Em cluster, a criação é um processo em duas fases (cada nó valida localmente, depois um nó executa a criação global).

### Etapa 3 — `setup()`: as transações OVSDB no Northbound

É aqui que o OVN entra. Na ordem exata do código (`driver_ovn.go:2631`):

1. **Valida restrições de projeto** e resolve qual rede de uplink usar.
2. **Calcula o MTU da bridge**: se `bridge.mtu` não foi dado, deriva do underlay (`getOptimalBridgeMTU()` — pega o MTU da interface do underlay e **subtrai o overhead do encapsulamento**; é a automação do que o lab fez à mão com 1442).
3. **Deriva o MAC do roteador** de forma **estável**: seed = `fingerprint do certificado do servidor + ID da rede`. Assim todos os nós do cluster geram o mesmo MAC, e dois Incus distintos na mesma rede física não colidem.
4. **`setupUplinkPort()`**: aloca IPs externos do `ipv4.ovn.ranges`/`ipv6.ovn.ranges` da rede de uplink e prepara o lado do host (para uplink `bridge`: cria um par veth entre a bridge do Incus e uma bridge OVS, ou usa OVS nativamente; para `physical`: usa a interface/VLAN).
5. **`CreateChassisGroup`** — o `HA_Chassis_Group` da rede.
6. **`CreateLogicalRouter`** + `UpdateLogicalRouterMulticastRelay`.
7. **`CreateLogicalSwitch(ls-ext)`**, **`CreateLogicalRouterPort(lrp-ext)`** (com o chassis group ⇒ **distributed gateway port**), **`CreateLogicalSwitchPort(lsp-router)`** + `UpdateLogicalSwitchPortLinkRouter` (o patch), e **`lsp-provider`** + `UpdateLogicalSwitchPortLinkProviderNetwork` (a porta `localnet`).
8. **`CreateLogicalRouterNAT("snat", ...)`** para IPv4 e IPv6 se `ipv4.nat`/`ipv6.nat` estiverem ligados.
9. **`CreateStaticMACBinding`** para o MAC do gateway do uplink, se a rede de uplink o declarar — evita depender de ARP para o próximo salto.
10. **Rotas default** (`CreateLogicalRouterRoute`), incluindo, no modo `l3only`, uma rota de descarte para a sub-rede interna inteira (para que pacotes a IPs desconhecidos não escapem pelo uplink).
11. **`CreateLogicalSwitch(ls-int)`** + `UpdateLogicalSwitchMulticastSnooping`.
12. **`bridge.external_interfaces`**: cria LSPs e pluga interfaces físicas do host diretamente no switch interno (inclusive criando interfaces VLAN quando o formato estendido `nome/pai/vlan` é usado).
13. **`UpdateLogicalSwitchIPAllocation`** — configura o **IPAM do OVN** (`other_config:subnet`, `exclude_ips`) no switch interno. **É o OVN que aloca os IPs dinâmicos**, não o Incus.
14. **`CreateAddressSet` / `UpdateAddressSetAdd`** com as sub-redes internas — usado pelas ACLs.
15. **`logicalRouterPolicySetup()`** — políticas de segurança do roteador (e exclusão de peers).
16. **`CreateLogicalRouterPort(lrp-int)`** — o gateway das instâncias.
17. **`UpdateLogicalSwitchDHCPv4Options` / `DHCPv6Options`** — cria os registros `DHCP_Options` com `server_id`, `server_mac`, `lease_time`, `router`, `dns_server`, `domain_name`, `mtu`, `classless_static_route`. **O servidor DHCP é o próprio OVN (pinctrl), não um dnsmasq.**
18. **`UpdateLogicalRouterPort(RA opts)`** — Router Advertisement IPv6 nativo (`send_periodic`, `address_mode` slaac/dhcpv6_stateful, `mtu`, `rdnss`).
19. **`CreateLogicalSwitchPort(ls-int-lsp-router)`** + link com o `lrp-int`.
20. **ACLs de baseline** no switch interno, **port group da rede**, e os port groups das ACLs listadas em `security.acls`.

### Etapa 4 — daqui para baixo, é OVN puro

O que acontece depois **não é mais Incus**:

```
incusd (libovsdb transact)
   └─► OVN Northbound DB
          └─► ovn-northd  → compila logical flows
                 └─► OVN Southbound DB
                        └─► ovn-controller (em cada nó)
                               └─► OpenFlow → ovs-vswitchd
                                      └─► genetlink → datapath do kernel
```

A latência percebida entre `incus network create` retornar e a rede realmente funcionar é a soma de: transação no NB (ms) + ciclo do northd (ms a s, conforme escala) + monitor do SB + tradução no controller + instalação dos fluxos. Em cluster, o Incus usa `pingOVNRouter()` (dispara pings para o IP do roteador) para forçar a resolução de ARP e verificar que a rede subiu.

## V.4 — `incus start` com NIC OVN: do comando ao veth em `br-int`

Configuração típica:

```
incus config device override c1 eth0 network=ovntest ipv4.address=10.10.10.42
incus start c1
```

### O que o `nicOVN.Start()` faz (`internal/server/device/nic_ovn.go:798`)

1. **`validateEnvironment()` / `PreStartCheck()`** — a rede existe e está `started`?
2. **Cria o par veth** (nome do host persistido em `volatile.<dev>.host_name`, geralmente `veth<random>`), com MAC e MTU pedidos. (Para VMs QEMU, o device apresentado é um `tap`/`virtio-net`; com `acceleration=sriov`/`vdpa`, é uma VF/representor em vez de veth.)
3. **`network.InstanceDevicePortStart()`** — a parte OVN, detalhada abaixo.
4. **`setupHostNIC()`**:
   - `sysctl net/ipv6/conf/<veth>/disable_ipv6=1` (o lado host não deve pegar link-local nem aceitar RAs);
   - `sysctl net/ipv4/conf/<veth>/forwarding=0`;
   - **`vswitch.CreateBridgePort(br-int, <veth>)`** — transação OVSDB no `Open_vSwitch` local criando `Interface` + `Port` na `Bridge` `br-int`;
   - **`vswitch.AssociateInterfaceOVNSwitchPort(<veth>, <nome da LSP>)`** — escreve **`Interface.external_ids["iface-id"] = incus-net<N>-instance-<uuid>-<dev>`**;
   - `ip link set <veth> up`.
5. Devolve a `RunConfig` que o LXC/QEMU usa para mover a outra ponta para o namespace da instância (ou anexar o tap à VM).

**O passo 4 é o momento exato em que o mundo Incus encosta no mundo OVN.** A partir daí o `ovn-controller` local vê a mudança no OVSDB, encontra o `Port_Binding` de mesmo nome no SB, escreve `chassis=<este nó>`, e instala os fluxos das tabelas 0 e 65 mapeando `ofport ↔ tunnel_key`. O `Port_Binding.up` vira `true`, o northd propaga para `Logical_Switch_Port.up`, e o Incus considera a NIC operacional.

### O que o `InstanceDevicePortStart()` faz no NB (`driver_ovn.go:4841`)

Em ordem, e cada item é uma transação OVSDB:

1. Consulta o UUID da LSP persistente (pode faltar após upgrade/restore).
2. Lê as reservas DHCPv4 estáticas do switch interno.
3. **IPs "pegajosos"**: se o IPv4 é dinâmico, procura o IP usado na última execução (em `volatile`) e, se ainda estiver livre, **pede ao OVN para reusá-lo** — é o que faz um container manter o IP entre reinícios.
4. **`CreateLogicalSwitchPort(ls-int, instancePortName, opts, replace=true)`** — cria/atualiza a porta com `addresses` (MAC + IPs, ou `dynamic`), `port_security`, `dhcpv4_options`/`dhcpv6_options`, `enabled`.
5. Lê os **IPs dinâmicos** alocados pelo OVN (`GetLogicalSwitchPortDynamicIPs`), com *retry* — o IPAM é assíncrono.
6. **SNAT por NIC**: se `ipv4.address.external` foi configurado, remove NATs antigos e cria `snat` do IP interno para o externo.
7. **`UpdateLogicalSwitchPortDNS`** — registra o nome DNS da instância na tabela `DNS` do OVN (servida por `pinctrl`). É o que faz `c1.incus` resolver dentro da rede.
8. **Reserva DHCPv4** se o IPv4 é estático.
9. **`ipv4.routes` / `ipv6.routes`** (rotas internas) e **`ipv4.routes.external`** — viram `Logical_Router_Static_Route` no LR, e entram no `Address_Set` da rede para as ACLs.
10. **Modo `l3only`**: adiciona uma rota /32 (ou /128) por instância no roteador em vez de uma rota conectada — sem L2 entre instâncias.
11. **`UpdateLogicalSwitchPortARPProxy`** no `lsp-router` do switch externo — publica os IPs da instância no uplink via **proxy ARP/NDP**, quando o uplink usa `ingress_mode=l2proxy` e NAT está desligado (é assim que um IP externo "aparece" na LAN física sem BGP).
12. **Peers**: adiciona as rotas também nos roteadores das redes pareadas.
13. **ACLs**: adiciona a porta ao **port group da rede** (o sujeito `@internal` das regras) e aos port groups de cada ACL de `security.acls`; remove dos que saíram; aplica a regra default (`security.acls.default.ingress.action` etc.) como ACL específica da porta.
14. **QoS**: `SetLogicalSwitchQoSRules` a partir de `limits.ingress`, `limits.egress`, `limits.max` e os *buckets* — vira `QoS` no NB, que o northd converte em `set_meter`/`set_queue`.
15. Notifica os *peers* de DNS sobre a mudança de zona.

### O `Stop()` / `postStop()`

Remove a porta do `br-int` (`DeleteBridgePort`), desfaz o veth, e no NB: `UpdateLogicalSwitchPortEnabled(false)` (a LSP **persiste** entre boots, para manter alocações), remove registros DNS, ARP proxy e rotas externas conforme o caso. A remoção definitiva da LSP só ocorre em `InstanceDevicePortRemove()`.

## V.5 — O catálogo completo: tudo que o Incus faz com o OVN

### Rede (`incus network ... --type=ovn`)

| Chave | O que vira no OVN |
| --- | --- |
| `network` | a rede de uplink → `lsp-provider` (`localnet`) + `ovn-bridge-mappings` |
| `ipv4.address` / `ipv6.address` | `networks` do `lrp-int` + `other_config:subnet` do `ls-int` |
| `ipv4.nat` / `ipv6.nat` | `NAT` tipo `snat` no LR |
| `ipv4.nat.address` / `ipv6.nat.address` | o `external_ip` do SNAT |
| `ipv4.dhcp` / `ipv6.dhcp` / `ipv6.dhcp.stateful` | registros `DHCP_Options` (servidos pelo `pinctrl`) |
| `ipv4.dhcp.ranges`, `ipv4.dhcp.expiry`, `ipv4.dhcp.gateway`, `ipv4.dhcp.routes` | opções DHCP e `exclude_ips` do IPAM |
| `ipv6.ra` | `ipv6_ra_configs` no `lrp-int` |
| `ipv4.l3only` / `ipv6.l3only` | rotas /32 e /128 por instância + rota de descarte da sub-rede |
| `dns.domain`, `dns.nameservers`, `dns.search` | opções DHCP/RA e a tabela `DNS` |
| `dns.mode`, `dns.zone.*` | zonas DNS do Incus (servidor DNS próprio do Incus, alimentado pelas LSPs) |
| `bridge.mtu` | MTU dos LRPs e das NICs (default: derivado do underlay) |
| `bridge.hwaddr` | MAC do roteador (default: estável por certificado+ID) |
| `bridge.multicast_snooping` / `multicast_relay` | `other_config` do LS / LR |
| `bridge.external_interfaces` | LSPs extras plugando NICs do host no `ls-int` |
| `security.acls` + `security.acls.default.*` | `Port_Group`s e `ACL`s |
| `tunnel.*` | túneis geridos no nível da bridge de uplink |

### Recursos de nível superior

| Recurso Incus | Comando | Implementação OVN |
| --- | --- | --- |
| **Network ACLs** | `incus network acl create/rule add` | `Port_Group` + `Address_Set` + `ACL` com `conjunction` |
| **Address sets** | `incus network address-set ...` | `Address_Set` do NB |
| **Network forwards** | `incus network forward create <net> <ip>` | `Load_Balancer` (`incus-net<N>-lb-<ip>`) com VIPs achatados + `NAT` default |
| **Load balancers** | `incus network load-balancer create` | `Load_Balancer` com múltiplos backends |
| **Network zones (DNS)** | `incus network zone ...` | servidor DNS do Incus alimentado pelas LSPs (não pelo `pinctrl`) |
| **Network peers (local)** | `incus network peer create net1 p net2` | par de `Logical_Router_Port` (`...-lrp-peer-net<M>`) ligando dois LRs diretamente + rotas + policies |
| **Network integrations (remoto)** | `incus network integration create ...` + `incus network peer create ... --type=remote` | **OVN-IC**: `Transit_Switch` no IC-NB + LRP/LSP locais + chassis group |
| **NIC OVN** | `nictype=ovn` / `network=<ovn>` | LSP + veth em `br-int` |
| **Aceleração** | `acceleration=sriov` / `vdpa` | VF + representor com `iface-id`; datapath em hardware via `tc-flower` |
| **`ipv4.address.external`** | por NIC | `dnat_and_snat` / `snat` no LR + proxy ARP no uplink |
| **`limits.*`** | por NIC | `QoS` no NB → meters/queues |
| **`security.promiscuous`** | por NIC | relaxa `port_security` |
| **`nested` / `vlan`** | por NIC | LSP com `parent_name` + `tag` (containers dentro de VM) |

### E o OVN-IC — a resposta que atualiza a conclusão do lab

O README do `advanced-ovn-ic` registra, corretamente **para a versão usada**:

> "O Incus tem rede OVN nativa, mas gerencia o NB ele mesmo e **não expõe configuração de OVN-IC**."

No Incus atual isso **mudou**. Existem as **network integrations**:

```
# 1. declarar a federação (global ao deployment, não presa a projeto ou rede)
incus network integration create ovn-region ovn
incus network integration set ovn-region \
   ovn.northbound_connection=tcp:10.0.0.1:6645,tcp:10.0.0.2:6645,tcp:10.0.0.3:6645
incus network integration set ovn-region \
   ovn.southbound_connection=tcp:10.0.0.1:6646,tcp:10.0.0.2:6646,tcp:10.0.0.3:6646

# 2. usar
incus network peer create default region ovn-region --type=remote
```

Pré-requisitos que a documentação do Incus lista — **são exatamente as gotchas do laboratório**:

- IC-NB e IC-SB funcionando;
- dois ou mais clusters OVN com o **nome da availability zone configurado** (propriedade `name` — *gotcha #1*);
- `ovn-ic` rodando em todos os clusters;
- clusters configurados para **anunciar e aprender rotas** (*gotcha #4*);
- **pelo menos um servidor marcado como gateway de interconnect** (`ovn-is-interconn=true` — *gotcha #5*).

O que o Incus faz quando você cria o peer remoto (`driver_ovn.go:7490`):

1. Abre clientes **IC-NB** e **IC-SB** com as credenciais da integração.
2. Lê `NB_Global.name` do NB local (**o nome do AZ**).
3. Consulta `icsb.GetGateways(azName)` — **se não houver nenhum gateway registrado, falha com `No chassis gateways available for interconnect`**.
4. Renderiza o nome do transit switch a partir de `ovn.transit.pattern` (padrão: `ts-incus-{{ integrationName }}-{{ projectName }}-{{ networkName }}`).
5. Cria um **chassis group** local com o nome do TS e atribui **prioridades pseudoaleatórias mas estáveis** (seed = nome do TS, máximo 32767) a cada gateway — assim redes diferentes escolhem gateways diferentes, distribuindo a carga.
6. **`icnb.CreateTransitSwitch(tsName, mayExist=true)`** — cria o `Transit_Switch` no IC-NB, marcado com `external_ids:incus-managed=true`.
7. **Espera até 10 s o TS aparecer no NB local** — ou seja, espera o `ovn-ic` propagá-lo (exatamente o comportamento da *gotcha #2*, aqui tratado como contrato).
8. **`icnb.CreateTransitSwitchAllocation(tsName, azName)`** — um **IPAM próprio do Incus dentro do `external_ids` do transit switch**: lê `incus-subnet-ipv4`/`incus-subnet-ipv6`, varre as chaves `incus-allocation-*` já existentes, escolhe o próximo endereço livre em cada família e grava `incus-allocation-<az> = "<v4>,<v6>"`. É como dois deployments Incus independentes concordam sobre quem usa qual IP no TS sem se falarem.
9. **`CreateLogicalRouterPort`** no roteador da rede, com o endereço alocado e **o chassis group** (⇒ distributed gateway port — a *gotcha #5* resolvida automaticamente).
10. **`CreateLogicalSwitchPort`** no transit switch, nomeada `<ts>-<az>`, ligada ao LRP.

Ou seja: o Incus moderno **automatiza 4 das 5 gotchas** do laboratório. O que ele continua **não** fazendo:

- não instala nem gerencia o `ovn-ic`, o `ovn-northd`, os bancos NB/SB/IC ou o `ovn-controller`;
- não configura `ovn-is-interconn` nos chassis;
- não define `NB_Global.name`;
- não configura `ic-route-adv` / `ic-route-learn`.

**Conclusão para o seu projeto**: a abordagem do lab (OVN na mão, Incus só como runtime de workload) continua sendo a correta **para pesquisar o OVN-IC**, porque dá controle total da topologia — múltiplos planos, transit switches nomeados por você, edge router próprio. Mas, se o objetivo passar a ser *operar* uma nuvem multi-região com Incus, as `network integrations` fazem o trabalho e a sobreposição com o lab é grande. Vale um experimento comparativo: a mesma topologia de dois planos feita com duas `network integrations` distintas (uma por plano), e medir o que se perde de controle.

## V.6 — Mapeamento de problemas Incus↔OVN

| Sintoma no Incus | Onde olhar no OVN |
| --- | --- |
| Instância sobe sem IP | `ovn-sbctl list Port_Binding <lsp>` — `chassis` vazio? `iface-id` errado? `ovs-vsctl get interface <veth> external_ids` |
| IP atribuído mas sem conectividade externa | `ovn-nbctl lr-nat-list incus-net<N>-lr`, `ovn-bridge-mappings` no chassis, existência do `localnet` |
| `incus network create` demora/erra | `ovn-nbctl show`, log do `northd`, conectividade com `network.ovn.northbound_connection` |
| DHCP não responde | `ovn-nbctl list DHCP_Options`, `ovn-sbctl list DHCP_Options`, logs do `ovn-controller` (pinctrl) |
| DNS interno não resolve | `ovn-sbctl list DNS`, e para zonas do Incus o servidor DNS do próprio Incus |
| ACL não aplica | `ovn-nbctl list Port_Group`, `ovn-nbctl acl-list <pg>` |
| Forward/LB não responde | `ovn-nbctl lb-list`; lembrar das gotchas #10–#15 (switch vs roteador, ARP do VIP) |
| Peer remoto falha | `ovn-ic-sbctl show` (há gateways?), `NB_Global.name`, `ovn-ic` rodando |
| MTU quebrando conexões grandes | `bridge.mtu` da rede vs MTU do underlay menos 58 |
| Instância perde IP ao reiniciar | `volatile.<dev>.last_state.ip_addresses`; IP "pegajoso" foi tomado por outra porta |

---

# Parte VI — O que falta para dominar: temas, laboratórios e referência

## VI.1 — Temas que não estão no repositório e que você não pediu, mas que fazem falta

### 1. Version skew — o problema operacional número um

OVN tem um contrato de compatibilidade entre `northd` e `ovn-controller`: durante um upgrade, controllers antigos precisam entender os logical flows do northd novo. O OVN lida com isso mantendo compatibilidade retroativa por algumas releases (veja no fonte os `MFF_LOG_*_OLD` "needed for backwards compatibility with older northd versions"). Regras práticas:

- **Atualize `ovn-controller` antes do `ovn-northd`** (a ordem recomendada upstream).
- `external_ids:ovn-match-northd-version=true` faz o controller **se recusar** a operar com northd incompatível — melhor do que programar fluxos errados.
- OVS e OVN têm compatibilidade própria; o repositório já registrou isso (OVN 24.03 ↔ OVS 3.3.0).
- Releases LTS do OVN existem (`XX.03` são as de março). Escolher LTS reduz esse problema pela metade.

### 2. O modelo do OpenStack Neutron (ML2/OVN)

O módulo 7 do treinamento cobre Neutron. O mapeamento vale ser memorizado porque é o vocabulário do mercado:

| Neutron | OVN |
| --- | --- |
| Network | `Logical_Switch` |
| Subnet | `other_config:subnet` + `DHCP_Options` |
| Port | `Logical_Switch_Port` |
| Router | `Logical_Router` |
| Router interface | `Logical_Router_Port` + LSP `type=router` |
| External network | `Logical_Switch` com LSP `localnet` |
| Floating IP | `NAT` `dnat_and_snat` (distribuído com `external_mac`) |
| Security Group | `Port_Group` + `ACL` |
| Security Group Rule | `ACL` |
| Allowed address pairs | `port_security` estendido |
| LBaaS / Octavia (ovn-provider) | `Load_Balancer` |
| Availability zone | `ovn-cms-options:availability-zones=` no chassis |

O `ovn-metadata-agent` do Neutron usa uma porta `localport` — o padrão que o Incus **não** usa (o Incus injeta configuração pelo LXC/cloud-init).

### 3. O modelo do ovn-kubernetes

Também vendorizado no repositório. Diferenças estruturais que ensinam:

- **um switch lógico por nó** (o Pod CIDR do nó) em vez de um por rede de tenant;
- um `Logical_Router` distribuído (`ovn_cluster_router`) conectando todos;
- Services do Kubernetes viram `Load_Balancer` do OVN (e não kube-proxy/iptables);
- NetworkPolicy vira `Port_Group` + `ACL`;
- modo **interconnect** do ovn-kubernetes: **uma zona OVN por nó**, federadas por transit switch — é OVN-IC levado ao extremo, e a razão da opção `requested-encap-ip` mencionada no NEWS.

Ler o ovn-kubernetes é a forma mais rápida de ver OVN em escala real.

### 4. Contribuir upstream (o Projeto C do repositório)

O caminho prático:

- o desenvolvimento é por **mailing list** (`ovs-dev@openvswitch.org`) com `git send-email`, não por pull request no GitHub;
- `CONTRIBUTING.rst` e `Documentation/internals/contributing/` no repositório do OVN;
- o *test suite* é **autotest** (`make check`, `tests/ovn.at`, `tests/ovn-northd.at`, `tests/system-ovn.at` para testes com datapath real);
- `ovn-fake-multinode` e `ovn-heater` (ambos já no repositório) para validar em multi-nó e medir regressão de desempenho;
- pontos de entrada bons para um primeiro patch: mensagens de erro ruins (a gotcha #13, `bad ip router_ip`, é literalmente um bug de usabilidade), documentação de OVN-IC (a gotcha #4 é uma discrepância documentação↔código), e cobertura de teste.

**As 16 gotchas do repositório são, cada uma, um candidato a patch ou a melhoria de documentação upstream.** Isso é material de contribuição pronto.

### 5. Desempenho de verdade

- **Onde o custo está**: *upcalls* (userspace), não o encaminhamento. Meça `ovs-appctl upcall/show` e `coverage/show`.
- **Geneve e offload**: NICs modernas fazem encap/decap e checksum; `ethtool -k` deve mostrar `tx-udp_tnl-segmentation: on`. Sem isso, TSO/GSO morre e o throughput despenca.
- **MTU do underlay**: jumbo frames (9000) no underlay eliminam a necessidade de reduzir o MTU do workload — a solução estruturalmente correta para o problema do lab.
- **`ovs-vswitchd` e afinidade de CPU**: `other_config:pmd-cpu-mask` (DPDK), `n-handler-threads`, `n-revalidator-threads`.
- **Benchmark honesto**: `iperf3` mede throughput; `netperf -t TCP_RR` mede latência/PPS — é este que revela o custo do overlay. O `ovn-heater` mede o **control plane**, que é outra coisa.

### 6. Observabilidade além do exporter

- **`ovn-controller` e `ovn-northd` expõem métricas** via `ovn-appctl` (`stopwatch/show`, `coverage/show`, `lflow-cache/show-stats`, `debug/status`) — todas scrapeáveis.
- **`ovs-vswitchd`** tem `coverage/show`, `upcall/show`, `memory/show`.
- **`Chassis_Private.nb_cfg`** é a métrica de convergência mais importante que existe: a diferença entre `NB_Global.nb_cfg` e o menor `nb_cfg` dos chassis é o *lag* da sua rede.
- **`ovn-event-exporter`** (CloudFerro) cobre eventos de tabela do SB; combinar com as métricas acima dá o quadro completo.
- **Sampling nativo** (`sample_new`/`sample_est` nas ACLs, `en-sampling-app.c`) permite exportar fluxos por IPFIX com correlação às ACLs que os permitiram — recurso novo e pouco usado.

### 7. Quando *não* usar OVN

Honestidade técnica vale mais que entusiasmo:

- **rede plana simples, poucos hosts**: bridge Linux ou OVS puro resolve com uma fração da complexidade;
- **Kubernetes puro sem requisitos de multi-tenancy L2**: Cilium (eBPF) tem menos partes móveis e melhor desempenho por conexão;
- **federação com fabric não-OVN**: BGP/EVPN;
- **requisito de criptografia fim a fim**: OVN+IPsec funciona, mas WireGuard/service mesh pode ser mais simples;
- **latência ultrabaixa determinística**: SR-IOV com passthrough direto, sem overlay.

O OVN ganha quando você precisa de **multi-tenancy com sobreposição de endereços, L2 estendido, NAT/LB/ACL declarativos e um único control plane** — que é exatamente o caso de uma nuvem.

## VI.2 — Roteiro de laboratórios (do que você já tem para o domínio completo)

Cada item assume o anterior.

**L1 — OVS sozinho, sem OVN.** Duas bridges OVS em duas VMs, um túnel Geneve manual (`ovs-vsctl add-port br0 gnv0 -- set interface gnv0 type=geneve options:remote_ip=...`), e fluxos escritos à mão com `ovs-ofctl`. Objetivo: ver que o OVN não faz mágica nenhuma no data plane. Faça um `ofproto/trace` e um `dpctl/dump-flows` e entenda a megaflow que aparece.

**L2 — Pipeline OpenFlow com múltiplas tabelas.** Implemente à mão um "switch lógico" simplificado: tabela 0 traduz porta física em metadados, tabela 1 faz L2 lookup, tabela 2 faz saída. Você terá reimplementado, em miniatura, o que o `ovn-controller` gera.

**L3 — OVN mínimo num host.** `ovn-central` + `ovn-host` numa VM; um `Logical_Switch` com dois LSPs; dois namespaces plugados no `br-int` via veth com `iface-id`. Depois: `ovn-sbctl lflow-list` e compare estágio a estágio com a tabela da seção III.4; `ovs-ofctl dump-flows br-int table=8` até achar o mesmo fluxo.

**L4 — Adicione um roteador.** Dois switches, um roteador, ping entre sub-redes. Rode `ovn-trace` e siga os 29 estágios de `lr_in_*`. Depois quebre de propósito: apague o `MAC_Binding` e veja o `lr_in_arp_request` agir.

**L5 — Dois chassis, Geneve real.** (Você já fez isso.) Acrescente: `tcpdump` no underlay decodificando Geneve (`tshark -d udp.port==6081,geneve`) e **encontre a TLV `0x0102:0x80`** — ver os 32 bits de ingress/egress port com os próprios olhos fecha o entendimento do túnel.

**L6 — NAT, LB e ACL.** Gateway router com SNAT; um LB com VIP; ACLs com `Port_Group` e `Address_Set`. Depois `ovs-ofctl dump-flows br-int | grep conj_id` e veja as `conjunction`. Confirme as gotchas #11/#12 experimentalmente: anexe o LB só ao roteador e observe o pacote morrer em `ls_in_l2_unknown` via `ovn-trace`.

**L7 — OVN-IC.** (Feito, duas vezes.) O próximo passo real é **três AZs**, já previsto no README: o `ovn-ic` federa N zonas e o Ansible do lab é orientado a dados.

**L8 — Incus nativo com OVN.** Uma VM, `ovn-central` + `ovn-host`, `incusbr0` como uplink, `incus network create ovntest --type=ovn`. Depois: `ovn-nbctl show` e **identifique cada objeto criado** com a tabela da seção V.2. Crie uma instância e confirme o `iface-id`: `ovs-vsctl get interface <veth> external_ids`.

**L9 — Incus com network integrations (OVN-IC nativo).** Dois deployments Incus, IC-NB/IC-SB compartilhados, `incus network integration create` + `incus network peer create --type=remote`. Compare, item a item, o que o Incus criou com o que o `ovn_topology` do seu Ansible cria. Essa comparação é, por si só, um bom relatório.

**L10 — Escala.** `ovn-fake-multinode` + `ovn-heater` com um cenário de criação massiva de portas; meça convergência (`ovn-nbctl --wait=hv sync` cronometrado) e CPU do northd. Depois ligue `use_logical_dp_groups` e meça de novo.

**L11 — Falha e recuperação.** Mate o `ovn-northd` e mostre que o tráfego continua. Mate o `ovn-controller` de um chassis e mostre que os fluxos ficam. Derrube o líder RAFT (o `verify_ha.yml` já faz). Injete latência com `tc netem delay 50ms dev genev_sys_6081` — sugestão que já está na lista de melhorias do próprio lab.

**L12 — Roteamento dinâmico.** FRR + OVN com `Advertised_Route`/`Learned_Route`, anunciando as sub-redes do tenant por BGP para o fabric. É o caminho para o próximo grande tema depois do OVN-IC.

## VI.3 — Referência rápida de comandos

### Northbound

```
ovn-nbctl show
ovn-nbctl ls-add|ls-del|ls-list <ls>
ovn-nbctl lsp-add <ls> <lsp> [<parent> <tag>]
ovn-nbctl lsp-set-addresses <lsp> "<mac> <ip>" | dynamic | unknown | router
ovn-nbctl lsp-set-port-security <lsp> "<mac> <ip>"
ovn-nbctl lsp-set-type <lsp> router|localnet|localport|external|virtual|remote
ovn-nbctl lsp-set-options <lsp> router-port=<lrp>|network_name=<physnet>
ovn-nbctl lr-add|lr-del|lr-list <lr>
ovn-nbctl lrp-add <lr> <lrp> <mac> <cidr...>
ovn-nbctl lrp-set-gateway-chassis <lrp> <chassis> <prio>     # gotcha #5
ovn-nbctl lr-route-add|lr-route-list <lr> [<prefix> <nexthop>]
ovn-nbctl lr-policy-add|lr-policy-list <lr> <prio> <match> <action>
ovn-nbctl lr-nat-add <lr> snat|dnat|dnat_and_snat <ext> <int>
ovn-nbctl lb-add|lb-list|lb-del <lb> <vip> <backends> [tcp|udp]
ovn-nbctl ls-lb-add <ls> <lb>  ;  ovn-nbctl lr-lb-add <lr> <lb>   # gotchas #11/#12
ovn-nbctl acl-add <ls|pg> <dir> <prio> <match> <action>
ovn-nbctl pg-add|pg-set-ports <pg> <lsp...>
ovn-nbctl dhcp-options-create <cidr> ; ovn-nbctl dhcp-options-set-options <uuid> ...
ovn-nbctl set NB_Global . name=az1
ovn-nbctl set NB_Global . options:ic-route-adv=true options:ic-route-learn=true   # gotcha #4
ovn-nbctl --wait=hv sync            # espera todos os chassis convergirem
```

### Southbound

```
ovn-sbctl show
ovn-sbctl list Chassis | Chassis_Private | Encap
ovn-sbctl list Port_Binding <lsp>
ovn-sbctl list Datapath_Binding
ovn-sbctl lflow-list [<datapath>]
ovn-sbctl list MAC_Binding
ovn-sbctl dump-flows
```

### Interconnect

```
ovn-ic-nbctl show ; ovn-ic-nbctl ts-add <ts> ; ovn-ic-nbctl list Transit_Switch
ovn-ic-sbctl show ; ovn-ic-sbctl list Availability_Zone | Gateway | Route
```

### OVS

```
ovs-vsctl show
ovs-vsctl add-port br-int <iface> -- set interface <iface> external_ids:iface-id=<lsp>
ovs-vsctl get interface <iface> external_ids
ovs-vsctl list open_vswitch .
ovs-ofctl -O OpenFlow15 dump-flows br-int [table=N]
ovs-appctl ofproto/trace br-int '<flow>'
ovs-appctl dpctl/dump-flows -m
ovs-appctl dpif/show ; ovs-appctl upcall/show ; ovs-appctl coverage/show
ovs-appctl dpctl/dump-conntrack
```

### Diagnóstico OVN

```
ovn-trace [--db=...] <datapath> '<microflow>'
ovn-detrace < <saída de ofproto/trace>
ovn-appctl -t ovn-controller lflow-cache/show-stats
ovn-appctl -t ovn-controller debug/status
ovn-appctl -t ovn-northd stopwatch/show
ovs-appctl -t /var/run/ovn/ovnsb_db.ctl cluster/status OVN_Southbound
```

### Incus

```
incus config set network.ovn.northbound_connection tcp:...:6641
incus config set network.ovn.integration_bridge br-int
incus network create <net> --type=ovn network=<uplink> ipv4.address=... ipv4.nat=true
incus network show|edit|set <net>
incus network acl create|rule add <acl> ...
incus network forward create <net> <listen-ip> target_address=<ip>
incus network load-balancer create <net> <listen-ip>
incus network peer create <net> <peer> <target>            # local
incus network integration create <name> ovn                # OVN-IC
incus network peer create <net> <peer> <integration> --type=remote
incus config device override <inst> eth0 network=<net> ipv4.address=<ip>
incus network info <net> ; incus network list-allocations
```

## VI.4 — Glossário

| Termo | Definição de trabalho |
| --- | --- |
| **AZ (Availability Zone)** | uma instalação OVN independente: seu NB, SB, `northd` e chassis |
| **Chassis** | um hipervisor participando do OVN; um `ovn-controller` |
| **`br-int`** | a bridge OVS de integração, onde tudo é plugado |
| **Datapath (lógico)** | um switch ou roteador lógico, identificado pelo `tunnel_key` de 24 bits |
| **Datapath (físico)** | a tabela de fluxos do kernel (ou userspace) do OVS |
| **DGP** | *distributed gateway port*: LRP com chassis de gateway; gera a porta `cr-*` |
| **Gateway router** | roteador lógico inteiro fixado num chassis (`options:chassis`) |
| **`iface-id`** | `external_ids` da `Interface` OVS que a liga a uma LSP — a única cola físico↔lógico |
| **I-P engine** | motor de processamento incremental do northd e do controller |
| **Logical flow** | a regra intermediária gerada pelo northd, no SB |
| **`localnet`** | LSP que faz ponte para uma rede física nomeada |
| **`localport`** | LSP presente em todo chassis que nunca tunela |
| **Megaflow** | entrada mascarada na tabela do datapath |
| **`pinctrl`** | a thread do `ovn-controller` que responde DHCP/DNS/ARP/ICMP via packet-in/out |
| **Port binding** | a associação entre uma porta lógica e o chassis que a hospeda |
| **Transit switch** | switch lógico compartilhado entre AZs, declarado no IC-NB |
| **Tunnel key** | o identificador que viaja no VNI (datapath) e na TLV Geneve (portas) |
| **Upcall** | o envio de um pacote do datapath para o userspace por falta de fluxo |
| **VIF** | interface virtual de uma VM/container |

---

## Fontes

Repositórios e código lidos diretamente para este documento:

- [cloudlabs-ufscar/sdn](https://github.com/cloudlabs-ufscar/sdn) — READMEs de `training/`, `projects/`, `projects/core/`, `projects/ovn-exporter/`, `projects/ovn-ic/pratice-ovn-ic/`, `projects/ovn-ic/advanced-ovn-ic/` e o Ansible de `advanced-ovn-ic/ansible/`
- OVN, código vendorizado em `projects/core/ovn` (post-v26.03.0): `ovn-architecture.7.xml`, `northd/northd.h`, `northd/ovn-northd.8.xml`, `controller/lflow.h`, `controller/ovn-controller.8.xml`, `include/ovn/logical-fields.h`, `ic/ovn-ic.c`, `NEWS`
- Open vSwitch, código vendorizado em `projects/core/ovs` (post-v3.7.0): `Documentation/topics/datapath.rst`, `lib/dpif-netlink.c`, `NEWS`
- [lxc/incus](https://github.com/lxc/incus) — `internal/server/network/driver_ovn.go`, `internal/server/network/ovn/*.go`, `internal/server/network/ovs/ovs_actions.go`, `internal/server/device/nic_ovn.go`, `internal/server/network/acl/acl_ovn.go`, `internal/server/cluster/config/config.go`, `doc/reference/network_ovn.md`, `doc/howto/network_ovn_setup.md`, `doc/howto/network_integrations.md`, `doc/config_options.txt`

Documentação oficial de referência:

- [Documentação do OVN](https://docs.ovn.org/en/latest/) — [arquitetura](https://docs.ovn.org/en/latest/ref/ovn-architecture.7.html), [tutorial de interconnect](https://docs.ovn.org/en/latest/tutorials/ovn-interconnection.html), [roteamento dinâmico](https://docs.ovn.org/en/latest/topics/dynamic-routing/architecture.html)
- [Changelog do OVN v26.03.0](https://www.ovn.org/en/releases/changelog_v26.03.0/)
- [Documentação do Open vSwitch](https://docs.openvswitch.org/en/latest/)
- [Rede OVN no Incus](https://linuxcontainers.org/incus/docs/main/reference/network_ovn/) · [setup](https://linuxcontainers.org/incus/docs/main/howto/network_ovn_setup/) · [network integrations](https://linuxcontainers.org/incus/docs/main/howto/network_integrations/)
- [PR #655 do Incus — introdução das network integrations e suporte a OVN interconnect](https://github.com/lxc/incus/pull/655)
- [ovn-event-exporter (CloudFerro)](https://github.com/CloudFerro/ovn-event-exporter) · [ovn-fake-multinode](https://github.com/ovn-org/ovn-fake-multinode) · [ovn-heater](https://github.com/ovn-org/ovn-heater)
