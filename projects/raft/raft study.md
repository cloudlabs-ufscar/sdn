# RAFT no OVSDB — ressincronização incremental de followers

> Documento de estudo sobre o mecanismo de ressincronização do cluster RAFT do OVSDB, utilizado
> pelos bancos NB e SB do OVN e pelos bancos IC-NB e IC-SB do OVN-IC. O objetivo é identificar
> em quais situações a replicação já é incremental, em quais o banco é copiado integralmente e
> quais alterações de comportamento seriam pertinentes do ponto de vista de alta disponibilidade.
>
> **Base:** código do Open vSwitch (`ovsdb/raft.c`, `ovsdb/storage.c`, `ovsdb/transaction.c`) no
> branch `main` de outubro de 2026, documentação do OVSDB e o artigo original do RAFT.

---

## Índice

1. [Hipótese e reformulação do problema](#1-hipótese-e-reformulação-do-problema)
2. [Fundamentos do RAFT](#2-fundamentos-do-raft)
3. [Implementação atual no OVSDB](#3-implementação-atual-no-ovsdb)
4. [Os três caminhos de ressincronização](#4-os-três-caminhos-de-ressincronização)
5. [Espaço de soluções](#5-espaço-de-soluções)
6. [Trabalhos relacionados e precedentes](#6-trabalhos-relacionados-e-precedentes)
7. [Invariantes de corretude](#7-invariantes-de-corretude)
8. [Plano experimental](#8-plano-experimental)
9. [Questões para a equipe da Magalu Cloud](#9-questões-para-a-equipe-da-magalu-cloud)
10. [Relação com alta disponibilidade e topologias multi-site](#10-relação-com-alta-disponibilidade-e-topologias-multi-site)
11. [Referências](#11-referências)

---

## 1. Hipótese e reformulação do problema

### 1.1 Hipótese inicial

A hipótese de partida é a seguinte: em vez de todos os membros do cluster replicarem todo o
conteúdo uns dos outros, replicar apenas o que está desatualizado ou o que apresentou erro.

### 1.2 Análise

A hipótese é pertinente, mas o ponto de aplicação não é o que parece à primeira vista:

- O RAFT **não** opera no modelo em que todos replicam tudo. Apenas o líder replica, e envia a
  cada follower **somente as entradas de log que lhe faltam**. Esse caminho já é incremental.
- Há um único ponto em que o OVSDB copia **o banco inteiro**: quando o follower está tão atrasado
  que as entradas de que necessita **já foram compactadas** no líder. Nesse caso, o líder envia
  um *snapshot* completo (`install_snapshot`).

### 1.3 Questão de pesquisa

A partir dessa análise, a questão de pesquisa é reformulada como:

> **Como evitar, reduzir ou tornar menos custoso o envio do snapshot completo a um follower
> atrasado?**

As seções seguintes fundamentam essa afirmação com base no código e, em seguida, exploram o
espaço de soluções.

---

## 2. Fundamentos do RAFT

### 2.1 Conceitos relevantes

| Conceito | Definição |
|---|---|
| **Líder** | Único servidor que aceita escritas. Todo dado flui do líder para os followers; followers nunca replicam entre si. |
| **Log** | Sequência de entradas (transações) identificadas por índice e termo. |
| **Commit** | Uma entrada é considerada *committed* quando a maioria dos membros a gravou. Com 3 nós, o cluster tolera a falha de 1. |
| **`nextIndex[f]`** | Próxima entrada que o líder enviará ao follower `f`. |
| **`matchIndex[f]`** | Maior entrada que o líder sabe estar presente em `f`. |
| **AppendEntries** | RPC pelo qual o líder envia entradas a partir de `nextIndex[f]`. Se o follower recusa (log divergente), o líder recua `nextIndex` e tenta novamente. |
| **Snapshot / compactação** | Para que o log não cresça indefinidamente, o estado é periodicamente salvo como snapshot e as entradas anteriores são descartadas. |
| **InstallSnapshot** | Quando `nextIndex[f]` aponta para antes do início do log (entradas já descartadas), o líder envia o snapshot inteiro. |

### 2.2 Observação central

A replicação apenas do que falta corresponde exatamente ao mecanismo `nextIndex`/AppendEntries.
O problema surge somente quando a compactação já removeu as entradas que faltam ao follower.

---

## 3. Implementação atual no OVSDB

### 3.1 Decisão do líder a cada resposta do follower

Em `raft_handle_append_reply()` (`ovsdb/raft.c`), após ajustar `next_index`, o líder segue um de
três casos:

```
        Case 1       |    Case 2     |      Case 3
   <---------------->|<------------->|<------------------>
                     +---+---+---+---+
                   T | T | T | T | T |
                     +---+---+---+---+
                       ^               ^
                   log_start        log_end
```

| Caso | Condição | Ação do líder | Custo |
|---|---|---|---|
| 1 | `next_index < log_start` | `raft_send_install_snapshot_request()`: **banco inteiro** | O(tamanho do banco) |
| 2 | `log_start ≤ next_index < log_end` | `raft_send_append_request()`: apenas as entradas faltantes | O(entradas faltantes) |
| 3 | `next_index ≥ log_end` | Follower atualizado (ou fim da fase de *catch-up* de um servidor novo) | — |

Quando o AppendEntries falha, o OVSDB já aplica a otimização descrita na seção 4.2.1 da tese do
RAFT: o follower informa o tamanho do seu log e o líder recua `next_index` diretamente para esse
ponto, em vez de recuar uma entrada por vez.

### 3.2 O snapshot é monolítico

`raft_send_install_snapshot_request()` serializa **o snapshot inteiro em uma única mensagem
JSON-RPC** (`raft_entry_get_serialized_data(&raft->snap)`). Consequências:

- não há envio em partes (*chunks*), controle de *offset* ou retomada;
- se a conexão for interrompida durante a transferência, o envio recomeça do início;
- existe apenas uma trava (`install_snapshot_request_in_progress`) que impede dois envios
  simultâneos.

A Figura 13 do artigo do RAFT já prevê InstallSnapshot em *chunks* com os campos `offset` e
`done`. **O OVSDB não implementa essa parte do protocolo.**

### 3.3 Obsolescência do snapshot durante a transferência

Em `raft_handle_install_snapshot_reply()`: caso o follower conclua a instalação, mas o líder
tenha **compactado novamente** nesse intervalo, o snapshot instalado está obsoleto. O líder então
registra *"installed out-of-date snapshot, starting over"* e **envia o banco inteiro novamente**.

Com banco grande, alta taxa de escrita e rede lenta, esse comportamento pode se tornar um
**ciclo**, fenômeno que o CockroachDB denomina *snapshot storm*.

### 3.4 A compactação é uma decisão local que desconsidera os followers

`ovsdb_storage_should_snapshot()` (`ovsdb/storage.c`) adota os seguintes critérios:

- aguarda um intervalo mínimo **aleatório entre 10 e 20 minutos** desde o último snapshot (o
  *jitter* evita que todos os membros compactem simultaneamente);
- antes de 24 horas, compacta se houver **100 ou mais entradas** e o arquivo de log tiver
  **crescido significativamente** (`ovsdb_log_grew_lots`);
- após 24 horas, compacta se houver qualquer entrada.

`raft_store_snapshot()` compacta até `last_applied` **sem considerar o `match_index` de nenhum
follower**. Assim, um follower indisponível por apenas 2 minutos pode passar a necessitar do
snapshot completo, caso o líder tenha compactado nesse intervalo.

> Este é provavelmente o **ponto de intervenção de menor custo** para a hipótese do estudo
> (ver seção 5.1).

### 3.5 Outros pontos de atrito com bancos grandes

- **Backlog por conexão RAFT:** limite padrão de 500 mensagens (`DEFAULT_MAX_BACKLOG_N_MSGS`),
  ajustável por meio de `cluster/set-backlog-threshold`.
- **Entrada no cluster com banco grande:** até abril de 2024, um servidor novo não conseguia
  ingressar no cluster, pois gerar e enviar o snapshot inicial levava mais tempo que o *probe*
  padrão de 1 segundo. O problema foi corrigido por Ilya Maximets.
- **A compactação bloqueia a thread principal.** Atualmente, o líder transfere a liderança antes
  de compactar. O patch *Minimize compaction downtime* (Felix Huettner, julho de 2025) move a
  compactação para uma thread em segundo plano; em um banco de 833 MB, o bloqueio caiu de
  aproximadamente 3,9 s para 1,2 s. O próprio autor observa que **a instalação do snapshot
  continua bloqueando a thread principal**.

---

## 4. Os três caminhos de ressincronização

Há três mecanismos distintos de ressincronização, que convém não confundir:

| Quem se ressincroniza | Mecanismo | Incremental? | Quando passa a copiar tudo |
|---|---|---|---|
| Follower RAFT pouco atrasado | AppendEntries a partir de `next_index` | **Sim** | Nunca, enquanto as entradas existirem no log do líder |
| Follower RAFT muito atrasado ou novo | InstallSnapshot | **Não**: banco inteiro, em uma única mensagem | Sempre que `next_index < log_start` |
| Cliente (`ovn-controller`, `northd`, relay) | `monitor_cond_since` com `last-txn-id` (OVS ≥ 2.12) | **Sim** | Quando o `last-txn-id` não está mais no histórico do servidor |

Quanto ao terceiro caminho: o histórico de transações (`ovsdb_txn_history_run`, em
`transaction.c`) existe apenas nos modos *clustered* e *relay*. Ele é podado quando ultrapassa
**100 transações** ou quando o número de átomos no histórico supera o do banco inteiro — a partir
desse ponto, enviar o banco é mais barato que enviar o delta. Em um SB com muitos
`ovn-controller` reconectando após a queda de um servidor, esse limite determina quantos clientes
recebem o delta e quantos recebem o banco inteiro.

> Se a alteração em desenvolvimento na Magalu Cloud for do lado dos **clientes**, o tema
> corresponde ao terceiro caminho. Se for entre os **servidores do cluster**, corresponde ao
> segundo. Esse ponto deve ser confirmado com a equipe (seção 9).

---

## 5. Espaço de soluções

As propostas estão ordenadas da mais simples para a mais ambiciosa.

### 5.1 Ideia A — Compactação ciente dos followers (retenção de log)

**Proposta:** ao compactar, o líder mantém as entradas acima de `min(match_index)` dos followers
ativos, limitado por um teto de tamanho ou de idade. Um follower que retorna rapidamente recebe
apenas o que falta (caso 2), sem snapshot.

- **Precedentes:** o etcd mantém `SnapshotCatchUpEntries = 5000` entradas após cada snapshot,
  justamente para permitir que followers lentos alcancem o líder. Segundo o guia da Netdata, o
  CockroachDB só trunca o log após a confirmação das entradas pelos followers.
- **No OVSDB:** alterar o ponto em que `raft_store_snapshot()` define `new_log_start`, ou adiar o
  snapshot enquanto houver follower em *catch-up*.
- **Custo:** maior uso de disco e memória no líder. O teto é obrigatório; caso contrário, um
  follower inativo impediria a compactação indefinidamente.
- **Esforço:** baixo. É a melhor candidata a primeira contribuição.

### 5.2 Ideia B — InstallSnapshot em *chunks* e retomável

**Proposta:** implementar a Figura 13 do artigo (`offset`, `data`, `done`). Cada *chunk* é
confirmado individualmente, e uma conexão interrompida retoma a transferência do ponto em que
parou.

- **Não reduz o volume total transferido**, mas elimina o reinício completo, reduz picos de
  memória (evitando um único JSON de grande porte) e interage melhor com o backlog e o *probe*.
- **Custo:** o snapshot precisa permanecer fixo durante a transferência, o que se relaciona com a
  ideia E.
- **Esforço:** médio (alteração do protocolo RPC e compatibilidade entre versões durante
  *upgrades*).

### 5.3 Ideia C — Snapshot por diferença (*anti-entropy*)

**Proposta:** o follower já possui um snapshot antigo. Em vez do banco inteiro, o líder envia
apenas as **linhas alteradas**. Há duas abordagens:

1. **Por histórico:** o líder armazena os deltas entre snapshots. É simples, mas equivale, na
   prática, à ideia A.
2. **Por hash (Merkle tree, no modelo do Dynamo):** hash por linha (o OVSDB identifica linhas por
   UUID), agregado por tabela e por faixa de UUID. O follower envia sua árvore de hashes; o líder
   compara e envia apenas as linhas divergentes, além das remoções.

- **Benefício:** custo proporcional ao volume alterado, e não ao tamanho do banco. Funciona mesmo
  quando o log já foi compactado.
- **Riscos:** corretude. O estado final deve ser **idêntico** ao do líder no índice do snapshot,
  o que exige um *checksum* de verificação final. Também é necessário tratar mudanças de schema
  (conversão online) e o custo de manter os hashes atualizados a cada transação.
- **Esforço:** alto. Constitui a parte com caráter de **pesquisa original**.

### 5.4 Ideia D — Histórico ampliado para os clientes (`monitor_cond_since`)

**Proposta:** tornar configurável o limite de 100 transações, ou dimensioná-lo de acordo com o
número de clientes ou com uma janela de tempo.

- Ataca o problema de *thundering herd* de `ovn-controller` reconectando após um *failover*.
- **Esforço:** baixo, com resultado mensurável por meio do `ovn-heater`.

### 5.5 Ideia E — Preservação do snapshot em trânsito

**Proposta:** enquanto houver um InstallSnapshot em andamento para algum follower, o líder
**adia a próxima compactação** (ou preserva o snapshot anterior até a confirmação). Isso elimina
o ciclo descrito na seção 3.3.

- **Esforço:** baixo a médio. Combina-se naturalmente com as ideias A e B.

### 5.6 Comparativo

| Ideia | Reduz bytes transferidos? | Evita retrabalho? | Esforço | Originalidade |
|---|---|---|---|---|
| A. Retenção ciente dos followers | Sim (evita o snapshot) | Sim | Baixo | Baixa (existe em outros sistemas) |
| B. Snapshot em *chunks* | Não | Sim | Médio | Baixa (prevista no artigo) |
| C. Snapshot por diferença | Sim, de forma significativa | Sim | Alto | **Alta** |
| D. Histórico de clientes | Sim (lado cliente) | — | Baixo | Baixa |
| E. Snapshot fixo em trânsito | Não | Sim | Baixo a médio | Baixa |

---

## 6. Trabalhos relacionados e precedentes

**No OVSDB, nenhuma das ideias A–E está implementada** no upstream (outubro de 2026). Todas, no
entanto, possuem equivalentes em outros sistemas, o que permite reaproveitar o design, os
parâmetros e os modos de falha já documentados.

### 6.1 Sistemas baseados em RAFT

| Sistema | Comportamento | Ideia equivalente |
|---|---|---|
| **etcd** | Após cada snapshot, mantém `SnapshotCatchUpEntries = 5000` entradas de log. O comentário no código indica que o objetivo é permitir que followers lentos alcancem o líder sem receber snapshot. | **A** |
| **hashicorp/raft** (Consul, Vault, Nomad) | `TrailingLogs` define quantas entradas permanecem no log após o snapshot, para que o follower reaplique o log em vez de receber o snapshot inteiro. `SnapshotThreshold` e `SnapshotInterval` (com *jitter*) controlam quando compactar. Todos os parâmetros são ajustáveis em tempo de execução via `ReloadConfig`. | **A** (configurável) |
| **CockroachDB** | O líder só trunca entradas já confirmadas pelos followers. Se o follower ficar atrás do ponto de truncamento, recebe o *range* inteiro (até 512 MiB). O efeito *snapshot storm* (snapshot → disco lento → mais atraso → mais snapshots) é documentado e monitorado pelas métricas `raftlog_behind` e `range_snapshots_generated`, com *rate limit* para snapshots. | **A**, com observabilidade do problema |
| **TiKV** | GC do log do RAFT com limite flexível (`raft-log-gc-threshold`) e tetos rígidos por contagem e tamanho (`raft-log-gc-count-limit`, `raft-log-gc-size-limit`). | **A** com teto (o mesmo compromisso da seção 5.1) |
| **Artigo do RAFT** | InstallSnapshot em *chunks* com `offset`/`done` (Figura 13), implementado na maioria das bibliotecas RAFT. | **B** |

### 6.2 Bancos replicados não baseados em RAFT

| Sistema | Comportamento | Ideia equivalente |
|---|---|---|
| **Redis** (`PSYNC`) | A réplica envia *replication ID* e *offset*. Se o *offset* ainda estiver no *backlog* circular do primário (padrão de 1 MB, `repl-backlog-size`), recebe apenas o que perdeu (`+CONTINUE`); caso contrário, executa sincronização completa com snapshot RDB (`+FULLRESYNC`). Mantém o ID anterior (`master_replid2`) para continuar de forma incremental **após um *failover***. A métrica `sync_partial_err` indica *backlog* subdimensionado. | **A** e **D**; o uso do ID anterior após *failover* é diretamente aplicável ao histórico de clientes do OVSDB |
| **MySQL Group Replication** | Ao ingressar no grupo, o membro calcula a distância em transações (GTID). Abaixo de `group_replication_clone_threshold`, recupera-se pelo *binlog* (incremental); acima desse limite, ou se o *binlog* necessário já tiver sido removido, realiza *clone* completo. A documentação alerta que um limite baixo em um grupo ativo pode gerar **clones em ciclo infinito**. | **A**, com política explícita entre incremental e completo; mesmo ciclo da seção 3.3 |
| **PostgreSQL `pg_rewind`** | Para reintegrar um ex-primário que divergiu, lê o WAL a partir do ponto de divergência, identifica **apenas os blocos alterados** e copia somente esses do novo primário. É significativamente mais rápido que um *base backup* em bancos grandes. | **C** (diferença guiada pelo log) |
| **PostgreSQL *replication slots* / `wal_keep_size`** | O primário retém o WAL até que a réplica o consuma (*slot*) ou até um tamanho fixo. | **A** (com e sem ciência do follower) |
| **Dynamo / Cassandra (*repair*)** | *Anti-entropy* com Merkle trees: compara árvores de hashes e transfere apenas as faixas de chaves divergentes. | **C** (diferença por hash) |

### 6.3 Trabalhos recentes no ecossistema OVS/OVN

- ***Minimize compaction downtime*** (Felix Huettner, julho de 2025). Na revisão (agosto de 2025),
  Ilya Maximets argumenta que cerca de 1 s sem *commits* ainda é excessivo. Ele sugere um período
  de *cooldown* que impeça transferências de liderança logo após uma eleição e uma melhor
  aleatorização do agendamento de snapshots, além de apontar problemas de *thread-safety* na
  clonagem do banco. **A série não havia sido incorporada** até a última mensagem visível na
  discussão.
- **Contribuição da Magalu Cloud no upstream.** Aeliton G. Silva (`@magalu.cloud`) enviou em
  setembro de 2026 a série *reconnect: Do not spin on a connection with queued data* /
  *Terminate a connection that stops taking data*. Ela trata sessões que deixam de ler e mantêm
  dados retidos no *backlog* (o caso medido apresentou 5,3 MB enfileirados e uma sessão que nunca
  era encerrada). O texto observa que o RAFT não bloqueia a recepção e, por isso, já atinge o
  *timeout*, mas alerta que proteger todo o caminho de *probe* faria com que um líder RAFT
  **nunca encerrasse a conexão com um follower que parou de consumir**. Embora não se trate de
  replicação incremental propriamente dita, a série evidencia que **a Magalu Cloud já atua nessa
  mesma camada** (conexões líder ↔ followers e clientes com *backlog*). Convém verificar com a
  equipe se há relação.

### 6.4 Implicações para a pesquisa

1. **A ideia A possui precedentes sólidos** (etcd, hashicorp/raft, TiKV, Redis, MySQL,
   PostgreSQL). Propor um parâmetro `trailing-logs` configurável no OVSDB é uma contribuição
   defensável e de baixo risco, com valores iniciais derivados desses sistemas.
2. **O ciclo da seção 3.3 é um fenômeno conhecido** (*snapshot storm* no CockroachDB, *clone* em
   ciclo no MySQL), o que fornece evidência para fundamentar a ideia E.
3. **A ideia C não tem análogo direto em implementações RAFT.** Seus precedentes são o
   `pg_rewind` (diferença guiada pelo log) e as Merkle trees (diferença por hash). É nela que se
   concentra a contribuição original.

---

## 7. Invariantes de corretude

Qualquer alteração proposta deve preservar as seguintes propriedades:

1. **Log Matching:** se dois logs possuem uma entrada com o mesmo índice e o mesmo termo, eles são
   idênticos até esse ponto.
2. **State Machine Safety:** nenhum servidor aplica, em um dado índice, uma entrada diferente da
   aplicada por outro servidor.
3. **Equivalência entre snapshot e prefixo do log:** o estado instalado pelo follower deve ser
   exatamente o resultado da aplicação das entradas `1..last_index`. Essa propriedade é crítica
   na ideia C, que deve validá-la por meio de *checksum*.
4. **Composição do cluster:** o snapshot transporta `servers` e `election_timer`; um delta não
   pode omitir esses metadados.
5. **Durabilidade:** um follower não pode confirmar a instalação antes de o estado estar
   persistido em disco (`raft_save_snapshot`).

---

## 8. Plano experimental

O objetivo é medir **em que situações** o snapshot completo ocorre e **qual é o seu custo**,
estabelecendo a linha de base que qualquer proposta precisará superar.

### 8.1 Ambiente

- Três instâncias de `ovsdb-server` em modo cluster com o schema **OVN_Southbound**, o banco de
  maior tamanho e maior taxa de escrita. Podem ser executadas em containers ou VMs; o
  `ovn-fake-multinode` atende a esse propósito.
- Gerador de carga: `ovn-heater` (criação de chassis, portas e ACLs) ou um script com
  `ovn-sbctl`/`ovsdb-client transact` em laço.

```bash
# criar o cluster (nó 1) e incluir os demais membros
ovsdb-tool create-cluster sb1.db ovn-sb.ovsschema tcp:10.0.0.1:6644
ovsdb-tool join-cluster  sb2.db OVN_Southbound tcp:10.0.0.2:6644 tcp:10.0.0.1:6644
ovsdb-tool join-cluster  sb3.db OVN_Southbound tcp:10.0.0.3:6644 tcp:10.0.0.1:6644

# observação
ovs-appctl -t /var/run/ovn/ovnsb_db.ctl cluster/status OVN_Southbound   # next_index, match_index, log
ovs-appctl -t /var/run/ovn/ovnsb_db.ctl memory/show
ovs-appctl -t /var/run/ovn/ovnsb_db.ctl ovsdb-server/compact            # forçar compactação
grep -E "sending snapshot|installed snapshot|out-of-date snapshot" ovsdb-server-sb.log
```

### 8.2 Injeção de falhas

- **Follower suspenso:** `kill -STOP <pid>` por N segundos, seguido de `kill -CONT`. Simula um nó
  travado sem perda imediata da conexão TCP.
- **Follower desligado:** `systemctl stop` por N minutos.
- **Rede degradada:** `tc qdisc add dev eth0 root netem delay 50ms loss 1%` no follower.

### 8.3 Variáveis

- Tamanho do banco: 50 MB, 200 MB e 800 MB.
- Taxa de escrita: transações por segundo.
- Tempo de indisponibilidade do follower: 30 s, 2 min, 10 min e 30 min (cruzando ou não a janela
  de compactação de 10 a 20 minutos).

### 8.4 Métricas

- **Caminho adotado:** AppendEntries (caso 2) ou snapshot (caso 1), identificado pelos logs.
- Bytes transferidos (contador da interface ou `ss -i`).
- Tempo até o `match_index` do follower alcançar o `commit_index` do líder.
- Consumo de CPU e memória do líder durante a ressincronização.
- Latência das transações dos clientes durante a ressincronização.
- Número de ocorrências de *"out-of-date snapshot, starting over"*.

### 8.5 Hipóteses

- **H1:** a probabilidade de snapshot completo depende mais do **tempo de indisponibilidade em
  relação à janela de compactação** do que do volume de escritas.
- **H2:** com banco grande e alta taxa de escrita, existe um limiar a partir do qual o ciclo da
  seção 3.3 se repete diversas vezes.
- **H3:** reter as últimas *K* entradas (ideia A) elimina o snapshot em indisponibilidades curtas,
  com custo de disco previsível.

A hipótese H3 pode ser validada com uma alteração pontual em `raft_store_snapshot()`, constituindo
a primeira contribuição concreta.

---

## 9. Questões para a equipe da Magalu Cloud

1. A alteração ocorre **entre os servidores do cluster** (InstallSnapshot) ou **do servidor para
   os clientes** (`monitor_cond_since` / relays)?
2. Qual banco é mais afetado: NB, SB ou IC-SB? Qual é o tamanho típico em produção?
3. O gatilho é a reinicialização de followers, instabilidade de rede entre racks ou AZs, ou
   *rolling upgrades*?
4. A abordagem adotada é retenção de log, snapshot por diferença, *chunking* ou outra?
5. Há intenção de submeter a alteração ao upstream? Em caso afirmativo, o experimento da seção 8
   pode ser alinhado como linha de base.
6. Existe relação com a série *reconnect: Terminate a connection that stops taking data*
   (Aeliton Silva, setembro de 2026)?

---

## 10. Relação com alta disponibilidade e topologias multi-site

Os bancos IC-NB e IC-SB utilizam **o mesmo `ovsdb-server` em modo cluster**; portanto, toda a
análise anterior se aplica igualmente a eles. O cenário com múltiplos sites é o mais sensível,
pelos seguintes motivos:

- enlaces entre AZs apresentam maior latência e perda, o que torna mais frequentes os followers
  atrasados;
- um árbitro de quórum distante (como o nó `quorum` do laboratório `advanced-ovn-ic`) recai no
  caso 1 com maior facilidade;
- transferir um snapshot completo por WAN é mais custoso do que dentro de um mesmo datacenter.

Por esses motivos, as ideias A (retenção) e C (diferença) tornam-se ainda mais relevantes em
topologias geograficamente distribuídas, independentemente do uso do OVN-IC.

---

## 11. Referências

**Código (openvswitch/ovs, branch `main`):**

- `ovsdb/raft.c`: `raft_handle_append_reply`, `raft_send_install_snapshot_request`,
  `raft_handle_install_snapshot_request__`, `raft_handle_install_snapshot_reply`,
  `raft_store_snapshot`
- `ovsdb/storage.c`: `schedule_next_snapshot`, `ovsdb_storage_should_snapshot`
- `ovsdb/transaction.c`: `ovsdb_txn_history_run`
- `Documentation/ref/ovsdb-server.7.rst`, seção 4.1.15 (`monitor_cond_since`)
- `Documentation/topics/ovsdb-relay.rst`

**Lista ovs-dev:**

- [ovsdb: raft: Fix inability to join a cluster with a large database](https://www.mail-archive.com/ovs-dev@openvswitch.org/msg84829.html) (I. Maximets, abr/2024)
- [Minimize compaction downtime](https://www.mail-archive.com/ovs-dev@openvswitch.org/msg96287.html) (F. Huettner, jul/2025)
- [raft: Transfer leadership before creating snapshots](https://mail.openvswitch.org/pipermail/ovs-dev/2021-May/382703.html) (2021)
- [Revisão de Ilya Maximets em *Minimize compaction downtime*](https://www.mail-archive.com/ovs-dev@openvswitch.org/msg96682.html) (ago/2025)
- [reconnect: Terminate a connection that stops taking data, v2](http://www.mail-archive.com/ovs-dev@openvswitch.org/msg106758.html) (A. G. Silva, Magalu Cloud, set/2026)

**Implementações de referência (seção 6):**

- [hashicorp/raft: `Config.TrailingLogs`, `SnapshotThreshold`, `SnapshotInterval`](https://pkg.go.dev/github.com/hashicorp/raft)
- [TiKV: Raftstore config (raft-log-gc-*)](https://tikv.org/docs/4.0/tasks/configure/raftstore/)
- [Redis: partial resynchronization (PSYNC, backlog)](https://oneuptime.com/blog/post/2026-03-31-redis-how-partial-resynchronization-works-in-redis/markdown)
- [MySQL Group Replication: cloning vs binary log](https://dev.mysql.com/doc/en/group-replication-cloning.html)
- [PostgreSQL: pg_rewind](https://postgresql.org/docs/current/app-pgrewind.html)

**RAFT e sistemas comparáveis:**

- D. Ongaro e J. Ousterhout, *In Search of an Understandable Consensus Algorithm*, 2014. Seção 7 e
  Figura 13 (InstallSnapshot em *chunks*).
- D. Ongaro, *Consensus: Bridging Theory and Practice*, tese de doutorado, 2014. Capítulo 4
  (mudança de membros) e capítulo 5 (compactação).
- etcd: `DefaultSnapshotCatchUpEntries = 5000` em [`server/etcdserver/server.go`](https://chromium.googlesource.com/external/github.com/coreos/etcd/+/HEAD/server/etcdserver/server.go)
- [CockroachDB Raft snapshot storm](https://www.netdata.cloud/guides/cockroachdb/cockroachdb-raft-snapshot-storm/) (Netdata)
- G. DeCandia et al., *Dynamo: Amazon's Highly Available Key-value Store*, SOSP 2007.
  *Anti-entropy* com Merkle trees (base da ideia C).
