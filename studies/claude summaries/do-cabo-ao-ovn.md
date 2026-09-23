# Guia de Redes para SDN — do cabo ao OVN

> Material de consolidação. A ordem é deliberada: cada bloco depende do anterior.
> O objetivo final é entender **por que** o OVN faz o que faz, não decorar comandos.

---

## Bloco 1 — Camada física e L2

### 1. Modelos OSI e TCP/IP, encapsulamento, PDU, MTU

O modelo OSI (7 camadas) é uma ferramenta de raciocínio, não uma implementação. A pilha real é a TCP/IP (4 camadas). O que importa na prática é o mapeamento:

| OSI | TCP/IP | PDU | Exemplos | Endereçamento |
|---|---|---|---|---|
| 7/6/5 Aplicação/Apresentação/Sessão | Aplicação | mensagem | HTTP, DNS, DHCP, TLS | URL, FQDN |
| 4 Transporte | Transporte | segmento (TCP) / datagrama (UDP) | TCP, UDP, SCTP, QUIC | porta |
| 3 Rede | Internet | pacote | IPv4, IPv6, ICMP | IP |
| 2 Enlace | Link | frame | Ethernet, 802.11, PPP | MAC |
| 1 Física | Link | bit/símbolo | 1000BASE-T, 10GBASE-SR | — |

**Encapsulamento** é o mecanismo central: cada camada trata a PDU da camada de cima como payload opaco e prefixa seu próprio header.

```
[ Ethernet | IP | TCP | HTTP ...................... | FCS ]
 14 bytes    20   20    payload                       4
```

Isso tem duas consequências que você vai usar o tempo todo:

1. **Overhead se acumula.** Cada header consome bytes que não são dados úteis. Em overlay (Bloco 4) isso vira problema de MTU.
2. **Independência de camadas é uma ficção útil, mas vazada.** TCP precisa saber o MSS que depende do MTU do L2; NAT (L3) reescreve checksums de L4; um switch L2 moderno inspeciona L3/L4 para hashing de LAG.

**MTU (Maximum Transmission Unit)** é o maior payload que um frame de enlace carrega. Ethernet padrão: 1500 bytes de payload L3. O frame completo no fio é 1518 bytes (14 de header + 1500 + 4 de FCS), 1522 com uma tag VLAN.

Termos que confundem e você precisa separar com precisão:

- **MTU** — payload L3 máximo (1500).
- **MSS** — payload TCP máximo. Em IPv4: `MTU − 20 (IP) − 20 (TCP) = 1460`. Com opções TCP (timestamps, SACK) o payload efetivo cai mais.
- **Frame size** — o que trafega no fio, incluindo header L2 e FCS.
- **Jumbo frame** — MTU > 1500, tipicamente 9000. Precisa ser configurado *fim a fim*; um único hop com 1500 no meio quebra tudo de forma sutil (ver PMTUD, item 14).

### 2. Ethernet: frame, MAC, EtherType, tipos de endereço

Formato do frame Ethernet II (o único que você vai ver na prática):

```
+----------+----------+-----------+---------------+-----+
| DST MAC  | SRC MAC  | EtherType | Payload       | FCS |
| 6 bytes  | 6 bytes  | 2 bytes   | 46-1500 bytes | 4   |
+----------+----------+-----------+---------------+-----+
```

**Endereço MAC** — 48 bits, escrito em hex (`fa:16:3e:1a:2b:3c`). Estrutura:

- Primeiros 24 bits: **OUI**, identificador do fabricante. `fa:16:3e` é o OUI do OpenStack/QEMU — você reconhece VMs do Neutron de longe. `52:54:00` é QEMU/KVM. `00:50:56` é VMware.
- **Bit I/G** (bit menos significativo do primeiro octeto): 0 = unicast, 1 = multicast/broadcast.
- **Bit U/L** (segundo bit menos significativo do primeiro octeto): 0 = globalmente único (queimado na NIC), 1 = administrado localmente. Endereços gerados por hipervisor ou container runtime setam esse bit.

Reconhecer isso na prática: `02:42:ac:11:00:02` (Docker) tem o primeiro octeto `02` = `00000010`, ou seja, bit U/L ligado, bit I/G desligado → unicast localmente administrado. `ff:ff:ff:ff:ff:ff` tem todos os bits ligados → broadcast.

**EtherType** identifica o protocolo do payload:

| Valor | Protocolo |
|---|---|
| 0x0800 | IPv4 |
| 0x0806 | ARP |
| 0x86DD | IPv6 |
| 0x8100 | VLAN tag 802.1Q |
| 0x88A8 | QinQ / 802.1ad |
| 0x8847 | MPLS unicast |
| 0x88CC | LLDP |
| 0x8809 | LACP / Slow protocols |

**Três modos de entrega em L2:**

- **Unicast** — MAC de destino específico. O switch entrega em uma porta só (se souber onde está).
- **Broadcast** — `ff:ff:ff:ff:ff:ff`. Entregue em **todas** as portas do domínio de broadcast exceto a de origem. Usado por ARP request, DHCP Discover, e é o principal custo de escalabilidade de uma rede L2 grande.
- **Multicast** — bit I/G ligado, destino é um grupo. Ex.: `01:00:5e:xx:xx:xx` para IPv4 multicast, `33:33:xx:xx:xx:xx` para IPv6 multicast (NDP usa isso pesadamente), `01:80:c2:00:00:00` para STP. Sem IGMP snooping, o switch trata multicast como broadcast.

**Por que isso importa para OVN:** o OVN foi desenhado em boa parte para **eliminar broadcast**. ARP, DHCP e ND são respondidos localmente por flows no hipervisor de origem, sem tocar a rede. Entender exatamente o que o broadcast faz é o que te permite entender o valor dessa escolha.

### 3. Switching: MAC learning, CAM, flooding, domínios

Um switch L2 opera com uma máquina simples:

1. Frame chega na porta `P` com SRC MAC `M`.
2. **Learning:** registra na tabela CAM/MAC que `M` está acessível por `P`, com timestamp. Entrada expira (aging, tipicamente 300s).
3. **Forwarding:** olha o DST MAC.
   - Se está na tabela → encaminha só naquela porta.
   - Se não está (unknown unicast) → **flood** em todas as portas do mesmo domínio de broadcast, exceto a de entrada.
   - Se é broadcast/multicast → flood.

Consequências operacionais:

- **Unknown unicast flooding** é tráfego desperdiçado e vazamento de dados. Em redes grandes é fonte real de problema.
- **MAC flapping** — o mesmo MAC aparecendo alternadamente em duas portas indica loop ou VM migrada. Em log de switch é sinal de alerta.
- **CAM table overflow** — ataque clássico: inundar com MACs falsos até estourar a tabela, forçando o switch a floodar tudo (fail-open), o que permite sniffing. Mitigação: port security / limite de MACs por porta.

**Domínio de colisão** — herança de hub/half-duplex. Em switching full-duplex moderno, cada porta é seu próprio domínio de colisão. Praticamente irrelevante hoje, exceto como conceito histórico.

**Domínio de broadcast** — conjunto de portas onde um broadcast se propaga. Delimitado por: (a) um roteador, ou (b) fronteira de VLAN. Essa é a definição que importa. Um switch com 3 VLANs tem 3 domínios de broadcast.

### 4. VLAN 802.1Q, trunk/access, QinQ

Uma VLAN particiona um switch físico em vários switches lógicos. Cada VLAN = um domínio de broadcast = (tipicamente) uma sub-rede IP.

A tag 802.1Q são 4 bytes inseridos após o SRC MAC:

```
+---------+---------+--------+-----+-----+---------+---------+
| DST MAC | SRC MAC | 0x8100 | PCP | DEI | VID(12) | EtherType ...
+---------+---------+--------+-----+-----+---------+---------+
```

- **TPID** `0x8100` — sinaliza "tem tag aqui".
- **PCP** (3 bits) — prioridade, base do 802.1p/CoS para QoS.
- **DEI** (1 bit) — drop eligible.
- **VID** (12 bits) — o ID da VLAN. 12 bits = 4096 valores, sendo 0 e 4095 reservados → **4094 VLANs úteis**.

Esse limite de 4094 é uma das razões históricas que motivaram overlay (Bloco 4). Em um datacenter multi-tenant, 4094 tenants isolados é pouco.

**Tipos de porta:**

- **Access** — pertence a uma VLAN só. Frames entram sem tag e são tagueados internamente; saem sem tag. O host conectado não sabe que VLAN existe.
- **Trunk** — carrega múltiplas VLANs tagueadas. Usada entre switches, ou entre switch e hipervisor (que precisa distinguir tenants).
- **Native VLAN** — a VLAN que trafega **sem tag** num trunk. Fonte clássica de bug: se os dois lados discordam de qual é a native, tráfego vaza entre VLANs. É a base do ataque de *VLAN hopping* por double tagging. Boa prática: native VLAN dedicada e não usada para dados.

**QinQ (802.1ad)** — duas tags empilhadas (`S-VLAN` externa do provedor, `C-VLAN` interna do cliente). Permite ao provedor transportar as VLANs do cliente sem colisão de numeração. TPID externo `0x88A8`. É a solução "pré-overlay" para multi-tenancy — funciona, mas é rígida e ainda limitada.

### 5. STP, RSTP, MSTP — e o problema real de loop em L2

L2 não tem TTL. Se existe um ciclo físico entre switches, um broadcast circula para sempre, é replicado a cada hop, e em segundos satura todos os links. Isso é o **broadcast storm** — o modo de falha mais destrutivo de uma rede L2, porque tende a derrubar até o plano de gerência.

**STP (802.1D)** resolve elegendo uma raiz e bloqueando portas até sobrar uma topologia em árvore (sem ciclos):

1. Elege **root bridge** — menor Bridge ID (prioridade + MAC). Sempre configure a prioridade explicitamente; deixar o padrão significa eleger o switch com o MAC mais baixo, que costuma ser o mais velho e pior.
2. Cada switch escolhe sua **root port** — a de menor custo até a raiz.
3. Cada segmento elege uma **designated port**.
4. O resto vira **blocking**.

Estados: Blocking → Listening → Learning → Forwarding, com convergência de ~30–50s. Inaceitável hoje.

**RSTP (802.1w)** — convergência em sub-segundo, com papéis alternate/backup e proposal/agreement. É o mínimo aceitável.
**MSTP (802.1s)** — múltiplas instâncias de spanning tree, cada uma cobrindo um grupo de VLANs, permitindo balancear carga entre links que o STP simples bloquearia.

Recursos operacionais que você deve saber citar: **PortFast/edge port** (pula listening/learning em portas de host), **BPDU Guard** (desabilita a porta se receber BPDU onde não deveria — evita que alguém plugue um switch), **Root Guard**, **Loop Guard**.

**Por que isso motiva o resto do guia:** STP desperdiça links inteiros mantendo-os bloqueados. Em datacenter isso é inaceitável — é a razão de leaf-spine com ECMP em L3 (item 8/13) ter substituído L2 estendido, e de overlays existirem para reconstituir a adjacência L2 que as aplicações ainda querem.

### 6. LAG / LACP / bonding

Agregação de links: várias interfaces físicas viram uma interface lógica, somando banda e provendo redundância sem que o STP bloqueie nada.

- **LACP (802.3ad)** — protocolo de negociação dinâmica (EtherType `0x8809`). Modo *active* envia PDUs; *passive* só responde. Detecta cabo errado, link unidirecional, e falhas que o "link up" não pega.
- **Static LAG** — sem protocolo. Se houver erro de cabeamento, você cria um loop silenciosamente. Evite.

O tráfego é distribuído por **hashing**, não round-robin: um hash sobre (MAC src/dst), (IP src/dst), ou (IP + porta L4) escolhe o membro. Isso garante que um fluxo não sofra reordenação, mas implica: **um único fluxo TCP nunca excede a banda de um membro**. Um LAG 4×10G não dá 40G para uma transferência só.

Bonding no Linux (`/proc/net/bonding/bond0`), modos relevantes:

| Modo | Nome | Uso |
|---|---|---|
| 0 | balance-rr | round-robin, causa reordenação, raramente usado |
| 1 | active-backup | só redundância, funciona com qualquer switch |
| 2 | balance-xor | hash estático |
| 4 | 802.3ad | LACP — o padrão de datacenter |
| 6 | balance-alb | balanceio por ARP, sem suporte do switch |

**MLAG / vPC / stacking** — permite que o LAG do host termine em **dois switches distintos**, eliminando o switch como ponto único de falha. Cada fabricante chama de um nome. Alternativa moderna: abandonar MLAG e fazer o host rodar BGP com ECMP para dois leafs (Bloco 2, item 13).

### 7. Meio físico: cobre, fibra, ópticas, autonegociação

- **Cobre (RJ45, 1000BASE-T / 10GBASE-T)** — barato, até 100 m, mas 10GBASE-T tem latência e consumo altos. Comum em acesso, raro em datacenter moderno.
- **DAC (Direct Attach Copper)** — cabo twinax com ópticas fixas nas pontas. Curtíssimo alcance (1–5 m), baratíssimo, latência mínima. Padrão para servidor → ToR dentro do mesmo rack.
- **AOC (Active Optical Cable)** — mesma ideia, fibra, até ~30 m.
- **Fibra multimodo (OM3/OM4, 10GBASE-SR)** — dentro do datacenter, dezenas a centenas de metros.
- **Fibra monomodo (LR/ER)** — entre datacenters, quilômetros.

**Form factors:** SFP (1G) → SFP+ (10G) → SFP28 (25G) → QSFP+ (40G) → QSFP28 (100G) → QSFP-DD/OSFP (400G). Um QSFP de 40G/100G pode ser *breakout*: 1 porta → 4 links de 10G/25G, com cabo específico. Isso muda o cálculo de densidade de portas do ToR.

**Autonegociação** — negocia velocidade e duplex. O clássico modo de falha: um lado auto, outro fixo → o lado auto cai para half-duplex, e você vê colisões tardias, perda intermitente e throughput horrível sem que a interface caia. Regra: ambos auto, ou ambos fixos e idênticos.

**Contadores a olhar sempre** (`ip -s link`, `ethtool -S`): CRC errors (cabo/óptica ruim), input discards (buffer/congestão), output drops (fila/QoS), late collisions (mismatch de duplex), rx_missed (CPU não deu conta).

### 8. Topologia de datacenter: three-tier vs leaf-spine

**Three-tier clássico** (access → aggregation → core) foi desenhado para tráfego **north-south**: cliente externo → servidor. Latência entre dois servidores varia conforme a distância na árvore, o STP bloqueia links, e o escalonamento é vertical (comprar um core maior).

**Leaf-spine (Clos)** é o que se usa hoje:

- Cada **leaf** (ToR) conecta a **todos** os spines. Leafs não se conectam entre si; spines não se conectam entre si.
- Qualquer servidor alcança qualquer outro em exatamente 2 hops (leaf → spine → leaf). **Latência previsível.**
- Roteamento é L3 com **ECMP**: todos os uplinks ativos, sem STP.
- Escala horizontalmente: mais banda = mais spines; mais servidores = mais leafs.

Termos que você precisa usar com naturalidade:

- **East-west** — tráfego servidor↔servidor. É a maioria absoluta do tráfego moderno (microserviços, storage distribuído, replicação). Leaf-spine existe por causa disso.
- **North-south** — tráfego que entra/sai do datacenter, via *border leaf* / gateway.
- **Oversubscription** — razão entre banda de acesso e banda de uplink. Um leaf com 48×25G de acesso e 8×100G de uplink tem 1200G:800G = 1.5:1. 3:1 é comum, 1:1 é caro e raramente necessário.
- **Incast** — muitos servidores respondendo simultaneamente a um só (padrão map-reduce, leitura de storage distribuído). Estoura o buffer do ToR e causa perda em rajada. Mitigações: ECN, buffers profundos, controle de congestionamento adequado (DCTCP/BBR).

**A ponte para o resto do guia:** leaf-spine é L3 puro no underlay. Mas VMs e containers querem endereços estáveis, mobilidade e adjacência L2. A reconciliação dessas duas exigências **é** a razão de existir do overlay, do OVS e do OVN.
---

## Bloco 2 — Camada 3

### 9. IPv4: máscara, CIDR, subnetting, VLSM, endereços especiais

Um endereço IPv4 tem 32 bits. A máscara divide em **prefixo de rede** + **host**. Notação CIDR: `10.0.5.17/24` = 24 bits de rede, 8 de host.

Tabela que você precisa saber de cabeça:

| CIDR | Máscara | Hosts úteis | Uso típico |
|---|---|---|---|
| /32 | 255.255.255.255 | 1 | loopback, host route, VIP |
| /31 | 255.255.255.254 | 2 (RFC 3021) | link ponto-a-ponto |
| /30 | 255.255.255.252 | 2 | link p2p legado |
| /29 | 255.255.255.248 | 6 | bloco público pequeno |
| /28 | 255.255.255.240 | 14 | |
| /24 | 255.255.255.0 | 254 | sub-rede padrão |
| /23 | 255.255.254.0 | 510 | |
| /22 | 255.255.252.0 | 1022 | |
| /16 | 255.255.0.0 | 65534 | rede de tenant grande |

Regra rápida: hosts úteis = 2^(32−prefixo) − 2 (descontando endereço de rede e broadcast). Exceção: /31 e /32.

**Subnetting na prática.** Para `192.168.10.0/24` dividido em 4 sub-redes /26:

```
192.168.10.0/26    → hosts .1–.62,    broadcast .63
192.168.10.64/26   → hosts .65–.126,  broadcast .127
192.168.10.128/26  → hosts .129–.190, broadcast .191
192.168.10.192/26  → hosts .193–.254, broadcast .255
```

O truque mental: o "bloco" de um /26 é 64 (= 256 − 192). As fronteiras são múltiplos do bloco. Para /28 o bloco é 16, para /27 é 32, para /30 é 4.

**VLSM (Variable Length Subnet Mask)** — usar prefixos de tamanhos diferentes dentro do mesmo espaço, dimensionando cada sub-rede à necessidade. Um link p2p recebe /31, uma sub-rede de servidores recebe /24. É o padrão desde o fim das classes A/B/C (que só valem como vocabulário histórico hoje).

**Ranges especiais que você precisa reconhecer instantaneamente:**

| Range | RFC | Significado |
|---|---|---|
| 10.0.0.0/8 | 1918 | privado |
| 172.16.0.0/12 | 1918 | privado (172.16–172.31!) |
| 192.168.0.0/16 | 1918 | privado |
| 100.64.0.0/10 | 6598 | CGNAT — muito usado como espaço interno de cloud |
| 127.0.0.0/8 | — | loopback |
| 169.254.0.0/16 | 3927 | link-local / APIPA. `169.254.169.254` é o endereço de metadata em praticamente toda cloud |
| 224.0.0.0/4 | — | multicast |
| 0.0.0.0/0 | — | rota default / "qualquer" |

**Agregação (supernetting):** `10.0.0.0/24` + `10.0.1.0/24` + `10.0.2.0/24` + `10.0.3.0/24` = `10.0.0.0/22`. É o que permite que a tabela de roteamento global não exploda. Planejar endereçamento de forma agregável é uma das marcas de quem sabe desenhar rede.

### 10. IPv6: o essencial que você vai encontrar

128 bits, notação hex em 8 grupos, com compressão: `2001:0db8:0000:0000:0000:0000:0000:0001` → `2001:db8::1` (o `::` pode aparecer uma única vez).

Diferenças conceituais que importam:

- **Sem broadcast.** IPv6 substituiu broadcast por multicast. `ff02::1` = todos os nós do link, `ff02::2` = todos os roteadores.
- **ARP não existe.** Foi substituído por **NDP** (Neighbor Discovery, sobre ICMPv6): Neighbor Solicitation / Neighbor Advertisement para resolução de endereço, Router Solicitation / Router Advertisement para descoberta de gateway.
- **Link-local obrigatório** — todo nó tem um `fe80::/10`, gerado automaticamente. É sobre ele que NDP e protocolos de roteamento operam.
- **SLAAC** — autoconfiguração via Router Advertisement, sem servidor DHCP. Flags no RA (`M` e `O`) indicam se DHCPv6 deve ser usado para endereço e/ou outras informações.
- **Prefixo padrão de LAN é /64.** Não por escassez, mas porque SLAAC depende disso. Links p2p usam /127.
- **Sem NAT por padrão** (embora NPTv6 exista). O modelo é endereçamento global fim a fim, com firewall fazendo o papel de controle.
- **Sem fragmentação em roteador.** Só a origem fragmenta; roteadores mandam ICMPv6 Packet Too Big. Isso torna o PMTUD (item 14) **obrigatório** e faz o bloqueio cego de ICMPv6 quebrar a rede.

**Dual-stack** é a realidade operacional: v4 e v6 simultâneos, com **Happy Eyeballs** (RFC 8305) no cliente tentando ambos em paralelo e usando o que responder primeiro.

No OVN, IPv6 é cidadão de primeira classe: há ND responder nativo, RA emitido pelo logical router, DHCPv6 stateless/stateful — tudo implementado como logical flows, sem broadcast e sem daemon por rede.

### 11. ARP, gratuitous ARP, proxy ARP — e NDP

**ARP** resolve IPv4 → MAC dentro de um mesmo domínio de broadcast:

1. Host A quer falar com `10.0.0.5`, mesma sub-rede. Não tem o MAC.
2. Envia **ARP Request** em broadcast: "quem tem 10.0.0.5? diga para 10.0.0.1".
3. Host B responde com **ARP Reply** em unicast.
4. A guarda em cache (`ip neigh`), com estados: REACHABLE, STALE, DELAY, PROBE, FAILED.

Ponto crítico: se o destino está **fora** da sub-rede, o host não faz ARP do destino — ele faz ARP do **gateway** e envia o frame com o MAC do gateway e o IP do destino final. Essa separação (MAC muda a cada hop, IP não muda) é o conceito de roteamento em uma frase.

**Gratuitous ARP (GARP)** — um ARP request/reply não solicitado anunciando o próprio par IP/MAC. Usos:

- Detecção de IP duplicado ao subir interface.
- **Failover de VIP**: quando um keepalived/VRRP move um IP para outro nó, ele envia GARP para que switches e hosts atualizem CAM e cache ARP imediatamente. Sem isso, o tráfego continua indo para o nó morto até o aging expirar.
- Migração de VM ao vivo: o hipervisor de destino emite GARP.

**Proxy ARP** — um roteador responde ARP por um IP que não é dele, atraindo o tráfego para si. Útil em casos específicos, mas quebra o modelo mental de sub-rede e costuma esconder problemas de design.

**ARP spoofing** — como não há autenticação, qualquer host pode responder por qualquer IP. Base de ataques MITM. Mitigações no mundo físico: Dynamic ARP Inspection, DHCP snooping. **No OVN, a mitigação é estrutural**: com `port_security` configurado, flows de ingress descartam qualquer ARP cujo SPA/SHA não case com o par IP/MAC registrado na logical switch port. Não há como forjar.

**NDP (IPv6)** faz o mesmo papel via ICMPv6 sobre multicast solicited-node (`ff02::1:ff00:0/104`), o que já reduz o custo: só os nós cujo endereço termina nos mesmos 24 bits processam a solicitação, em vez de todos.

### 12. Roteamento: tabela, longest prefix match, tipos de rota

Um roteador toma uma decisão por pacote:

1. Extrai o IP de destino.
2. Consulta a **FIB** procurando o prefixo mais específico que contém o destino — **longest prefix match**.
3. Encaminha ao **next-hop** pela interface de saída, reescrevendo o MAC de destino e decrementando o TTL.

Longest prefix match com exemplo. Tabela:

```
0.0.0.0/0        via 192.168.1.1
10.0.0.0/8       via 10.1.1.1
10.20.0.0/16     via 10.1.1.2
10.20.30.0/24    via 10.1.1.3
```

Destino `10.20.30.40` casa com as quatro entradas; vence o `/24`. Destino `10.20.99.1` casa com três; vence o `/16`. Destino `8.8.8.8` só casa com a default.

**Tipos de rota, por ordem natural de preferência:**

- **Connected** — sub-rede diretamente ligada a uma interface ativa. Aparece sozinha.
- **Local** — o /32 do próprio endereço da interface.
- **Estática** — configurada manualmente. Previsível, não reage a falhas (a menos que atrelada a BFD/track).
- **Dinâmica** — aprendida por OSPF/BGP.

Quando várias fontes oferecem a mesma rota, desempata a **distância administrativa** (conceito de fabricante: connected 0, estática 1, eBGP 20, OSPF 110, iBGP 200) e, dentro do mesmo protocolo, a **métrica**.

No Linux o modelo é mais rico: existem múltiplas **tabelas** de roteamento e **regras de policy routing** (`ip rule`) que escolhem a tabela por origem, marca de firewall (`fwmark`), interface de entrada etc. Isso é o alicerce de VRF (item 16) e de boa parte do que o OVN faz na borda.

```bash
ip route show table main
ip route get 8.8.8.8          # mostra a decisão real para um destino
ip rule show
```

### 13. Roteamento dinâmico: OSPF, BGP, ECMP

**Distance-vector vs link-state:**

- **Distance-vector** (RIP, EIGRP): cada roteador conhece apenas distâncias reportadas por vizinhos. "Roteamento por rumor". Simples, convergência lenta, sujeito a loops (mitigado por split horizon, poison reverse).
- **Link-state** (OSPF, IS-IS): cada roteador inunda o estado de seus links; todos constroem um mapa idêntico da topologia e rodam Dijkstra localmente. Convergência rápida, sem loop por construção, mas custa CPU e memória e exige design em áreas.

**OSPF** — IGP link-state, dentro de um domínio administrativo. Conceitos: área 0 (backbone) e áreas conectadas a ela, LSAs, DR/BDR em redes broadcast, custo inversamente proporcional à banda. Ótimo em campus; em datacenter moderno perdeu espaço para BGP.

**BGP** — protocolo de política, path-vector, o protocolo que faz a Internet funcionar. É o que mais importa para você.

- **AS (Autonomous System)** — domínio administrativo, identificado por ASN (32 bits hoje; `64512–65534` são privados de 16 bits).
- **eBGP** — entre ASes diferentes. TTL 1 por padrão (vizinhos diretos), next-hop reescrito para si.
- **iBGP** — dentro do mesmo AS. Regra fundamental: uma rota aprendida por iBGP **não é repassada** a outro peer iBGP (prevenção de loop), o que exigiria full-mesh de N² sessões. Solução: **route reflector** (um peer central que reflete) ou confederações.
- **Atributos**, na ordem de decisão simplificada: Weight (Cisco, local) → **Local Preference** (preferência de saída dentro do AS, maior vence) → rota local → **AS_PATH mais curto** → Origin → **MED** (sugestão ao AS vizinho de por onde entrar, menor vence) → eBGP sobre iBGP → menor custo IGP até o next-hop → menor router-ID.
- **Communities** — tags que carregam política (ex.: "não anuncie fora do meu AS" = `NO_EXPORT`). É como operadores implementam política em escala.

**Por que BGP em datacenter (RFC 7938):** em leaf-spine, cada leaf é seu próprio AS, cada spine é outro. eBGP entre eles dá: prevenção de loop por AS_PATH, política explícita, escalabilidade testada e um único protocolo para underlay e para EVPN. Muitos desenhos levam BGP até o **host** (FRR no servidor), com cada servidor anunciando os /32 dos seus serviços — o que elimina a necessidade de L2 estendido e MLAG.

**ECMP (Equal-Cost Multi-Path)** — múltiplos next-hops de custo igual, com o pacote atribuído por hash da 5-tupla. Propriedades importantes:

- Preserva a ordem dentro de um fluxo (crítico para TCP).
- Um fluxo único não usa mais de um caminho → **elephant flows** desbalanceiam. Mitigações: flowlet switching, hashing que inclui o header de encapsulamento, ou entropia no source port do túnel (é exatamente o que VXLAN/Geneve fazem, item 25).
- Mudança no conjunto de next-hops reembaralha o hash e pode quebrar conexões. Daí **consistent hashing / resilient ECMP**.

**BFD (Bidirectional Forwarding Detection)** — hellos em milissegundos, independentes do protocolo de roteamento, para detectar falha muito antes do timer do OSPF/BGP. Usado tanto no underlay quanto pelo OVN entre chassis para decidir failover de gateway.

### 14. ICMP, traceroute, PMTUD

**ICMP** não é "ping". É o canal de sinalização de erro do IPv4, e bloqueá-lo indiscriminadamente quebra a rede.

Tipos que importam:

| Tipo/Código | Nome | Por que importa |
|---|---|---|
| 8 / 0 | Echo Request/Reply | ping |
| 11 | Time Exceeded | TTL zerado — base do traceroute |
| 3/0, 3/1 | Destination Unreachable (net/host) | |
| 3/3 | Port Unreachable | resposta a UDP em porta fechada |
| 3/4 | **Fragmentation Needed** com DF set | **base do PMTUD** |
| 5 | Redirect | gateway avisando que há caminho melhor |

**Traceroute** explora o TTL: envia pacotes com TTL=1, 2, 3… Cada roteador que zera o TTL responde ICMP Time Exceeded, revelando seu endereço. Variações: UDP em portas altas (padrão Unix), ICMP Echo (Windows), TCP SYN para porta 80/443 (`tcptraceroute` — o único que atravessa firewalls que filtram UDP/ICMP). Interpretação: asteriscos em um hop intermediário costumam significar apenas que aquele roteador não gera ICMP, não que há perda — só a **última** linha reflete a conectividade fim a fim.

**PMTUD (Path MTU Discovery)** — o host marca DF (Don't Fragment) e envia pacotes de tamanho do MTU local. Se um roteador no caminho tem MTU menor, ele descarta e responde ICMP tipo 3 código 4 informando o MTU permitido. O host reduz e reenvia.

**PMTUD black hole** — o modo de falha mais insidioso da rede, e um que você **vai** encontrar em overlay:

1. Alguém bloqueia ICMP tipo 3 no firewall "por segurança".
2. O roteador com MTU menor descarta o pacote grande, envia ICMP, o ICMP é bloqueado.
3. O host nunca sabe. Fica retransmitindo pacotes grandes eternamente.
4. **Sintoma clássico:** handshake TCP funciona, ping funciona, requisições pequenas funcionam — mas transferências grandes travam. HTTPS falha no meio do handshake (certificado é grande). SSH conecta e congela no banner.

Mitigação em máquina intermediária: **MSS clamping** — reescrever o MSS no SYN para o valor que cabe (`iptables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu`). É o remédio universal em ambientes com túnel.

**Regra prática para overlay:** ou você aumenta o MTU do underlay para acomodar o encapsulamento (jumbo frames, 9000), ou reduz o MTU dos hosts/VMs (ex.: 1442 para Geneve sobre 1500). O OVN/Neutron faz a segunda via opção do DHCP; a primeira é sempre melhor por desempenho.

### 15. NAT: SNAT, DNAT, PAT, conntrack

NAT reescreve endereços em trânsito. Não é um protocolo, é uma violação deliberada do modelo fim a fim — mas é onipresente.

- **SNAT (Source NAT)** — reescreve o IP de origem. Uso: hosts privados saindo para a Internet. Quando muitos hosts compartilham um IP público, é preciso reescrever também a porta de origem: isso é **PAT / NAT overload / masquerade**. A tabela de tradução é indexada pela 5-tupla.
- **DNAT (Destination NAT)** — reescreve o IP de destino. Uso: port forwarding, publicar um serviço interno, load balancer.
- **1:1 NAT / floating IP** — um IP público mapeado a um privado, com DNAT na entrada e SNAT na saída. É exatamente o `dnat_and_snat` do OVN.
- **Hairpin NAT (NAT loopback)** — um host interno acessando o IP **público** do próprio serviço interno. Sem tratamento, o pacote sai, volta, e a resposta vem com IP errado (o cliente vê resposta do IP privado, não do público, e descarta). A solução é aplicar SNAT adicional no retorno. É um bug clássico de laboratório e de produção.

**Conntrack** é o que torna tudo isso possível: uma tabela de fluxos com estados (NEW, ESTABLISHED, RELATED, INVALID). NAT só funciona porque o kernel lembra da tradução para reverter no pacote de resposta. Isso também é o que dá *stateful firewall*: permitir ESTABLISHED/RELATED e negar NEW de fora.

```bash
conntrack -L                    # tabela ativa
sysctl net.netfilter.nf_conntrack_max
cat /proc/sys/net/netfilter/nf_conntrack_count
```

Estourar `nf_conntrack_max` derruba conexões novas com `nf_conntrack: table full, dropping packet` no dmesg. Em gateways de cloud é um limite real que precisa ser dimensionado.

Limitações que geram trabalho: NAT quebra protocolos que carregam IPs no payload (FTP ativo, SIP, H.323), exigindo **ALGs** ou helpers de conntrack; dificulta conexões entrante (daí STUN/TURN/ICE para P2P); e complica logging/atribuição (CGNAT exige registrar porta além de IP).

No OVN, NAT é declarado no `Logical_Router` e implementado por logical flows com conntrack no datapath do OVS. A escolha entre NAT **distribuído** (executado no hipervisor da VM, via `dnat_and_snat` com `external_mac`/`logical_port`) e **centralizado** (todo o tráfego passa pelo gateway chassis) é uma das decisões de arquitetura mais consequentes de um deployment — ver item 41.

### 16. VRF e route leaking

**VRF (Virtual Routing and Forwarding)** = múltiplas tabelas de roteamento independentes no mesmo dispositivo. Cada VRF tem seu próprio espaço de endereços, podendo inclusive usar os mesmos prefixos que outra VRF sem conflito. É a virtualização de L3, assim como VLAN é a virtualização de L2.

Uso: multi-tenancy (cada cliente com seu `10.0.0.0/8` próprio), separação de ambientes (prod/dev/gerência/DMZ) no mesmo hardware.

No Linux:

```bash
ip link add vrf-tenant-a type vrf table 100
ip link set vrf-tenant-a up
ip link set eth1 master vrf-tenant-a
ip route add default via 10.0.0.1 table 100
ip vrf exec vrf-tenant-a ping 10.0.0.5
```

**Route leaking** — importar seletivamente rotas de uma VRF para outra, normalmente via route targets do BGP/MPLS-VPN, ou no Linux com rotas estáticas cruzadas. Necessário quando tenants isolados precisam de acesso comum a serviços compartilhados (DNS, NTP, repositórios internos, monitoração).

**O paralelo direto com OVN:** um `Logical_Router` no OVN é conceitualmente uma VRF. Dois routers lógicos de tenants diferentes são tabelas de roteamento completamente independentes, podendo usar as mesmas faixas privadas. O "route leaking" entre eles se faz conectando ambos a um logical switch comum, com rotas estáticas, ou — entre availability zones — via **OVN-IC** e transit switch (item 45).
---

## Bloco 3 — Transporte e serviços

### 17. TCP em profundidade

TCP entrega um fluxo de bytes confiável, ordenado e com controle de fluxo e congestionamento sobre um IP que não garante nada.

**Header (20 bytes sem opções):** portas origem/destino, sequence number, acknowledgment number, data offset, flags, window, checksum, urgent pointer.

**Three-way handshake:**

```
Cliente                         Servidor
   |  SYN, seq=x                   |
   |------------------------------>|
   |  SYN-ACK, seq=y, ack=x+1      |
   |<------------------------------|
   |  ACK, ack=y+1                 |
   |------------------------------>|
```

Custo: 1 RTT antes de qualquer dado. Com TLS 1.2 são mais 2 RTTs; TLS 1.3 reduz para 1; QUIC funde tudo (item 18).

**Encerramento:** FIN → ACK → FIN → ACK (quatro vias, pois cada direção fecha independentemente — half-close). O lado que fecha primeiro entra em **TIME_WAIT** por 2×MSL (60s no Linux), para absorver pacotes atrasados e garantir a entrega do último ACK. Em um servidor que abre muitas conexões de saída curtas (proxy, load balancer), acumular centenas de milhares de sockets em TIME_WAIT esgota portas efêmeras. Tratamento correto: `net.ipv4.tcp_tw_reuse=1` (seguro, reutiliza no lado cliente), `SO_REUSEADDR`, ampliar `ip_local_port_range`, ou usar conexões persistentes. **Não** use `tcp_tw_recycle` (removido do kernel por quebrar NAT).

**Estados que você deve saber ler em `ss -tan`:**

- `SYN-SENT` acumulando → não chega resposta: firewall dropando, rota ausente, servidor inacessível.
- `SYN-RECV` acumulando → backlog cheio ou SYN flood.
- `CLOSE_WAIT` acumulando → **bug de aplicação**: o peer fechou, mas seu processo não chamou `close()`. Vazamento de descritores.
- `TIME_WAIT` em massa → normal em cliente de alto volume, ver acima.
- `FIN_WAIT_2` preso → o outro lado não fecha.

**Confiabilidade:** cada byte tem um número de sequência; o receptor reconhece cumulativamente. Perda é detectada por timeout (RTO, calculado a partir de RTT médio e variância) ou por **3 ACKs duplicados** (fast retransmit). **SACK** permite dizer exatamente quais blocos chegaram, evitando retransmitir o que já foi recebido — essencial em redes com perda.

**Controle de fluxo** (proteger o receptor): campo Window anuncia quanto o receptor aceita. **Window scaling** (opção) multiplica isso para redes de alto BDP — sem ele, o teto de 64 KB limita drasticamente o throughput em links de alta latência.

**Controle de congestionamento** (proteger a rede) — conceitos distintos do anterior, e frequentemente confundidos em entrevista:

- **Slow start** — cwnd cresce exponencialmente até o ssthresh ou até haver perda.
- **Congestion avoidance** — crescimento linear (AIMD: aumento aditivo, redução multiplicativa).
- **CUBIC** — padrão do Linux, baseado em perda, crescimento cúbico, recupera rápido em links de alta banda.
- **BBR** — modela banda e RTT mínimo em vez de reagir a perda. Muito melhor em links com perda não-congestiva e em bufferbloat. Padrão em vários serviços de larga escala.
- **DCTCP** — usa ECN para reagir *antes* da perda. Padrão em datacenter, exige suporte nos switches.

**Bandwidth-Delay Product:** `BDP = banda × RTT`. Um link de 10 Gbps com 30 ms de RTT tem BDP ≈ 37,5 MB. Se o buffer do socket for menor, você nunca satura o link, por melhor que seja a rede. É a explicação de "o link é de 10G mas só consigo 200 Mbps": quase sempre buffer ou janela, não a rede.

### 18. UDP e QUIC

**UDP** — header de 8 bytes (portas, tamanho, checksum). Sem conexão, sem ordenação, sem retransmissão, sem controle de congestionamento. Vantagens: latência mínima, sem estado, suporta multicast/broadcast, e deixa a aplicação decidir a semântica.

Usos: DNS, DHCP, NTP, SNMP, RTP (voz/vídeo), VXLAN/Geneve (encapsulamento!), QUIC.

Cuidado operacional: como não há handshake, UDP é o vetor preferido de **amplificação em DDoS** (DNS, NTP, memcached) — pequena requisição com IP de origem forjado gera resposta enorme para a vítima.

**QUIC** (RFC 9000) — transporte sobre UDP que reimplementa confiabilidade, controle de congestionamento e criptografia em espaço de usuário:

- **Handshake de 1 RTT** (0-RTT em retomada), fundindo transporte e TLS 1.3.
- **Sem head-of-line blocking** entre streams: perda em um stream não bloqueia os outros (problema real do HTTP/2 sobre TCP).
- **Connection ID** independente da 5-tupla → a conexão sobrevive a mudança de IP (celular saindo do Wi-Fi para 4G).
- **Evolução no espaço de usuário**, sem depender de atualização de kernel ou de middleboxes.

Implicação para rede: QUIC é **opaco** a middleboxes. Firewalls L7, otimizadores de WAN e ferramentas de inspeção que dependiam de ler o header TCP perdem visibilidade. Base do HTTP/3.

### 19. Sockets, portas, 5-tupla

Uma conexão é identificada unicamente pela **5-tupla**: `(protocolo, IP origem, porta origem, IP destino, porta destino)`. É a chave de conntrack, de hashing ECMP, de flows do OVS e de regras de load balancer.

Faixas de porta (IANA): 0–1023 well-known (exigem privilégio), 1024–49151 registradas, 49152–65535 dinâmicas/efêmeras. No Linux a faixa efêmera real é `net.ipv4.ip_local_port_range` (padrão 32768–60999) — ~28k portas por par (IP origem, IP destino). Isso significa que um proxy pode esgotar portas ao falar com um único backend, e é por isso que SNAT em escala exige múltiplos IPs de saída.

`listen(fd, backlog)` cria duas filas: a de **SYN** (handshakes incompletos, protegida por SYN cookies quando estoura) e a de **accept** (conexões prontas esperando `accept()`). Overflow da segunda aparece em `netstat -s` como *listen queue overflows* e é uma causa comum de latência em picos.

### 20. DHCP

**DORA** — o fluxo de quatro mensagens:

1. **Discover** — cliente, sem IP, envia broadcast (`0.0.0.0` → `255.255.255.255`, UDP 68 → 67).
2. **Offer** — servidor oferece um IP.
3. **Request** — cliente pede formalmente aquele IP (broadcast, para que os outros servidores saibam que não foram escolhidos).
4. **Ack** — servidor confirma e entrega o lease com as opções.

**Opções** relevantes: 1 (máscara), 3 (gateway), 6 (DNS), 12 (hostname), 15 (domínio), 26 (**MTU** — é assim que o Neutron/OVN ajusta a MTU das VMs para caber no encapsulamento), 51 (lease time), 53 (tipo de mensagem), 82 (relay agent info), 121/249 (rotas estáticas classless — é assim que a rota para `169.254.169.254` de metadata chega à VM).

**Lease** — o cliente renova em T1 (50% do lease, unicast ao servidor) e, se falhar, em T2 (87,5%, broadcast). Lease curto = mais tráfego, recuperação rápida de endereços; longo = o contrário.

**Relay (IP helper)** — como Discover é broadcast, ele não cruza roteadores. O relay em cada sub-rede encaminha em unicast ao servidor central, preenchendo `giaddr` para que o servidor saiba de qual sub-rede alocar. A **option 82** acrescenta identificação de circuito/porta física, permitindo políticas por porta e rastreabilidade.

**Segurança:** um servidor DHCP rogue distribui gateway falso e captura todo o tráfego. Mitigação física: **DHCP snooping** no switch, marcando portas confiáveis e construindo uma tabela de bindings que alimenta Dynamic ARP Inspection e IP Source Guard.

**No OVN isso muda de natureza.** Não existe servidor DHCP nem relay. O `Logical_Switch_Port` tem `dhcpv4_options` apontando para um registro com as opções; o `ovn-northd` gera logical flows que **respondem ao Discover/Request diretamente no hipervisor de origem**, com a ação `put_dhcp_opts`. O broadcast nunca sai do host. Não há servidor para falhar, nem rogue possível, nem relay para configurar. Esse é um dos exemplos mais claros de "serviço de rede virou tabela de fluxos".

### 21. DNS

Hierarquia: raiz (`.`) → TLD (`.com`, `.br`) → domínio (`exemplo.com`) → subdomínios.

**Tipos de servidor:**

- **Recursivo/resolver** — o que o cliente consulta; percorre a hierarquia por ele e faz cache.
- **Autoritativo** — detém os dados de uma zona; responde apenas pelo que é seu.
- **Forwarder** — repassa a outro recursivo.

**Resolução de `www.exemplo.com.br`:** resolver consulta a raiz → recebe referral para os servidores de `.br` → consulta `.br` → referral para `exemplo.com.br` → consulta o autoritativo → resposta autoritativa. Depois tudo é cacheado conforme o TTL de cada registro.

**Registros essenciais:**

| Tipo | Função |
|---|---|
| A / AAAA | nome → IPv4 / IPv6 |
| CNAME | alias; não pode coexistir com outros registros no mesmo nome, nem estar no ápice da zona |
| MX | servidor de e-mail, com prioridade |
| NS | delegação de zona |
| SOA | metadados da zona: serial, refresh, retry, expire, **TTL negativo** |
| PTR | IP → nome (zona `in-addr.arpa` / `ip6.arpa`) |
| TXT | texto livre: SPF, DKIM, verificações de domínio |
| SRV | serviço + porta + prioridade + peso |
| CAA | quais CAs podem emitir certificado para o domínio |

**TTL** é a alavanca operacional mais importante: antes de uma migração, reduza o TTL com antecedência (ex.: de 3600 para 60) para que a mudança propague rápido; depois restaure. Esquecer isso transforma um corte de 5 minutos em uma hora de inconsistência.

**Cache negativo** — respostas NXDOMAIN também são cacheadas, com o TTL do campo minimum do SOA. Explica o clássico "criei o registro mas ainda não resolve".

**Split-horizon / views** — respostas diferentes conforme a origem da consulta (IP interno para clientes internos, público para externos).

**No OVN:** há DNS interno via `put_dns_opts`, servido pelos mesmos logical flows, resolvendo nomes de portas lógicas dentro do tenant sem infraestrutura dedicada. Mesmo padrão do DHCP.

### 22. TLS e HTTP

**TLS 1.3 handshake** (1 RTT):

1. **ClientHello** — versões suportadas, cipher suites, **SNI** (nome do host desejado, em claro) e key share.
2. **ServerHello** — parâmetros escolhidos, key share; a partir daí o resto do handshake já vai cifrado, incluindo o certificado.
3. Verificação do certificado pela cadeia até uma CA raiz confiável, checagem de validade, nome e revogação (OCSP stapling).

Diferenças de TLS 1.2: menos round-trips, suites inseguras removidas, forward secrecy obrigatório, e `0-RTT` para retomada (com risco de replay em requisições não-idempotentes).

**SNI** é o que permite virtual hosting em HTTPS e o que permite um load balancer L7 rotear por nome sem descriptografar — e é também o que redes de censura inspecionam. **ECH** (Encrypted Client Hello) resolve isso cifrando o SNI.

**HTTP:**

- **HTTP/1.1** — texto, uma requisição por conexão de cada vez; pipelining nunca funcionou bem. Navegadores abriam 6 conexões por host para compensar.
- **HTTP/2** — binário, multiplexação de streams sobre uma conexão TCP, compressão de headers (HPACK), server push (obsoleto na prática). Sofre **head-of-line blocking no TCP**: uma perda trava todos os streams.
- **HTTP/3** — sobre QUIC; resolve o HOL blocking, handshake mais rápido, migração de conexão.

Para quem trabalha com rede, o essencial é: menos conexões e mais multiplexação significa que o balanceamento **por conexão** (L4) distribui pior do que antes — um cliente pesado fica preso num backend. É um dos argumentos para LB L7 em ambientes com gRPC/HTTP2.

### 23. Load balancing

**L4 (transporte)** — decide pela 5-tupla, sem olhar o payload. Rápido, barato, funciona com qualquer protocolo. É o que fazem IPVS, o LB do OVN, e o `kube-proxy`.

**L7 (aplicação)** — termina a conexão, lê o HTTP, roteia por path/host/header, faz retry, reescrita, TLS termination. Mais caro, muito mais flexível. HAProxy, Envoy, NGINX.

**Modos de operação:**

- **Proxy / NAT mode** — o LB reescreve destino (e geralmente origem). Simples; o backend vê o IP do LB, exigindo `X-Forwarded-For` ou PROXY protocol para preservar o IP do cliente. O tráfego de volta passa pelo LB.
- **DSR (Direct Server Return)** — o LB só reescreve o MAC de destino; o backend responde direto ao cliente, com o VIP configurado em loopback. Elimina o LB do caminho de retorno, o que importa quando a resposta é muito maior que a requisição (vídeo, download). Exige que os backends estejam no mesmo L2 e uma configuração de ARP cuidadosa (`arp_ignore`/`arp_announce`).
- **Maglev / consistent hashing** — hashing que minimiza remapeamento quando o conjunto de backends muda, preservando conexões existentes. Essencial em LB distribuído sem estado compartilhado.

**Algoritmos:** round-robin, weighted RR, least-connections, source-hash (afinidade), consistent hash, EWMA/latência.

**Health check** — ativo (o LB sonda: TCP connect, HTTP GET em `/healthz`) ou passivo (observa falhas reais). Detalhes que separam amador de profissional: distinguir *liveness* de *readiness*, evitar flapping com thresholds de subida/descida, e não deixar o health check derrubar todos os backends simultaneamente (outlier detection com mínimo garantido).

**No OVN**, o load balancer é uma tabela no NB DB associando VIP:porta a uma lista de backends, aplicada a um logical switch ou logical router. A implementação é feita com `ct_lb` — o datapath usa **conntrack** para escolher um backend na primeira conexão e manter a afinidade nas seguintes. Como isso roda em cada hipervisor, o balanceamento é **distribuído**: não existe um appliance central por onde todo o tráfego passe. É a mesma ideia do `kube-proxy`/IPVS, implementada no datapath do OVS.
---

## Bloco 4 — Overlay e Linux networking

### 24. Por que overlay existe

Três pressões concretas, todas surgidas com virtualização em escala:

1. **Limite de 4094 VLANs.** Um provedor com dez mil tenants não cabe em 12 bits de VID.
2. **Mobilidade.** Uma VM migrada para outro rack precisa manter IP e MAC. Sem overlay, isso exige estender a mesma VLAN por todo o datacenter — um domínio de broadcast gigantesco, com STP, tabelas CAM estouradas e falhas correlacionadas.
3. **Acoplamento entre rede física e lógica.** Criar uma rede de tenant exigia configurar VLANs em switches físicos. Não é automatizável em velocidade de API, e coloca o time de rede como gargalo de cada provisionamento.

**A solução:** desacoplar completamente. O underlay vira uma malha L3 simples, estável e roteada (leaf-spine com ECMP), sem saber nada de tenants. A rede lógica é construída por **encapsulamento** entre hipervisores, controlada por software. Criar uma rede de tenant vira uma chamada de API que escreve num banco, sem tocar em nenhum switch.

O custo: overhead de bytes, uma camada a mais de troubleshooting, e uma nova classe de problema de MTU.

### 25. Encapsulamentos: VXLAN, Geneve, GRE, STT

**VXLAN (RFC 7348)** — MAC-in-UDP. Header de 8 bytes com um **VNI de 24 bits** → ~16,7 milhões de segmentos.

```
[ Eth externo | IP externo | UDP (dst 4789) | VXLAN (VNI) | Eth interno | IP interno | payload ]
     14            20            8               8             14           20
```

Overhead total sobre IPv4: 50 bytes. MTU interno resultante com underlay de 1500: **1450**.

Duas decisões de design importantes:

- **Usar UDP** não é para confiabilidade — é para que switches intermediários façam hashing ECMP normalmente. A **porta de origem UDP é derivada de um hash do pacote interno**, gerando entropia: fluxos diferentes entre o mesmo par de hipervisores tomam caminhos diferentes no spine. Sem isso, todo o tráfego entre dois hosts colapsaria num único caminho.
- **VXLAN não define control plane.** O RFC original usa multicast no underlay para aprender MACs — inviável na maioria dos datacenters. Daí as duas alternativas reais: EVPN (item 27) ou um controlador SDN (o caminho do OVN).

**Geneve (RFC 8926)** — o encapsulamento que o OVN usa por padrão. Mesma ideia (UDP, porta 6081, VNI de 24 bits), mas com **TLVs extensíveis** no header: campos de tamanho variável que carregam metadados arbitrários. Overhead base ~50 bytes, maior com opções.

Por que isso é decisivo para o OVN: o OVN precisa transportar, junto com o pacote, **qual é o datapath lógico, qual foi a porta lógica de ingresso e qual é a porta lógica de egresso**. Com VXLAN só há o VNI (24 bits), insuficiente. Com Geneve, o `ovn-controller` grava essas informações em TLVs, e o hipervisor de destino pode retomar o pipeline lógico exatamente no ponto certo, sem reclassificar o pacote. Isso é o que permite ao OVN implementar ACLs e pipeline de egresso corretamente no host remoto. **É a resposta certa para "por que o OVN usa Geneve e não VXLAN".**

**GRE (RFC 2784)** — encapsulamento genérico sobre IP (protocolo 47), sem portas. Simples e antigo, mas sem entropia para ECMP (a menos que se use GRE com key + hashing específico) e frequentemente bloqueado por firewalls/clouds que só passam TCP/UDP.

**STT (Stateless Transport Tunneling)** — emula um header TCP para explorar o offload TSO das NICs, ganhando muito desempenho em hardware antigo. Praticamente obsoleto: firewalls odeiam, e as NICs modernas já fazem offload de VXLAN/Geneve nativamente.

**Impacto no MTU — a conta que você precisa saber fazer:**

| Underlay MTU | Encapsulamento | MTU interno |
|---|---|---|
| 1500 | VXLAN/IPv4 | 1450 |
| 1500 | Geneve/IPv4 (sem opções) | 1442–1450 |
| 9000 | Geneve | 8942+ (sem problema prático) |

**Recomendação profissional:** configure jumbo frames (9000) no underlay inteiro. É a única solução que não penaliza desempenho nem gera black holes. Se não for possível, ajuste o MTU das instâncias via opção 26 do DHCP e aplique MSS clamping na borda.

### 26. Underlay, overlay, VTEP

- **Underlay** — a rede física roteada. Endereços dos hipervisores, spines, leafs. Deve ser simples, estável, com ECMP e sem estado de tenant. Idealmente você quase nunca a modifica.
- **Overlay** — as redes lógicas construídas por encapsulamento sobre o underlay. Mudam o tempo todo, por API.
- **VTEP (VXLAN Tunnel Endpoint)** — o ponto onde o encapsulamento começa e termina. Pode ser software (o OVS em cada hipervisor — caso do OVN) ou hardware (um switch ToR capaz de VXLAN, usado para integrar servidores bare-metal ao overlay; o OVN fala com esses via o `hardware_vtep` schema e o `ovn-controller-vtep`).

O modelo mental correto: **o underlay enxerga apenas conversas entre IPs de hipervisores.** Um `tcpdump` no spine mostra tráfego UDP 6081 entre hosts, nada de tenant. Isso é ótimo para estabilidade e péssimo para troubleshooting ingênuo — daí a importância das ferramentas do Bloco 6.

### 27. EVPN/BGP como control plane — e como o OVN difere

O overlay resolve o *data plane*. Falta responder: **como cada VTEP sabe atrás de qual outro VTEP está um determinado MAC/IP?**

**EVPN (BGP Ethernet VPN, RFC 7432/8365)** é a resposta padrão da indústria de rede: usa BGP com a AFI/SAFI L2VPN EVPN para distribuir informação de alcance. Tipos de rota relevantes:

- **Type 2** — MAC/IP advertisement. "O MAC X e o IP Y estão no VTEP Z."
- **Type 3** — Inclusive Multicast. Constrói a lista de VTEPs de um segmento para replicar BUM (Broadcast, Unknown unicast, Multicast) em **ingress replication**, sem multicast no underlay.
- **Type 5** — IP Prefix. Rotas L3 entre VRFs, para roteamento inter-subnet.

Vantagens: padrão aberto, interopera entre fabricantes, integra hardware e software, escala bem, e permite **ARP suppression** (o VTEP local responde ARP com base no que aprendeu por BGP, eliminando flooding).

**Como o OVN difere.** O OVN **não usa BGP como control plane interno**. Ele usa um modelo de controlador com banco de dados:

| | EVPN | OVN |
|---|---|---|
| Control plane | BGP distribuído entre VTEPs | `ovn-northd` + Southbound DB, com `ovn-controller` em cada host |
| Fonte de verdade | anúncios trocados entre pares | banco de dados declarativo (NB DB) |
| Aprendizado | dinâmico, por anúncio | **nenhum** — a topologia é conhecida a priori pelo CMS |
| Programação do dataplane | FIB/MAC table do VTEP | logical flows → OpenFlow no OVS |
| Integração externa | nativa com roteadores físicos | via gateway, VTEP de hardware, ou `ovn-bgp-agent` |

A diferença conceitual: EVPN **descobre** onde as coisas estão; o OVN **já sabe**, porque o CMS (OpenStack, Kubernetes) criou a porta lógica e registrou onde ela foi ligada. Por isso o OVN pode suprimir ARP, DHCP e DNS integralmente — ele não precisa aprender nada. Em compensação, o OVN depende de um banco central (com HA via RAFT) e escala de forma diferente.

Os dois se encontram na borda: o `ovn-bgp-agent` anuncia por BGP, para a rede física, os IPs que vivem dentro do OVN — é assim que se expõe uma cloud OVN sem depender de NAT centralizado.

### 28. Internals de rede no Linux

Esta é a base sobre a qual OVS e OVN funcionam. Dominar isso te diferencia de quem só sabe usar a API.

**Network namespaces** — instâncias isoladas da pilha de rede: interfaces, tabelas de roteamento, regras de firewall, tabela de conntrack, tudo próprio.

```bash
ip netns add ns1
ip netns exec ns1 ip addr
ip netns exec ns1 ip link set lo up
```

É o primitivo que torna containers possíveis. Um pod Kubernetes é um conjunto de containers compartilhando **um** netns.

**veth pair** — um cabo virtual com duas pontas; o que entra numa sai na outra. Serve para conectar um namespace a outro ou a uma bridge.

```bash
ip link add veth0 type veth peer name veth1
ip link set veth1 netns ns1
```

**Linux bridge** — um switch L2 em software com MAC learning e STP opcional. Funcional, mas sem programabilidade — não há como expressar "encaminhe por este caminho se a porta TCP for X". Essa limitação é exatamente o que o OVS existe para resolver.

**tap / tun** — interfaces virtuais ligadas a um processo de espaço de usuário. `tap` trabalha com frames Ethernet (L2) e é o que o QEMU/KVM usa para dar uma NIC à VM; `tun` trabalha com pacotes IP (L3) e é o que VPNs usam.

**macvlan / ipvlan** — dão a um container um endereço diretamente no segmento físico, sem bridge. `macvlan` atribui um MAC próprio (mas muitos switches limitam MACs por porta, e Wi-Fi não suporta); `ipvlan` compartilha o MAC do pai e distingue por IP (modo L2) ou roteia (modo L3).

**netfilter / iptables / nftables** — o framework de filtragem. Hooks no caminho do pacote: `PREROUTING` → (decisão de roteamento) → `INPUT` ou `FORWARD` → `POSTROUTING`. Tabelas: `filter`, `nat`, `mangle`, `raw`. `nftables` é o sucessor unificado, com melhor desempenho e sintaxe. Ponto importante: **`iptables` e as flows do OVS coexistem mas são mundos distintos** — um pacote que sai do datapath do OVS para o kernel pode passar por netfilter, e essa interação é fonte frequente de confusão em troubleshooting.

**conntrack** — já visto no item 15. Vale reforçar que **o OVS usa a mesma tabela de conntrack do kernel** através da ação `ct()`, com **zonas** para isolar tenants diferentes. É o que dá ao OVN ACLs stateful e load balancing com afinidade.

**tc (traffic control)** — qdiscs para shaping, policing e priorização (`fq_codel`, `htb`, `tbf`, `fq`). Também é o ponto de ancoragem do **tc flower**, usado para hardware offload das flows do OVS (item 50).

**eBPF / XDP** — programas verificados rodando no kernel. XDP intercepta o pacote no driver, antes de qualquer alocação de `skb` — o caminho mais rápido possível no kernel, usado para DDoS mitigation e load balancing de altíssimo volume (Katran). eBPF é a base do Cilium, que é a alternativa arquitetural direta ao OVN no espaço Kubernetes: mesma finalidade, abordagem oposta (programas eBPF por endpoint vs pipeline de flows do OVS).

**Ferramentas de inspeção que você deve usar sem pensar:**

```bash
ip -br addr                 # resumo de endereços
ip -s link                  # contadores por interface
ip route get <ip>           # decisão real de roteamento
ip neigh                    # cache ARP/ND
ss -tanp                    # sockets com processo
bridge fdb show             # tabela MAC de bridges Linux
nstat / netstat -s          # contadores de protocolo
ethtool -S <iface>          # contadores do driver/NIC
```
---

## Bloco 5 — Open vSwitch

### 29. Arquitetura do OVS

O OVS é um switch virtual programável, multi-camada. Componentes:

```
           ovs-vsctl / ovs-ofctl / ovn-controller
                          |
                  +-------+--------+
                  |                |
            ovsdb-server      ovs-vswitchd   (userspace)
                  |                |
        conf.db (disco)      datapath (kernel: openvswitch.ko, ou userspace/DPDK)
```

- **`ovsdb-server`** — mantém a configuração persistente em `conf.db`, servida pelo protocolo OVSDB (item 33). Responde a `ovs-vsctl` e a clientes remotos.
- **`ovs-vswitchd`** — o cérebro. Mantém as tabelas OpenFlow, decide o que fazer com pacotes que o datapath não sabe tratar, e instala regras de cache no datapath.
- **Datapath** — o caminho rápido. Duas variantes:
  - **Kernel (`openvswitch.ko`)** — padrão, usa o stack do kernel, integra com conntrack e tc.
  - **Userspace / DPDK (`netdev`)** — poll mode drivers, contorna o kernel completamente, com CPUs dedicadas em busy-poll. Muito maior throughput e latência previsível; custo: CPUs queimadas 100%, complexidade de NUMA/hugepages, perda da integração natural com ferramentas do kernel.

**A distinção fundamental do OVS:** o encaminhamento não é aprendido, é **programado**. Enquanto uma bridge Linux aprende MACs observando tráfego, o OVS recebe regras (match → action) de um controlador. Isso é literalmente a definição de SDN: separação entre control plane (quem decide) e data plane (quem encaminha).

### 30. OpenFlow: match-action e pipeline

Uma flow é `prioridade + match + ações`, organizada em **tabelas** numeradas.

**Campos de match** — praticamente qualquer coisa: porta de entrada, MAC origem/destino, EtherType, VLAN, IP origem/destino (com máscara), protocolo, portas L4, flags TCP, ICMP type/code, estado de conntrack, campos de metadata e registradores.

**Ações:**

| Ação | Efeito |
|---|---|
| `output:N` | envia pela porta N |
| `drop` | descarta (lista de ações vazia) |
| `NORMAL` | delega ao comportamento de switch L2 tradicional |
| `mod_dl_dst`, `mod_nw_src`… | reescreve campos |
| `push_vlan` / `pop_vlan` | tagueamento |
| `ct(...)` | submete a conntrack |
| `resubmit(,N)` | continua o processamento na tabela N |
| `learn(...)` | instala dinamicamente uma nova flow |
| `controller` | envia o pacote ao controlador (packet-in) |
| `group:N` | usa um group (select para ECMP, failover) |

**Pipeline multi-tabela** é o que permite composição. Em vez de uma tabela gigante com produto cartesiano de condições, você separa estágios: tabela 0 classifica a porta de entrada, tabela 10 aplica ACL, tabela 20 decide L2, tabela 30 faz saída. O `resubmit` liga os estágios. Registradores (`reg0`–`reg15`, `metadata`) carregam estado entre tabelas.

**Prioridade** desempata: dentro de uma tabela, vence a flow de maior prioridade entre as que casam. Mesma prioridade com matches sobrepostos é comportamento indefinido — um erro de design.

**Groups:**
- `select` — escolhe um bucket por hash → ECMP e load balancing.
- `fast failover` — usa o primeiro bucket com liveness (BFD) ativa → failover em milissegundos sem envolver o controlador.
- `all` — replica para todos os buckets → é assim que se faz broadcast/multicast.

**Como isso reaparece no OVN:** os *logical flows* do OVN (item 37) são exatamente essa ideia, um nível acima — com portas lógicas em vez de físicas. O `ovn-controller` compila logical flows em flows OpenFlow reais nesta estrutura.

### 31. Caminho do pacote: megaflow, upcall, revalidator

O ponto de desempenho mais importante do OVS, e pergunta frequente em entrevista.

O datapath do kernel **não** conhece OpenFlow. Ele só tem um cache. O caminho é:

1. Pacote chega ao datapath.
2. **Exact-match cache (EMC)** — cache pequeno indexado pela 5-tupla completa. Acerto → encaminha imediatamente. É o caminho mais rápido.
3. **Megaflow cache** — entradas com **máscara**, cobrindo classes inteiras de pacotes (ex.: "qualquer TCP para 10.0.0.0/24" em vez de uma entrada por fluxo). Acerto → encaminha.
4. **Miss → upcall.** O pacote é enviado ao `ovs-vswitchd` em userspace, que percorre o pipeline OpenFlow completo, calcula as ações **e a máscara mais ampla possível** que produz o mesmo resultado, e instala uma megaflow no datapath. O pacote segue.
5. **Revalidator** — threads que periodicamente reavaliam as entradas do cache contra as tabelas OpenFlow atuais, removendo ou corrigindo as que ficaram inválidas (porque uma flow mudou) ou ociosas.

Consequências práticas:

- **O primeiro pacote de um fluxo novo é caro** (microssegundos a centenas de microssegundos); os seguintes são baratos. Cargas com altíssima taxa de conexões novas e curtas sofrem mais com upcalls do que com throughput.
- **Wildcarding importa muito.** Se as regras forçam match em campos muito específicos (ex.: portas L4 individuais), cada fluxo gera uma megaflow própria, o cache explode e a taxa de upcall dispara. Isso é chamado de *megaflow explosion* e é uma causa real de degradação com ACLs mal projetadas.
- **Sintomas de problema:** CPU alta em `ovs-vswitchd` (e não no datapath), contador de `flow_misses` subindo, latência no primeiro pacote.

```bash
ovs-dpctl show                       # estatísticas do datapath, hit/miss
ovs-appctl dpctl/dump-flows          # megaflows instaladas
ovs-appctl upcall/show               # threads de handler/revalidator
ovs-appctl coverage/show | grep -i upcall
```

### 32. Bridges, portas internas, patch ports, tunnel ports

Tipos de porta que você vai encontrar num host OVN:

- **Porta física** — uma NIC (`eth0`) adicionada à bridge.
- **Porta interna (`type=internal`)** — uma interface virtual criada pelo próprio OVS, que aparece no host e permite dar um IP à bridge.
- **Patch port** — par de portas que liga **duas bridges do mesmo OVS**, sem passar pelo kernel. Barato e comum: é assim que `br-int` se conecta a `br-ex` em deployments OVN/OpenStack.
- **Tunnel port (`type=geneve|vxlan|gre`)** — porta virtual com `remote_ip` configurado; qualquer pacote enviado a ela é encapsulado. O OVN cria essas portas automaticamente, uma por chassis remoto.
- **Porta `tap`/`vhost-user`** — conecta a VM (QEMU) ou o container.

**Layout típico de um hipervisor OVN:**

```
      VM1 tap        VM2 tap
        |              |
   +----+--------------+----+
   |        br-int          |   <- bridge de integração, gerida pelo ovn-controller
   +---+----------------+---+
       | patch          | geneve tunnels (ovn-0a0b0c...)
   +---+----+           |
   |  br-ex |           +--> para os outros chassis
   +---+----+
       |
      eth1 (uplink físico)
```

**`br-int`** é sagrada: **nunca** se editam flows nela manualmente, pois o `ovn-controller` é dono e vai sobrescrever. Configuração só via NB DB.

### 33. OVSDB: schema, protocolo, transações, monitors, RAFT

O OVSDB (RFC 7047) é o que diferencia o OVS de um switch programável comum, e o modelo que o OVN herda.

- **Schema em JSON**: tabelas, colunas, tipos, chaves e referências. O `conf.db` do OVS, e os `ovnnb_db.db` / `ovnsb_db.db` do OVN, são todos bancos OVSDB com schemas diferentes.
- **Protocolo JSON-RPC** com operações `transact`, `monitor`, `monitor_cond`, `lock`.
- **Transações atômicas** com verificação de condições — permitindo múltiplos clientes escrevendo com segurança.
- **Monitors** — é o recurso central: um cliente se inscreve em tabelas/colunas e o servidor **envia updates incrementais** quando algo muda. Não há polling. É assim que cada `ovn-controller` fica sabendo, em tempo real, das mudanças que lhe dizem respeito.
- **`monitor_cond` / conditional monitoring** — o cliente declara uma condição e recebe **apenas as linhas relevantes**. No OVN isso é decisivo para escala: cada chassis só recebe os logical flows dos datapaths que ele realmente hospeda, em vez do banco inteiro.
- **Clustering RAFT** — desde o OVS 2.9, o `ovsdb-server` suporta consenso RAFT com 3 ou 5 nós. Um líder aceita escritas, seguidores replicam. É assim que se faz HA do NB e SB DB do OVN. Regra: número ímpar de nós, e entenda que perder o quorum congela escritas (a rede continua encaminhando, mas não aceita mudanças).

```bash
ovsdb-client list-dbs
ovsdb-client dump unix:/var/run/openvswitch/db.sock
ovs-appctl -t ovsdb-server ovsdb-server/list-dbs
ovs-appctl -t ovnsb_db cluster/status OVN_Southbound
```

### 34. Ferramental do OVS

Quatro comandos, quatro propósitos distintos — confundi-los é sinal de quem não usou de verdade:

| Comando | Fala com | Para quê |
|---|---|---|
| `ovs-vsctl` | `ovsdb-server` | **configuração**: criar bridges, portas, opções |
| `ovs-ofctl` | `ovs-vswitchd` (OpenFlow) | **flows**: dump, add, del, trace |
| `ovs-appctl` | qualquer daemon (unixctl) | **introspecção e debug**: estado interno, logs, caches |
| `ovs-dpctl` | datapath | megaflows e estatísticas do caminho rápido |

Comandos que você deve ter na ponta dos dedos:

```bash
ovs-vsctl show                              # visão geral
ovs-vsctl list-br / list-ports br-int
ovs-vsctl get Interface tap0 ofport         # número OpenFlow da porta
ovs-vsctl list Open_vSwitch .               # config global, incl. external_ids

ovs-ofctl -O OpenFlow15 dump-flows br-int   # flows reais
ovs-ofctl dump-ports-desc br-int            # mapa de portas
ovs-appctl ofproto/trace br-int <fluxo>     # simula o caminho de um pacote

ovs-appctl dpif/show
ovs-appctl fdb/show br-ex                   # tabela MAC (em bridges com NORMAL)
ovs-appctl vlog/set dbg                     # aumentar verbosidade
```

**`ofproto/trace` é a ferramenta mais valiosa do conjunto.** Você descreve um pacote hipotético e o OVS mostra tabela por tabela qual flow casou e qual ação foi tomada, até a decisão final. Combinado com o `ovn-trace` (item 47), resolve a maioria dos problemas de "por que esse pacote não chega".
---

## Bloco 6 — OVN

### 35. Arquitetura: NB, northd, SB, ovn-controller

O OVN é a camada de virtualização de rede construída sobre o OVS. A arquitetura tem uma simetria que, uma vez entendida, explica quase tudo:

```
        CMS (OpenStack Neutron / ovn-kubernetes / Magalu Cloud ...)
                             |
                     [ Northbound DB ]        <- intenção: "quero um switch com 3 portas"
                             |
                      ovn-northd              <- tradutor
                             |
                     [ Southbound DB ]        <- implementação: logical flows + bindings
                             |
        +--------------------+--------------------+
        |                    |                    |
  ovn-controller       ovn-controller       ovn-controller     <- um por chassis
        |                    |                    |
      OVS/br-int          OVS/br-int          OVS/br-int
```

**Northbound DB (`ovnnb_db`)** — a API. Contém apenas **intenção declarativa**, em termos que fazem sentido para quem pensa em rede: `Logical_Switch`, `Logical_Switch_Port`, `Logical_Router`, `ACL`, `Load_Balancer`, `NAT`, `DHCP_Options`, `Address_Set`, `Port_Group`. Nada aqui menciona hipervisor, túnel ou OpenFlow. É o que o CMS escreve.

**`ovn-northd`** — daemon central e sem estado próprio (todo o estado está nos bancos). Ele lê o NB, computa e escreve no SB. É o compilador: transforma "existe um logical switch com estas portas e estas ACLs" em centenas de **logical flows**. Versões modernas usam **I-P (incremental processing, DDlog/inc-proc-eng)** para recomputar apenas o que mudou em vez de reconstruir tudo — sem isso, o northd vira o gargalo de escala.

**Southbound DB (`ovnsb_db`)** — a implementação. Tabelas principais:

| Tabela | Conteúdo |
|---|---|
| `Logical_Flow` | o pipeline lógico completo, em forma de match/action |
| `Port_Binding` | **onde cada porta lógica está fisicamente** (qual chassis) |
| `Chassis` | os hipervisores registrados, com IPs de encapsulamento |
| `Datapath_Binding` | mapeia cada switch/router lógico a um tunnel key |
| `MAC_Binding` | resultado de ARP/ND aprendido pelos routers lógicos |
| `Encap` | tipos e endereços de túnel por chassis |
| `SB_Global` | estado global, incluindo `nb_cfg` para medir convergência |

**`ovn-controller`** — roda em **cada** chassis. Suas três funções:

1. **Registrar o chassis** no SB DB (nome, IP de encap, tipo).
2. **Fazer o binding**: quando uma VM sobe, o OVS local ganha uma interface com `external_ids:iface-id` igual ao nome da porta lógica; o `ovn-controller` percebe e escreve o `Port_Binding` apontando para si. É assim que o resto da cloud descobre onde a VM está.
3. **Traduzir logical flows em flows OpenFlow** para o `br-int` local, e criar as portas de túnel Geneve para os outros chassis.

**O ponto arquitetural essencial:** não existe appliance central no caminho dos dados. Cada hipervisor tem uma cópia completa do comportamento de rede que lhe interessa, programada em flows. O control plane é central; o data plane é **totalmente distribuído**. Um pacote entre duas VMs em hosts diferentes vai direto, host → host, por um túnel Geneve — sem passar por nenhum roteador ou gateway.

### 36. Modelo lógico: switch, port, router

**`Logical_Switch`** — um domínio de broadcast L2 virtual. Análogo a uma VLAN, mas sem limite de 4094 e sem existência física. Pode ter milhares de portas espalhadas por centenas de hosts.

**`Logical_Switch_Port` (LSP)** — uma porta nesse switch. Campos que importam:

- `addresses` — pares MAC/IP da porta. Pode ser `"fa:16:3e:11:22:33 10.0.0.5"`, ou `unknown` (aceita qualquer MAC, para casos como nested virtualization), ou `router` (a porta se conecta a um roteador lógico), ou `dynamic` (o OVN aloca).
- `port_security` — restringe quais MAC/IP podem enviar por essa porta. **É a anti-spoofing estrutural**: flows de ingresso descartam qualquer coisa fora da lista, incluindo ARP forjado.
- `type` — vazio (VIF normal), `router`, `localnet` (conexão a uma rede física/VLAN via bridge mapping), `localport` (existe em todos os chassis, usada para metadata), `vtep` (VTEP de hardware), `l3gateway`, `chassisredirect`.
- `dhcpv4_options` / `dhcpv6_options` — ponteiro para as opções servidas pelo DHCP embutido.

**`Logical_Router` (LR)** — um roteador virtual distribuído. Não é um appliance, não roda em lugar nenhum: é um conjunto de logical flows presente em **todos** os hipervisores relevantes. Contém `static_routes`, `nat`, `load_balancer`, `policies`.

**`Logical_Router_Port` (LRP)** — interface do roteador, com IP/MAC e `peer` apontando para a LSP do tipo `router` no switch correspondente. É assim que switch e router se ligam.

**Duas consequências que definem o OVN:**

1. **O gateway padrão de uma VM não existe fisicamente.** Quando a VM faz ARP pelo `10.0.0.1`, quem responde é um flow no próprio hipervisor. Quando ela envia um pacote roteado, o roteamento acontece **no hipervisor de origem**, antes do túnel. Não há tromboning para um nó central — isso é o *distributed routing*.
2. **Dois tenants podem usar `10.0.0.0/24` simultaneamente** sem qualquer conflito, porque cada logical router é um espaço de roteamento próprio (o análogo de VRF do item 16) e cada logical switch tem seu próprio tunnel key.

### 37. Pipeline lógico: ingress, egress, logical flows

O OVN modela o comportamento como um pipeline de estágios, atravessado por cada pacote. Cada logical datapath (switch ou router) tem um **pipeline de ingress** e um **de egress**.

Uma `Logical_Flow` no SB DB tem: `logical_datapath`, `pipeline` (ingress/egress), `table_id`, `priority`, `match`, `actions`. A linguagem de match/action é própria do OVN (mais expressiva que OpenFlow puro) e inclui ações como `next;`, `output;`, `ct_next;`, `get_arp;`, `put_dhcp_opts();`, `ct_lb();`, `ct_snat();`.

**Pipeline de um logical switch (estágios, nomes aproximados):**

| Estágio ingress | Função |
|---|---|
| `ls_in_port_sec_l2/ip/nd` | port security — descarta MAC/IP/ARP forjados |
| `ls_in_lookup_fdb` / `put_fdb` | aprendizado para portas `unknown` |
| `ls_in_pre_acl` / `pre_lb` / `pre_stateful` | prepara conntrack |
| `ls_in_acl_hint` / `ls_in_acl` | aplica ACLs |
| `ls_in_lb` | load balancer (VIP → backend) |
| `ls_in_arp_rsp` | **responde ARP/ND localmente** |
| `ls_in_dhcp_options` / `dhcp_response` | **responde DHCP localmente** |
| `ls_in_dns_lookup` / `dns_response` | **responde DNS localmente** |
| `ls_in_l2_lkup` | decide a porta de saída pelo MAC de destino |

| Estágio egress | Função |
|---|---|
| `ls_out_pre_acl` / `pre_lb` | conntrack de saída |
| `ls_out_acl` | ACLs de egresso |
| `ls_out_port_sec_l2/ip` | port security de saída |

**Pipeline de um logical router:**

ingress: `lr_in_admission` (valida MAC de destino) → `lookup_neighbor` / `learn_neighbor` → `ip_input` (ICMP, TTL, opções) → `unsnat` → `defrag` → `dnat` → `ecmp_stateful` → `ip_routing` (longest prefix match!) → `policy` → `arp_resolve` (resolve o MAC do next-hop, consultando `MAC_Binding`) → `chk_pkt_len` → `gw_redirect` → `arp_request`.
egress: `lr_out_undnat` → `snat` → `egr_loop` → `delivery`.

Reconhecer esses nomes é o que te permite ler a saída de `ovn-sbctl lflow-list` e de `ovn-trace` com fluência.

**Como o pipeline atravessa hosts:** a VM de origem passa pelo pipeline de ingresso do switch, pelo pipeline completo do router (se necessário), e chega ao pipeline de egresso. Se a porta de destino está em outro chassis, o pacote é **encapsulado em Geneve carregando o datapath id e a porta lógica de egresso nos metadados** — e o hipervisor de destino **retoma o pipeline no ponto exato**, executando apenas o egress restante. Isso é o que exige Geneve e não VXLAN (item 25).

### 38. Tradução logical flow → OpenFlow

O `ovn-controller` faz a última etapa da compilação:

1. Lê os `Logical_Flow` dos datapaths que estão presentes neste chassis (via `monitor_cond`, para não baixar o banco inteiro).
2. Traduz portas lógicas em portas OpenFlow reais ou em túneis, consultando `Port_Binding`.
3. Traduz estágios lógicos em números de tabela OpenFlow (o `br-int` usa uma faixa de tabelas com mapeamento conhecido: tabelas 8–31 para ingress de switch, 32–39 para saída física, etc.).
4. Usa `metadata` para carregar o identificador do datapath lógico e `reg` para as portas lógicas de ingresso/egresso.
5. Escreve tudo no `br-int` via OpenFlow.

Portanto há **três níveis** que você precisa saber distinguir ao depurar:

| Nível | Onde vive | Como inspecionar |
|---|---|---|
| Intenção | NB DB | `ovn-nbctl show` |
| Pipeline lógico | SB DB | `ovn-sbctl lflow-list`, `ovn-trace` |
| Flows reais | `br-int` | `ovs-ofctl dump-flows`, `ofproto/trace` |
| Cache | datapath | `ovs-appctl dpctl/dump-flows` |

Quase todo problema de OVN se resolve descobrindo **em qual desses níveis a expectativa quebra**.

### 39. L2/L3 distribuído, gateway port, HA

**Tráfego east-west** (VM ↔ VM, mesmo tenant, mesmo ou outro switch): **100% distribuído**. Nenhum nó central envolvido, roteamento feito no hipervisor de origem, um único túnel Geneve entre os dois hosts.

**Tráfego north-south** é o problema. Para falar com a rede física, alguém precisa de uma porta na rede física. Aí entram:

- **`localnet` port** — conecta um logical switch a uma rede física via `ovn-bridge-mappings` (que mapeia um nome de rede a uma bridge OVS local, ex.: `physnet1:br-ex`). Todo chassis que tenha esse mapping pode sair direto para a rede física. É o modelo "provider network".
- **Distributed Gateway Port (DGP)** — uma LRP com `gateway_chassis` (ou `ha_chassis_group`) definido. A porta é *logicamente* distribuída, mas o tráfego que precisa de estado centralizado (SNAT, por exemplo) é redirecionado para um chassis específico.
- **`chassisredirect` port (CR port)** — a porta interna que representa "envie isto ao chassis gateway". Quando você vê `cr-lrp-xxxx` na saída do OVN, é isso.

**Quando o tráfego é centralizado e quando não é** — este é o ponto mais importante da operação de um OVN de produção:

| Caso | Caminho |
|---|---|
| VM ↔ VM mesmo switch | distribuído |
| VM ↔ VM switches diferentes, mesmo router | distribuído (roteado na origem) |
| VM → externo com **floating IP** (`dnat_and_snat` com `external_mac`) | **distribuído** — sai pelo próprio hipervisor |
| VM → externo com **SNAT de router** (IP compartilhado) | **centralizado** no gateway chassis |
| Externo → VM com floating IP | distribuído |
| Externo → VIP de load balancer no router | centralizado |

A razão de o SNAT compartilhado ser centralizado: o estado de tradução de portas precisa ser único. Se dois hipervisores fizerem SNAT para o mesmo IP externo, podem escolher a mesma porta de origem. Por isso o floating IP distribuído (`dnat_and_snat` por instância) é a técnica preferida para escalar north-south — cada VM sai pelo seu próprio host.

**HA do gateway:**

- **`Gateway_Chassis`** — lista ordenada por prioridade associada a uma LRP. O chassis de maior prioridade ativo assume; os demais ficam em espera.
- **`HA_Chassis_Group`** — modelo mais moderno e genérico, usado também por `localnet` e por portas externas.
- A eleição e o monitoramento usam **BFD** entre os chassis pelos túneis Geneve. A queda é detectada em centenas de milissegundos.
- No failover, o novo gateway emite **GARP / RARP** (item 11!) para que a rede física atualize CAM e ARP. Aqui os conceitos do Bloco 1 e 2 voltam diretamente.
- **Limitação clássica:** o estado de conntrack **não** é replicado entre gateways. Conexões com SNAT ativo quebram no failover. Isso é uma resposta honesta e valiosa em entrevista.

Na escolha de design: quanto mais o desenho usa floating IPs distribuídos e `localnet`, menos o gateway centralizado é gargalo. Deployments que roteiam tudo por SNAT centralizado colocam todo o north-south em um punhado de nós — e é exatamente aí que as pessoas descobrem o limite.

### 40. DHCP, DNS e ARP/ND nativos

Já mencionado nos itens 20, 21 e 36, mas vale consolidar como **um princípio de design**:

O OVN substitui serviços de rede por **flows que respondem localmente**:

- **ARP/ND responder** — o estágio `ls_in_arp_rsp` tem uma flow por endereço conhecido no switch lógico. A VM faz ARP; o hipervisor responde imediatamente. Nenhum broadcast atravessa o túnel.
- **DHCP** — `put_dhcp_opts` monta a resposta e `ls_in_dhcp_response` a entrega. Sem servidor, sem relay, sem rogue possível.
- **DNS** — `put_dns_opts` para nomes registrados no NB.
- **IPv6 RA** — o logical router emite Router Advertisements por flow, habilitando SLAAC sem daemon.

**Por que isso importa tanto:** o BUM traffic (Broadcast, Unknown unicast, Multicast) é o inimigo histórico de qualquer rede L2 grande. O OVN o elimina quase inteiramente por construção, porque o control plane já **sabe** todos os endereços — não precisa aprender. O que resta de broadcast real (ex.: uma aplicação que faz broadcast de verdade) é tratado com replicação de ingresso para os chassis que hospedam portas daquele datapath.

Esta é, provavelmente, a melhor resposta para "qual a vantagem de uma rede lógica controlada por SDN sobre uma VLAN tradicional?".

### 41. NAT no OVN

A tabela `NAT` do NB, referenciada por um `Logical_Router`:

| Tipo | Semântica |
|---|---|
| `snat` | origem `logical_ip` (rede ou host) → `external_ip`. Muitos-para-um. **Centralizado no gateway.** |
| `dnat` | destino `external_ip` → `logical_ip`. Entrada apenas. |
| `dnat_and_snat` | **floating IP**: 1:1 bidirecional. Se tiver `external_mac` + `logical_port` definidos, é **distribuído** — executado no hipervisor da VM. |

Campos relevantes: `allowed_ext_ips` / `exempted_ext_ips` (aplicar ou não NAT conforme o destino externo), `external_port_range` (limitar a faixa de portas do SNAT — útil para logging/CGNAT), `gateway_port` (desambiguar qual DGP usar quando o router tem várias).

Onde isso aparece no pipeline: estágios `lr_in_unsnat` e `lr_in_dnat` na entrada, `lr_out_undnat` e `lr_out_snat` na saída, todos usando `ct_snat()` / `ct_dnat()` — ou seja, **conntrack do kernel**, com zonas separadas por datapath.

Pontos de atenção em produção:

- **Esgotamento de conntrack** no gateway chassis sob SNAT pesado. Dimensione `nf_conntrack_max` e monitore.
- **Hairpin**: VM acessando o floating IP de outra VM do mesmo tenant. O OVN trata, mas é um caso que costuma revelar bugs de versão.
- **Fragmentação**: o estágio `lr_in_defrag` existe justamente porque NAT precisa ver as portas L4, que só estão no primeiro fragmento.

### 42. ACLs, port groups, address sets

**`ACL`** é a unidade de política: `direction` (`from-lport` = ingress, ou seja, saindo da VM; `to-lport` = egress, chegando à VM), `priority`, `match` (a mesma linguagem de match do OVN), `action` (`allow`, `allow-related`, `allow-stateless`, `drop`, `reject`), e opcionalmente `log`.

A distinção que mais gera erro:

- **`allow`** — stateless, permite só aquele sentido. Você precisa de uma regra explícita para o retorno.
- **`allow-related`** — **stateful**: submete a conntrack e libera automaticamente o tráfego de retorno (ESTABLISHED) e relacionado (ICMP errors, FTP data). É o que corresponde ao comportamento de um security group.
- **`allow-stateless`** — explicitamente sem conntrack, para fluxos de alto volume onde o custo de estado não compensa.
- **`reject`** — responde com TCP RST / ICMP unreachable em vez de descartar em silêncio. Melhor experiência, pior contra varredura.

**`Port_Group`** — um conjunto nomeado de logical switch ports, com address sets (IPv4 e IPv6) derivados automaticamente. ACLs podem ser anexadas diretamente ao port group. Isso é o que torna **microssegmentação** viável: em vez de N² regras entre hosts, você escreve "port group `web` aceita 443 de qualquer lugar e 3306 apenas do port group `app`". O `ovn-northd` expande isso em flows.

**`Address_Set`** — lista nomeada de endereços reutilizável em matches, sem precisar reescrever ACLs quando a lista muda.

**Conntrack zones** — cada logical datapath recebe sua própria zona de conntrack no host, para que tenants diferentes com os mesmos IPs privados não colidam na tabela de estado. Detalhe fino, e ótimo sinal de profundidade quando você o menciona.

**Cuidado de desempenho (volta ao item 31):** ACLs muito específicas (por porta L4 individual, por IP individual) forçam o datapath a gerar megaflows estreitas e aumentam a taxa de upcall. Usar port groups e address sets, e agrupar faixas, melhora não só a legibilidade mas a performance real.

### 43. Load balancer do OVN

`Load_Balancer` no NB tem `vips` (mapa `"IP:porta" → "backend1:porta,backend2:porta"`), `protocol`, `health_check`, `selection_fields`.

É associado a um `Logical_Switch` (balanceamento no ponto de entrada da rede) ou a um `Logical_Router` (para tráfego roteado, incluindo north-south).

Implementação: a ação `ct_lb(backends=...)` no estágio `ls_in_lb` / `lr_in_dnat`. O conntrack escolhe um backend na primeira conexão e registra a decisão, garantindo que todos os pacotes seguintes daquela conexão vão para o mesmo lugar. `selection_fields` controla quais campos entram no hash (para afinidade por IP de origem, por exemplo).

**`Load_Balancer_Health_Check`** — o `ovn-controller` envia probes e desabilita backends que falham, removendo-os do `ct_lb`. Requer `ip_port_mappings` para saber qual porta lógica corresponde a cada backend.

**Propriedades que importam:**

- É **L4**, não L7. Não há roteamento por path/header, nem TLS termination.
- É **distribuído**: roda no hipervisor de origem do tráfego. Não há appliance, não há gargalo central, não há ponto único de falha no dataplane.
- É exatamente o mecanismo usado pelo `ovn-kubernetes` para implementar `Service` do tipo ClusterIP — substituindo o `kube-proxy`/iptables por flows do OVS.

### 44. Geneve e os TLVs de metadata

Consolidando o item 25 do ponto de vista do OVN. Em cada pacote encapsulado, o OVN carrega:

- **VNI (24 bits)** → o `tunnel_key` do `Datapath_Binding`, ou seja, **qual rede lógica**.
- **Opção Geneve (classe 0x0102, tipo 0x80)** → 32 bits contendo o `tunnel_key` da **porta lógica de ingresso** (15 bits) e da **porta lógica de egresso** (16 bits).

O efeito: quando o pacote chega ao chassis remoto, o `ovn-controller` de lá não precisa reclassificar nada. Ele lê os metadados, carrega os registradores correspondentes, e **retoma o pipeline lógico no estágio de egresso**. O pipeline lógico é logicamente contínuo mesmo atravessando dois hosts físicos.

Com VXLAN isso é impossível — só há o VNI. Por isso o suporte a VXLAN no OVN é limitado (usado principalmente para interoperar com VTEPs de hardware, com restrições no número de portas lógicas e sem suporte pleno a ACLs de egresso remoto).

Essa é uma das perguntas mais discriminativas em entrevista de OVN, e a resposta completa é: *o OVN usa Geneve porque precisa de metadados além do identificador de rede, especificamente as portas lógicas de ingresso e egresso, para continuar o pipeline lógico no chassis remoto; os TLVs extensíveis do Geneve permitem isso e o header fixo do VXLAN não.*

### 45. OVN-IC (Interconnection)

Um cluster OVN é limitado a uma **availability zone**: um conjunto de chassis que compartilham o mesmo NB/SB DB. Escalar indefinidamente um único SB DB não funciona — o número de chassis, de logical flows e a carga de monitors crescem até doer.

**OVN-IC** conecta múltiplas AZs, cada uma com seu próprio OVN completo, através de dois bancos globais adicionais:

- **`IC_NB`** — onde se declaram os `Transit_Switch`, os switches lógicos compartilhados entre AZs.
- **`IC_SB`** — onde as AZs publicam suas `Availability_Zone`, `Gateway`, `Port_Binding` e `Route` para consumo mútuo.

Daemons:

- **`ovn-ic`** — roda em cada AZ. Lê o IC_NB/IC_SB e sincroniza com o NB/SB local: cria automaticamente o logical switch de trânsito local, as portas correspondentes às AZs remotas, e importa/exporta rotas.
- **`ovn-ic-sbctl` / `ovn-ic-nbctl`** — as CLIs correspondentes.

**Como funciona conceitualmente:** o `Transit_Switch` é um logical switch que existe em todas as AZs participantes. Cada AZ conecta seu `Logical_Router` a ele por uma LRP, e designa um ou mais **interconnection gateways** (chassis com `is-interconn=true`). O tráfego entre AZs vai da VM → logical router local → transit switch → túnel Geneve entre os gateways das duas AZs → logical router remoto → VM.

**Anúncio de rotas** — com `ic-route-adv=true` e `ic-route-learn=true` nas opções do `NB_Global`, cada AZ publica suas rotas no IC_SB e aprende as das outras, criando um roteamento inter-AZ automático. É possível filtrar com `ic-route-blacklist`.

**Trade-offs que você deve saber articular:**

- Ganha-se isolamento de falha (cada AZ tem seu control plane; a queda de um SB DB não afeta as outras) e escala horizontal.
- Perde-se: o tráfego inter-AZ **passa pelos gateways de interconexão** — não é distribuído host a host como dentro de uma AZ. Os gateways viram um ponto de concentração a dimensionar.
- ACLs e load balancers não atravessam AZs automaticamente; a política precisa ser coerente em cada lado.
- O MTU precisa ser consistente entre AZs, e o underlay entre elas (que pode ser uma WAN) vira parte do domínio de falha.

### 46. Integrações

**`ovn-kubernetes`** — CNI que implementa o modelo de rede do Kubernetes sobre OVN:

- Cada **node** recebe um logical switch; cada **pod** uma logical switch port.
- Um `ovn_cluster_router` distribuído conecta os switches dos nodes.
- **Services** (ClusterIP/NodePort) → `Load_Balancer` do OVN com `ct_lb`, substituindo iptables/IPVS do `kube-proxy`.
- **NetworkPolicy** → ACLs + Port Groups + Address Sets. O mapeamento é quase 1:1 conceitualmente.
- **Egress IP / Egress Firewall** → NAT e ACLs no router.

**OpenStack Neutron ML2/OVN** — substituiu o antigo driver OVS+agentes:

- Sem `neutron-l3-agent`, `neutron-dhcp-agent`, `neutron-metadata-agent` por rede — tudo vira logical flow. Menos processos, menos namespaces, menos pontos de falha.
- Rede Neutron → `Logical_Switch`; porta → LSP; router → `Logical_Router`; security group → ACL + Port Group; floating IP → `dnat_and_snat`; LBaaS/Octavia com provider OVN → `Load_Balancer`.
- Metadata (`169.254.169.254`) é servida por um agente local em cada chassis através de uma porta `localport`.

**BFD** — usado internamente entre chassis para liveness dos túneis e failover de gateway; também exposto em `static_routes` para ECMP com detecção rápida.

**BGP** — o `ovn-bgp-agent` (ou soluções equivalentes) roda nos nós de gateway, observa o SB DB e anuncia por BGP (via FRR) os IPs que estão dentro do OVN, expondo-os à rede física sem NAT. Combina o mundo do Bloco 2 com o mundo do Bloco 6, e é a direção para a qual as clouds vêm caminhando.

### 47. Debug e observabilidade do OVN

A metodologia é sempre a mesma: **descer os níveis até achar onde a expectativa quebra.**

**Nível 1 — intenção (NB):**

```bash
ovn-nbctl show                               # topologia lógica completa
ovn-nbctl list Logical_Switch_Port <porta>
ovn-nbctl lr-route-list <router>
ovn-nbctl lr-nat-list <router>
ovn-nbctl acl-list <switch|port-group>
ovn-nbctl lb-list
```

**Nível 2 — implementação (SB):**

```bash
ovn-sbctl show                               # chassis e port bindings
ovn-sbctl list Chassis
ovn-sbctl list Port_Binding <porta>          # ONDE a porta está ligada
ovn-sbctl lflow-list <datapath>              # o pipeline lógico inteiro
ovn-sbctl list MAC_Binding
```

Se `Port_Binding.chassis` está vazio, a VM não foi "reivindicada" por nenhum `ovn-controller` — quase sempre o `external_ids:iface-id` do OVS está errado ou ausente. É a causa nº 1 de "a VM subiu mas não tem rede".

**Nível 3 — simulação lógica:**

```bash
ovn-trace --detailed <datapath> 'inport=="lsp1" && eth.src==fa:16:3e:.. && ip4.dst==10.0.0.5 && ...'
```

`ovn-trace` percorre o pipeline lógico estágio por estágio e mostra exatamente onde o pacote é descartado e por qual flow. **É a ferramenta mais importante do OVN.** Aprenda a escrever os matches à mão.

**Nível 4 — flows reais e datapath:**

```bash
ovs-ofctl -O OpenFlow15 dump-flows br-int | grep <cookie>
ovs-appctl ofproto/trace br-int in_port=3,dl_src=...,nw_dst=...
ovs-appctl dpctl/dump-flows
ovs-dpctl show
```

O `cookie` de cada flow OpenFlow corresponde ao UUID da `Logical_Flow` que a originou — é o fio que liga o nível 4 de volta ao nível 2.

**Nível 5 — o fio:**

```bash
tcpdump -ni genev_sys_6081                   # tráfego encapsulado
tcpdump -ni <uplink> udp port 6081
ovs-appctl dpif/dump-flows br-int
```

**Convergência e saúde do control plane:**

```bash
ovn-nbctl --wait=hv sync                     # espera todos os chassis aplicarem
ovn-sbctl list SB_Global                     # nb_cfg
ovn-appctl -t ovn-controller ct-zone-list
ovn-appctl -t ovn-northd status
ovs-appctl -t ovnsb_db cluster/status OVN_Southbound   # saúde do RAFT
```

O par `nb_cfg` (no NB_Global) e `nb_cfg` por `Chassis_Private` no SB é o mecanismo que permite medir **latência de convergência**: quanto tempo leva entre escrever no NB e o último hipervisor ter aplicado. É a métrica de saúde mais importante de um OVN em escala, e um excelente assunto para pesquisa.
---

## Bloco 7 — Operação, performance e carreira

### 48. Troubleshooting metodológico

O erro de quem é júnior é começar a apertar coisas. O método é subir a pilha, camada por camada, e **cortar o problema pela metade** a cada passo.

**Roteiro padrão:**

1. **L1/L2** — a interface está up? `ip -br link`. Há erros? `ip -s link`, `ethtool -S`. O MAC do vizinho está no cache? `ip neigh`. O switch vê o MAC? `bridge fdb show` / `ovs-appctl fdb/show`.
2. **L3** — tenho IP e máscara certos? `ip -br addr`. A rota existe? `ip route get <destino>` (não `ip route show` — o `get` mostra a decisão **real**). O gateway responde? `ping <gw>`.
3. **Caminho** — `mtr -n <destino>` por alguns minutos. Onde começa a perda? Perda só em um hop intermediário costuma ser rate-limit de ICMP, não problema real; perda que **persiste a partir de um hop até o fim** é problema real.
4. **L4** — a porta está aberta? `ss -tlnp` no servidor, `nc -vz` ou `curl -v` do cliente. O SYN chega? `tcpdump -ni any port 443`.
5. **Firewall/política** — `iptables -L -n -v` / `nft list ruleset` (olhe os **contadores**, não só as regras), ACLs do OVN, security groups.
6. **Aplicação** — logs, TLS, DNS.

**O teste do tcpdump nos dois lados** é a técnica mais valiosa que existe: capture na origem e no destino simultaneamente. Se o pacote sai e não chega, o problema está no meio. Se chega e não há resposta, o problema é do host de destino. Isso elimina metade das hipóteses em um passo.

```bash
tcpdump -ni eth0 -nn -e 'host 10.0.0.5 and tcp port 443' -w /tmp/cap.pcap
tcpdump -ni any 'icmp or (tcp[tcpflags] & (tcp-syn|tcp-rst) != 0)'
```

Filtros que valem memorizar: `tcp[tcpflags] & tcp-syn != 0` (só SYNs), `icmp[icmptype] == 3` (unreachable — caçando PMTUD), `greater 1400` (pacotes grandes), `vlan` e `udp port 6081` (dentro do túnel).

**Sintomas → causa provável** (útil como tabela mental):

| Sintoma | Suspeita primeira |
|---|---|
| Ping ok, transferência grande trava | **MTU / PMTUD black hole** |
| Conexão intermitente, throughput errático | duplex mismatch, cabo, LAG mal hasheado |
| Latência alta e variável sob carga | bufferbloat, congestão, incast |
| Funciona por IP, não por nome | DNS (TTL, cache negativo, resolver) |
| Funciona uma vez, depois para | conntrack cheio, ARP aging, timeout de NAT |
| Metade das conexões falha | ECMP com um caminho quebrado, um backend ruim, MLAG assimétrico |
| Throughput baixo em link longo | janela TCP / BDP / buffers de socket |

### 49. Observabilidade

- **sFlow** — amostragem estatística de pacotes (1 em N). Barato, escala bem, ótimo para visão macro de tráfego. Não vê tudo.
- **NetFlow / IPFIX** — o dispositivo mantém registro por fluxo (5-tupla, bytes, pacotes, duração) e exporta. Mais preciso, mais caro em CPU/memória. IPFIX é o padrão aberto e extensível.
- O OVS suporta os três nativamente (`ovs-vsctl -- set bridge br-int sflow=@s -- --id=@s create sflow ...`), o que dá visibilidade dentro do overlay.

**Métricas que importam** e nem todo mundo coleta:

- Por interface: bps, pps, **erros, discards, drops** — os últimos três são os que contam histórias.
- TCP do host: retransmissões, `ss -ti` (RTT, cwnd, retrans por socket), listen queue overflows.
- Conntrack: utilização percentual da tabela. Alerta em 80%.
- OVS: taxa de upcall, número de megaflows, CPU do `ovs-vswitchd` e dos handlers/revalidators.
- OVN: latência de convergência (`nb_cfg`), tamanho do SB DB, tempo de loop do `ovn-northd`, status do RAFT.

**Testes ativos:** `iperf3` (TCP e UDP, com `-P` para paralelismo — se 1 stream é lento e 8 streams saturam, o problema é janela/BDP, não banda), `netperf` para latência de requisição-resposta (`TCP_RR`), e probing sintético contínuo entre pares de hosts para detectar degradação antes do usuário.

### 50. Performance: offloads, SR-IOV, DPDK, NUMA

**Offloads da NIC** (visíveis em `ethtool -k`):

- **TSO/GSO** (segmentação): o host entrega um buffer grande e a NIC (TSO) ou o kernel no último momento (GSO) o segmenta em MTUs. Reduz drasticamente o número de operações por byte.
- **GRO/LRO** (agregação na recepção): junta segmentos contíguos antes de subir a pilha.
- **Checksum offload**, **RSS** (distribui filas de recepção por CPUs via hash), **VXLAN/Geneve offload** (a NIC entende o encapsulamento e ainda faz TSO/checksum sobre o pacote interno — **essencial** em overlay).

Cuidado: TSO/GRO mentem para o `tcpdump`. Você vê "pacotes" de 64 KB que nunca existiram no fio. Ao investigar MTU, desligue temporariamente (`ethtool -K eth0 gso off tso off gro off`) ou capture no switch.

**SR-IOV** — a NIC se apresenta como várias funções virtuais (VFs), cada uma passada diretamente a uma VM via IOMMU. Desempenho próximo do bare-metal, latência mínima. Custo: a VM fala direto com o hardware, **contornando o OVS**, então você perde ACLs, encapsulamento e observabilidade — a menos que use *hardware offload* (abaixo). Também complica live migration.

**DPDK** — driver em espaço de usuário com poll mode. O `ovs-vswitchd` com datapath `netdev` roda PMD threads em busy-poll, sem interrupções. Ganho de milhões de pps. Custo: CPUs dedicadas a 100%, hugepages, configuração de NUMA, e perda da integração natural com conntrack do kernel.

**Hardware offload (tc flower / OVS offload)** — o melhor dos dois mundos: as megaflows do OVS são empurradas para o ASIC/eSwitch da NIC via `tc flower` (`ovs-vsctl set Open_vSwitch . other_config:hw-offload=true`). O primeiro pacote sobe ao software, os seguintes são encaminhados pelo hardware. Com SR-IOV + switchdev, você mantém as políticas do OVN **e** o desempenho do passthrough. É o estado da arte, e onde as SmartNICs/DPUs (BlueField, etc.) atuam — algumas rodam o próprio `ovn-controller` na DPU.

**NUMA e CPU pinning** — em servidor multi-socket, memória e PCIe são locais a um socket. Se a NIC está no NUMA node 0 e a thread PMD ou a vCPU no node 1, cada pacote atravessa a interconexão entre sockets, com penalidade grande. Regra: fixe VMs, PMDs e filas de interrupção no mesmo NUMA node da NIC. Verificar: `cat /sys/class/net/eth0/device/numa_node`, `lstopo`.

**IRQ affinity / RPS / XPS** — distribuir o processamento de rede entre CPUs. Sem isso, uma única CPU trata todas as interrupções e vira gargalo antes de o link saturar.

### 51. Segurança de rede

**Microssegmentação** — a política não é mais "DMZ vs interna", é por carga de trabalho. Cada porta lógica tem seu conjunto de regras, aplicado **no hipervisor de origem**, o que significa que o tráfego bloqueado nunca chega sequer a entrar na rede. É estruturalmente superior a um firewall de perímetro, e é exatamente o que ACLs + Port Groups do OVN entregam.

**Port security / anti-spoofing** — no mundo físico: DHCP snooping → tabela de bindings → Dynamic ARP Inspection + IP Source Guard. No OVN: o campo `port_security` da LSP, aplicado nos estágios `ls_in_port_sec_*` e `ls_out_port_sec_*`. Uma VM simplesmente não consegue enviar com MAC ou IP que não sejam os dela.

**Security groups** → ACLs `allow-related` com Port Groups. O modelo default-deny com exceções explícitas.

**Isolamento de tenants** — em overlay, o isolamento é por `tunnel_key`/VNI e por logical datapath. Um tenant não consegue nem endereçar o outro, porque não compartilham datapath. Isso é mais forte que isolamento por ACL (que depende de a regra estar certa).

**Plano de controle** — o SB DB é o alvo de maior valor de um deployment OVN: quem escreve nele programa a rede inteira. Protejam-se as conexões com **SSL/TLS mútuo** (`ovn-nbctl set-connection pssl:...`), RBAC do OVSDB (que restringe o que um `ovn-controller` pode escrever — ele deve poder alterar apenas seu próprio `Chassis` e seus `Port_Binding`), e segregação de rede de gerência.

**Defesas gerais** que você deve saber citar: rate limiting de ARP/broadcast, proteção contra SYN flood (`tcp_syncookies`), uRPF (descartar pacotes cuja origem não seria roteada de volta pela interface de entrada), BCP 38 (filtragem anti-spoofing na borda), e criptografia de túnel (IPsec) quando o underlay não é confiável — o OVN suporta IPsec nos túneis Geneve via `ovn-nbctl set NB_Global . ipsec=true`.

### 52. O que costuma ser cobrado em entrevista de SDN/rede

**Conceituais, quase sempre:**

- Explique o que acontece, passo a passo, quando você digita uma URL e aperta Enter. (Resposta boa passa por: cache de DNS → resolução recursiva → ARP do gateway → handshake TCP → TLS → HTTP. Menções a MSS, PMTU e keep-alive impressionam.)
- Diferença entre switch e roteador; entre domínio de colisão e de broadcast.
- Como funciona o ARP e por que ele não cruza roteadores.
- O que é NAT e por que ele complica conexões entrantes.
- TCP vs UDP e quando escolher cada um; three-way handshake; o que é TIME_WAIT e por que existe.
- O que é MTU e como diagnosticar um problema de MTU. (Se você contar a história do black hole do item 14, você se destaca.)

**De virtualização/SDN:**

- O que é SDN e o que significa separar control plane de data plane.
- Por que overlay existe — os três motivos do item 24.
- VXLAN vs Geneve, e **por que o OVN usa Geneve** (item 44). Essa é *a* pergunta.
- Explique a arquitetura do OVN: NB → northd → SB → ovn-controller, e o que vive em cada peça.
- O que é um logical flow e como ele vira OpenFlow.
- O que acontece quando uma VM manda um ARP num ambiente OVN. (Resposta: nada sai do host.)
- Como o OVN faz roteamento distribuído e quando o tráfego é obrigado a passar por um gateway centralizado (item 39, a tabela).
- O que acontece no failover de um gateway chassis e o que se perde (conntrack).
- EVPN vs controlador SDN como control plane (item 27).

**Operacionais:**

- "Uma VM não tem conectividade. Como você investiga?" — descreva o método do item 48 **e** mencione checar `Port_Binding` antes de qualquer outra coisa num ambiente OVN.
- "O throughput está em 200 Mbps num link de 10G." — BDP, janela, buffers, um único fluxo em LAG/ECMP, offloads.
- "O `ovs-vswitchd` está com CPU alta." — upcalls, megaflow explosion, ACLs específicas demais, revalidator.
- Leitura de um `tcpdump` na tela.

**Como estudar para valer:** monte um laboratório com 3 VMs (ou containers com netns), instale OVS e OVN manualmente — sem OpenStack, sem Kubernetes — e construa à mão: dois logical switches, um logical router, DHCP nativo, uma ACL, um floating IP distribuído e um gateway com HA. Depois quebre de propósito: derrube um chassis, reduza o MTU do underlay para 1400, remova o `iface-id`, encha a tabela de conntrack. Diagnosticar cada uma dessas falhas com `ovn-trace` e `ofproto/trace` vale mais que qualquer certificação nessa área.

---

## Mapa de dependências (para revisar)

```
Ethernet/MAC (2) ──> switching (3) ──> VLAN (4) ──> limite de 4094 ──┐
                                                                      ├──> overlay (24,25)
leaf-spine (8) ──> ECMP (13) ──> underlay L3 estável ────────────────┘
                                                                      |
IP/CIDR (9) ──> roteamento (12) ──> VRF (16) ────> logical router (36)|
ARP (11) ──────────────────────────────────────> ARP responder (40)  |
DHCP (20) ─────────────────────────────────────> put_dhcp_opts (40)  |
conntrack (15) ────────────────────────────────> ACLs + ct_lb (42,43)|
MTU/PMTUD (14) ────────────────────────────────> MTU de overlay (25) |
                                                                      v
                netns/veth/bridge (28) ──> OVS (29-34) ──> OVN (35-47)
```

Se você consegue explicar cada seta desse diagrama sem consultar, o objetivo do material foi cumprido.
