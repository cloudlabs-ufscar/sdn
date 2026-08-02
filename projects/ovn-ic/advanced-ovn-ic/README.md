# Multi-AZ DBaaS-style lab over OVN-IC (CLO-73)

**Status: implemented and validated end-to-end on three VMs, provisioned with Ansible.**
The operator quick-reference lives in [`ansible/README.md`](ansible/README.md); this
document is the full story — design, architecture, how to run it from scratch, every
test, the gotchas, and how to push it closer to production.

This revision rebuilds the lab to match [`advanced-ovn-ic.pdf`](advanced-ovn-ic.pdf).
See [What changed in this revision](#what-changed-in-this-revision) for the delta
against the previous single-plane version.

## Table of contents

- [Context](#context)
- [Goal](#goal)
- [Architecture](#architecture)
- [The two planes](#the-two-planes)
- [The cell edge (Gateway Nodes)](#the-cell-edge-gateway-nodes)
- [How the OVN databases are arranged](#how-the-ovn-databases-are-arranged)
- [Addressing](#addressing)
- [Traffic paths this lab exercises](#traffic-paths-this-lab-exercises)
- [How it is implemented — Ansible](#how-it-is-implemented--ansible)
- [Running it from zero](#running-it-from-zero)
- [Testing — every check](#testing--every-check)
- [OVN-IC gotchas](#ovn-ic-gotchas)
- [Results](#results)
- [What changed in this revision](#what-changed-in-this-revision)
- [Design decisions](#design-decisions)
- [Improving toward a more real scenario](#improving-toward-a-more-real-scenario)
- [Repository layout](#repository-layout)

---

## Context

This lab is the evolution of the [single-service OVN-IC proof of concept](../pratice-ovn-ic/README.md),
where two simulated AZs only validated intra/inter-AZ **ICMP** between *network namespaces*.
That setup proved the OVN-IC control plane (transit switch, gateways, route advertisement)
but, as its own README notes, it **masks the datapath**: no real TCP service, no MTU/encap
pressure, no firewall between chassis, workloads that are just namespaces.

This project takes the next step asked by issue
[CLO-73](https://linear.app/cloudlabs/issue/CLO-73/configure-ovn-ic-federation):
**workloads in different Incus clouds, federated by OVN-IC, running a useful workload.**

It is intentionally a *scaled-down* adaptation of the production Magalu architectures
(`dd-azdc-architecture`, `dd-xaas-network`): one tenant federated across two AZs, running a
**real three-tier application that is itself split across the AZs** — a React dashboard in
AZ1, a Java backend in AZ2, and a managed PostgreSQL the backend consumes over a
**separate management network**.

## Goal

Go beyond ping: exercise the OVN-IC **datapath** with real TCP traffic (HTTP + the Postgres
wire protocol) crossing GENEVE tunnels between two separate Incus clouds.

The application is deliberately **split across the AZs** so that simply using it is the
test: the React dashboard runs in AZ1, the Java backend and the database run in AZ2, so
every page refresh crosses the client transit switch and then the management plane. The
dashboard's job is to display the state of the very infrastructure carrying it.

On top of that it reproduces three structural properties of the production design:

1. **Two isolated planes** — a tenant-facing *client* plane and an operator-facing
   *management* plane, each federated by its **own** transit switch, sharing one GENEVE
   fabric. The database lives on the management plane and is unreachable from the tenant
   network by construction.
2. **A per-cell edge** — both planes hand north-south traffic to one gateway tier per AZ,
   which SNATs each plane to its own external address.
3. **A highly available interconnect control plane** — IC-NB/IC-SB as a 3-member RAFT
   cluster, so no single VM can take the federation's shared databases down.

---

## Architecture

Three VMs. Two are self-contained AZs ("cells"): an Incus cloud, its own OVN control
plane, `ovn-ic`, a chassis and a gateway tier. The third is a **quorum arbiter** — it runs
no chassis, no `br-int` and no GENEVE, only the third member of the interconnect database
cluster.

```
   ┌──────────────────── ovn-ic-global-db — RAFT, 3 members ────────────────────┐
   │  IC-NB :6645 / IC-SB :6646  (clients)   ·   :6647 / :6648  (RAFT peers)    │
   │        az1  ◄───────────────► az2  ◄───────────────► quorum (arbiter)      │
   └───────────────────────────────────────────────────────────────────────────┘
         ▲                                ▲                        ▲
      ovn-ic (az1)                    ovn-ic (az2)          no chassis, no br-int,
                                                            no GENEVE — only a vote
 ══════════════ CELL az1 ══════════════║══════════════ CELL az2 ══════════════════
 HOST 172.18.3.175  (pub 201.23.73.63) ║  HOST 172.18.33.126 (pub 201.23.74.156)
 NB 6641 · SB 6642 · northd · ovn-controller (each cell has its own)
                                       ║
 ┌── CLIENT plane ──────────────────┐  ║  ┌── CLIENT plane ──────────────────┐
 │ ls-client-az1   10.10.1.0/24     │  ║  │ ls-client-az2   10.10.2.0/24     │
 │  • app-vm-1 .10:80  REACT+nginx  │  ║  │  • app-vm-2 .20:8080  JAVA API   │
 │  • OVN-LB  VIP 10.10.1.100:80    │  ║  │  • OVN-LB  VIP 10.10.2.100:80    │
 │  • OVN-LB  VIP 10.10.1.200 ──────┼──╫──┼──► app-vm-2:8080  (service VIP)  │
 │ lr-client-az1 ─ lrp-ts .1 ●──────┼──╫──┼──● lrp-ts .2 ─ lr-client-az2     │
 └───────────────┬──────────────────┘ ts-client └──────────┬──────────────────┘
                 │                  (169.254.100.0/24)     │
 ┌── MGMT plane ─┼──────────────────┐  ║  ┌────────────────┼─────────────────┐
 │ ls-mgmt-az1   │ 10.20.1.0/24     │  ║  │ ls-mgmt-az2    │ 10.20.2.0/24    │
 │  • app-vm-1 .10 (2nd NIC)        │  ║  │  • app-vm-2 .20 (2nd NIC)        │
 │                                  │  ║  │  • db-vm    .10  Postgres  ◄──── │
 │                                  │  ║  │       (only the backend gets here)│
 │ lr-mgmt-az1 ─ lrp-ts .1 ●────────┼──╫──┼──● lrp-ts .2 ─ lr-mgmt-az2       │
 └───────────────┬──────────────────┘ ts-mgmt  └──────────┬──────────────────┘
                 │                  (169.254.200.0/24)    │
        ┌────────┴────────┐                      ┌────────┴────────┐
        │ lr-edge-az1     │  "Gateway Nodes"     │ lr-edge-az2     │
        │ SNAT client→.2  │  + Edge Firewall     │ SNAT client→.2  │
        │ SNAT mgmt  →.3  │  (no plane-to-plane) │ SNAT mgmt  →.3  │
        └────────┬────────┘                      └────────┬────────┘
             br-ex → host MASQUERADE → ens3 → INTERNET
                                       ║
  underlay: GENEVE/UDP 6081  172.18.3.175 ◄═════► 172.18.33.126
            carries BOTH transit switches (isolation is logical, not physical)
```

Three layers stack up:

- **Underlay** (physical): the VMs reach each other on their private IPs; GENEVE
  (UDP 6081) flows there. Both transit switches ride this one fabric. (The public
  `201.23.x` IPs are 1:1 NAT, used only for north-south, never for the tunnel.)
- **Logical overlay** (OVN): each cell has two switches and two plane routers, plus an
  edge router; each plane router joins its peer through its own transit switch, and
  OVN-IC advertises/learns each plane's connected routes **on that plane's transit
  switch only**.
- **Application**: containers on `br-int` — React + nginx in AZ1, a Java service in AZ2,
  PostgreSQL on the management plane. See [The application](#the-application).

### The application

A three-tier app, split so that *using it* is the interconnect test. Every dashboard
refresh takes this path:

```
browser ──ssh tunnel──► AZ1 VIP 10.10.1.100:80
                          └─► nginx + React bundle        app-vm-1 · AZ1 · client plane
                                └─► /api/*  ──► AZ1 service VIP 10.10.1.200:80
                                                  └─► OVN LB DNAT ──► ts-client ──► GENEVE   ◄── CROSS-AZ
                                                        └─► Java backend  app-vm-2 · AZ2 · client plane
                                                              └─► JDBC ──► db-vm 10.20.2.10  ◄── MGMT PLANE
```

| Tier | Where | Stack | Listens on |
|---|---|---|---|
| Frontend | `app-vm-1`, **AZ1** | React 18 + Vite, served by nginx | client NIC `:80` |
| Backend | `app-vm-2`, **AZ2** | Java 21, JDK `HttpServer` + JDBC | client NIC `:8080` |
| Database | `db-vm`, **AZ2** | PostgreSQL 16 | mgmt NIC `:5432` |

The dashboard is an **infrastructure view**: it renders the real state of the fabric
underneath it. Each AZ host reads its own OVN state (`ovn-nbctl`, `ovn-ic-sbctl`,
`ovs-vsctl`, `ovs-appctl cluster/status`) and writes it into the `infra_state` table over
the management plane; the Java backend reads that table and serves it; React displays it.
So the infrastructure view itself travels the whole architecture to reach your screen.

Two implementation notes, both driven by the 2 vCPU workloads behind a double NAT:
the backend is plain JDK (no Maven/Spring — nothing here needs a framework, and pulling a
dependency tree through the SNAT path is slow and fragile), and nginx proxies `/api/*`
rather than the browser calling AZ2 directly, so the browser only ever needs to reach AZ1
while the cross-AZ hop still happens inside the fabric.

### The two planes

This is the core of the design and the thing the previous revision did not have.

| | **Client plane** | **Management plane** |
|---|---|---|
| Purpose | tenant traffic — the web service | operator/service traffic — the DBaaS |
| Switch / router | `ls-client-<az>` / `lr-client-<az>` | `ls-mgmt-<az>` / `lr-mgmt-<az>` |
| Federated by | `ts-client` (`169.254.100.0/24`) | `ts-mgmt` (`169.254.200.0/24`) |
| Subnets | `10.10.1.0/24` (AZ1), `10.10.2.0/24` (AZ2) | `10.20.1.0/24` (AZ1), `10.20.2.0/24` (AZ2) |
| Load balancer | yes — one VIP per AZ (+ a service VIP in AZ1) | no |
| Members | `app-vm-1` (React), `app-vm-2` (Java) | `app-vm-1`, `app-vm-2`, **`db-vm`** |

The application tiers are **dual-homed**: they serve HTTP on their client NIC and reach the
database on their management NIC. `db-vm` has a **single** NIC, on the management plane —
so there is no address the client plane could even use to name it.

The isolation is **logical, not physical**. Both transit switches are carried by the same
GENEVE tunnel between the same two chassis; what separates them is that they are distinct
OVN datapaths, and `ovn-ic` advertises each plane's routes only onto its own transit
switch. The verification suite asserts this directly: `lr-client-*` must learn the peer's
`10.10.x` subnet and **no** `10.20.x` route, and vice versa.

### The cell edge (Gateway Nodes)

Both plane routers default-route into one **edge gateway router** per cell
(`lr-edge-<az>`), which owns the uplink to `br-ex` and SNATs each plane to its own
external address (`192.168.241.2` client, `192.168.241.3` mgmt on az1). That is the
design's *Gateway Nodes* block, and it exists for two reasons:

1. **It is what the design draws** — one gateway tier per cell, with both planes' SNAT
   arrows pointing into it.
2. **It is what makes the load balancer legal.** OVN refuses to program a load balancer
   on a router with more than one distributed gateway port
   (`Load-balancer is not supported yet when there is more than one distributed gateway
   port on the router`). A plane router that also owned an external port would have two:
   its transit port and its external port. Moving north-south onto the edge leaves each
   plane router with exactly one.

The edge necessarily knows a route into *both* planes — it has to, in order to send
replies back. That alone would re-connect them: a client-plane workload could reach its
default gateway, be routed to the edge, and be routed straight back down into the
management plane, silently undoing the isolation. Two OVN logical-router policies drop
any plane-to-plane transit at the edge. **That is the "Edge Firewall" in the diagram**, and
`verify.yml` tests it in both directions.

### How the OVN databases are arranged

Each AZ keeps its **own** control plane; only the two interconnect databases are shared —
and those are now clustered.

| Database  | Port(s) | Scope | Runs on | Who connects to it |
|-----------|---------|-------|---------|--------------------|
| local NB  | 6641 | per-AZ, private | each AZ (127.0.0.1) | that AZ's `ovn-northd`, `ovn-nbctl`, `ovn-ic` |
| local SB  | 6642 | per-AZ, private | each AZ (127.0.0.1) | that AZ's `ovn-northd`, `ovn-controller`, `ovn-ic` |
| **IC-NB** | 6645 client / 6647 RAFT | **global, clustered** | az1 + az2 + quorum | **both** AZs' `ovn-ic` |
| **IC-SB** | 6646 client / 6648 RAFT | **global, clustered** | az1 + az2 + quorum | **both** AZs' `ovn-ic` |

Both transit switches are declared in IC-NB and `ovn-ic` copies them into every local NB;
registered gateways and the advertised/learned routes live in IC-SB. A workload's traffic
never depends on the *other* AZ's NB/SB — only on those two shared IC databases plus the
GENEVE tunnel.

**Why three members and not two.** RAFT commits require a strict majority. A majority of 2
is 2, so a two-member cluster stops accepting writes the moment either member is lost —
no better than a single node. A majority of 3 is 2, so a three-member cluster keeps
working when any one member goes away. The quorum VM exists purely to be that third vote;
it carries no chassis and no workloads. `verify_ha.yml` proves this by stopping the current
leader and asserting that the survivors still elect a leader and still **commit writes**.

Note that the data plane is independent of all this: with the interconnect databases down,
already-programmed flows keep forwarding. What an IC outage costs you is *topology
changes*, not traffic — also asserted by `verify_ha.yml`.

### Addressing

| Plane / element | AZ1 (host az1) | AZ2 (host az2) | Shared |
|---|---|---|---|
| Host (underlay) | `172.18.3.175` | `172.18.33.126` | GENEVE UDP 6081 |
| Quorum arbiter | — | — | `172.18.3.240` (IC cluster only) |
| **Client** subnet | `10.10.1.0/24` (gw .1) | `10.10.2.0/24` (gw .1) | `ts-client` `169.254.100.0/24` |
| **Client** VIP | `10.10.1.100:80` | `10.10.2.100:80` | both front both backends |
| **Mgmt** subnet | `10.20.1.0/24` (gw .1) | `10.20.2.0/24` (gw .1) | `ts-mgmt` `169.254.200.0/24` |
| `app-vm-N` | client `10.10.1.10` · mgmt `10.20.1.10` | client `10.10.2.20` · mgmt `10.20.2.20` | |
| `db-vm` | — | mgmt `10.20.2.10` only | Postgres :5432 |
| Edge (internal) | `192.168.251.0/24` (edge .254) | `192.168.252.0/24` (edge .254) | advertised over IC |
| Provider (br-ex) | `192.168.241.0/24` (host .1) | `192.168.242.0/24` (host .1) | **not** advertised |
| SNAT addresses | client `.241.2` · mgmt `.241.3` | client `.242.2` · mgmt `.242.3` | |
| MTU | overlay workloads = `1442` (1500 − 58 GENEVE), both NICs | | |

The edge subnets are deliberately advertised across the interconnect while the provider
subnets are not: when a load balancer picks the backend in the *other* AZ, the edge SNATs
the request to its own edge address, and the remote backend can only reply if its AZ has
learned a route back to it.

---

## Traffic paths this lab exercises

1. **The application itself, crossing AZs.** The React frontend in AZ1 calls the Java
   backend in AZ2 through a load-balanced VIP, so every dashboard refresh traverses
   `ts-client` over GENEVE.
2. **Cross-AZ DBaaS query over the management plane.** `app-vm-1` (AZ1, mgmt NIC) →
   `db-vm` (AZ2): a real Postgres connection over `ts-mgmt`. This is where MTU and GENEVE
   encap overhead bite.
3. **Intra-AZ query for contrast.** `app-vm-2` (AZ2) → `db-vm` (AZ2): same query, local
   path — a clean before/after comparison against path #2 in a single test run.
4. **North-south via the edge.** Every plane reaches the internet through its cell's edge
   router, SNATed to its own external address.
5. **Plane isolation (a path that must NOT work).** The client plane has no route to the
   database, in either direction, and the edge will not bridge the two.

---

## How it is implemented — Ansible

Everything is provisioned with **Ansible** (no bash setup scripts), run from a **WSL
Ubuntu control node** that already has the `az1`/`az2`/`quorum` SSH aliases. The VMs only
need SSH + Python 3 and passwordless sudo.

- **`inventory.ini`** — group `azs` (`az1`, `az2`) and group `ic_cluster`
  (`az1`, `az2`, `quorum`).
- **`group_vars/all.yml`** — values shared by every host (versions, ports, the two transit
  switches, the IC cluster definition, supernets, MTU, DB credentials).
- **`host_vars/az1.yml` / `az2.yml`** — each AZ's identity, its edge, and a `planes:` list
  that fully describes the client and mgmt stacks (switch, router, subnet, transit switch
  and address, edge address, SNAT address, VIP). Workloads carry a `nics:` list, one entry
  per plane. **Adding a plane or a workload NIC is a data change, not a code change.**
- **`host_vars/quorum.yml`** — just the arbiter's address.
- **roles** (one concern each):

  | role | what it does |
  |------|--------------|
  | `common`       | apt base (OVS/OVN), disables the apt OVN services, **compiles `ovn-ic` + IC schemas from source** (the Ubuntu package omits them), and installs the **logrotate policy** that keeps a crash-looping daemon from filling the disk. Guarded so the ~10-20 min build runs once. |
  | `ic_cluster`   | the **3-member RAFT** IC-NB/IC-SB: installs `ovsdb-server` on the arbiter, copies the IC schemas to it, `create-cluster` on the bootstrap member then `join-cluster` on the others, and waits for all members plus a leader. |
  | `ovn_central`  | per-AZ NB/SB `.db` files and `ovn-northd`, as systemd units. |
  | `ovn_chassis`  | OVS chassis config (GENEVE encap-ip), `ovn-controller`, `br-ex`, host `ip_forward` + MASQUERADE, firewall for GENEVE + every IC cluster port. |
  | `ovn_topology` | `NB_Global` identity/route options, **both** transit switches in IC-NB, and per plane: LS/LR/LRP, gateway chassis on the transit LRP, then attach to the propagated transit switch. Encodes the gotchas. |
  | `ovn_services` | the **edge gateway router** (uplink, per-plane SNAT, Edge Firewall policies) and the **per-AZ load balancer** (VIP → both backends, attached to the client switch *and* the edge router). |
  | `incus`        | installs Incus and `admin init --minimal`. |
  | `workloads`    | one OVN logical port **per NIC**, Incus containers with a p2p veth per plane plugged into `br-int`, static netplan with per-plane routes, then the three tiers in dependency order: PostgreSQL → Java backend → infrastructure-state publish → React frontend. |

Idempotency is real: re-running any playbook reports `changed=0`, with one deliberate
exception — `ovn_topology` always restarts `ovn-ic` (gotcha #3).

---

## Running it from zero

On the **WSL** control node:

```bash
# 1) install Ansible (once)
sudo apt update && sudo apt install -y pipx
pipx ensurepath && pipx install --include-deps ansible
exec $SHELL

cd ~/magalu/sdn/projects/ovn-ic/advanced-ovn-ic/ansible

# 2) bring it up, stage by stage (recommended the first time)
ansible-playbook ping.yml         # SSH + Python reachable
ansible-playbook common.yml       # deps + compile OVN v24.03.6 / OVS v3.3.0  (~10-20 min)
ansible-playbook ic_cluster.yml   # 3-member RAFT IC-NB/IC-SB (az1 + az2 + quorum)
ansible-playbook central.yml      # per-AZ NB/SB + northd
ansible-playbook chassis.yml      # OVS chassis + ovn-controller + br-ex + host SNAT
ansible-playbook topology.yml     # both planes + both transit switches + ovn-ic
ansible-playbook services.yml     # edge gateway router + per-AZ load balancer
ansible-playbook incus.yml        # install + init Incus
ansible-playbook workloads.yml    # dual-NIC containers + Postgres + Java + React

ansible-playbook verify.yml       # the test suite
ansible-playbook verify_ha.yml    # the interconnect HA test (disruptive)
```

`ansible-playbook site.yml` runs stages 2-8 in one go. Everything is idempotent, so you can
re-run any stage at any time.

**Changing the shape of the topology** (renaming routers, adding a plane) needs a teardown
first, because the roles are idempotent for *values* but cannot rename or remove objects a
previous design created:

```bash
ansible-playbook reset.yml && ansible-playbook site.yml
```

---

## Testing — every check

`verify.yml` is **assert-based**: a broken path fails the run instead of printing something
you have to read yourself.

```bash
ansible-playbook verify.yml         # T1-T6 below
ansible-playbook verify_ha.yml      # H1-H4 below (stops a database briefly)
```

| Group | What it proves |
|---|---|
| **T1** control plane | both transit switches declared in IC-NB; both gateways registered in IC-SB; GENEVE tunnel to the peer's encap-ip; **and each plane's router learned only its own plane's remote subnet** |
| **T2** plane isolation | the client plane cannot reach the database; the mgmt plane cannot reach the client plane; `db-vm` has no client NIC at all |
| **T3** client plane | each AZ's VIP answers 12/12 for its own tier; the AZ1 **service VIP** is answered 8/8 by the backend in AZ2 (the load balancer doing cross-AZ DNAT over `ts-client`) |
| **T4** mgmt plane | the backend reads Postgres over its mgmt NIC; a raw Postgres wire-protocol session opens from both AZs; RTT reported cross-AZ vs intra-AZ |
| **T5** north-south | every workload reaches the internet through its plane's SNAT |
| **T6** MTU | `1414`B passes and `1415`B is rejected — the GENEVE ceiling is exactly where it should be |
| **T7** application | the frontend VIP serves the React bundle; `/api/status` through it is answered **by the backend in the other AZ** with a live DB; the backend reports one client + one mgmt NIC at MTU 1442; the dashboard shows infra rows from **both** AZs with none in error |
| **H1-H4** interconnect HA | 3 members healthy → stop the **leader** → a survivor is elected and still **commits writes** → the data plane is unaffected → the member rejoins |

Manual poking, on whichever VM you `ssh` into. Export the DB locations first so the `ovn-*`
tools don't need `--db` (no `sudo` needed — the local DBs are on local TCP):

```bash
export OVN_NB_DB=tcp:127.0.0.1:6641
export OVN_SB_DB=tcp:127.0.0.1:6642
export OVN_IC_NB_DB=tcp:172.18.3.175:6645,tcp:172.18.33.126:6645,tcp:172.18.3.240:6645
export OVN_IC_SB_DB=tcp:172.18.3.175:6646,tcp:172.18.33.126:6646,tcp:172.18.3.240:6646
```

**Control plane:**
```bash
ovn-ic-nbctl show                       # ts-client AND ts-mgmt
ovn-ic-sbctl show                       # both gateways + transit ports
ovn-nbctl lr-route-list lr-client-az1   # 10.10.2.0/24 (learned) — and no 10.20.x
ovn-nbctl lr-route-list lr-mgmt-az1     # 10.20.2.0/24 (learned) — and no 10.10.x
sudo ovs-vsctl show | grep -A2 geneve   # the tunnel to the peer's encap-ip

# interconnect cluster health (any member):
sudo ovs-appctl -t /opt/ovn-lab/ic-nb.ctl cluster/status OVN_IC_Northbound
sudo ovs-appctl -t /opt/ovn-lab/ic-sb.ctl cluster/status OVN_IC_Southbound
```

**Data plane — the two planes:**
```bash
# client plane, inter-AZ (over ts-client):
sudo incus exec app-vm-1 -- ping -c3 -I eth0 10.10.2.20

# mgmt plane, inter-AZ (over ts-mgmt) and intra-AZ:
sudo incus exec app-vm-1 -- ping -c3 -I eth1 10.20.2.10     # AZ1 -> db, cross-AZ
sudo incus exec app-vm-2 -- ping -c3 -I eth1 10.20.2.10     # AZ2 -> db, local

# isolation: these MUST fail
sudo incus exec app-vm-1 -- ping -c2 -W2 -I eth0 10.20.2.10   # client -> db
sudo incus exec app-vm-1 -- ping -c2 -W2 -I eth1 10.10.2.20   # mgmt -> client
```

**MTU / GENEVE pressure.** Workload MTU is 1442; with DF the largest ICMP payload is 1414:
```bash
sudo incus exec app-vm-1 -- ping -c2 -M do -s 1414 10.20.2.10   # OK
sudo incus exec app-vm-1 -- ping -c2 -M do -s 1415 10.20.2.10   # fails
```

**L4 — the application (HTTP + Postgres):**
```bash
# on az1 — the whole chain: nginx -> service VIP -> ts-client -> Java -> Postgres
curl -s http://10.10.1.100/api/status  | python3 -m json.tool   # answered by app-vm-2, in AZ2
curl -s http://10.10.1.100/api/infra   | head -c 400            # infra state out of the DB
curl -s -o /dev/null -w '%{http_code} in %{time_total}s\n' http://10.10.1.100/api/status

# the cross-AZ service VIP on its own (from inside the client plane):
sudo incus exec app-vm-1 -- curl -s http://10.10.1.200/api/health ; echo

# on az2 — its own VIP fronts the backend tier locally:
curl -s http://10.10.2.100/api/health ; echo

# raw Postgres wire protocol over the mgmt plane (cross-AZ from az1, local from az2):
sudo incus exec app-vm-1 -- env PGPASSWORD=apppass \
  psql -h 10.20.2.10 -U appuser -d appdb -tAc 'SELECT message FROM lab_info LIMIT 1'
```

**North-south:**
```bash
sudo incus exec app-vm-1 -- curl -sI https://example.org | head -1   # HTTP 200 (client plane)
sudo incus exec db-vm    -- curl -sI https://example.org | head -1   # HTTP 200 (mgmt plane)
```

**The dashboard.** Tunnel to AZ1's frontend VIP and open it in a browser:
```bash
ssh -L 8080:10.10.1.100:80 az1     # then browse http://localhost:8080/
```
You get the React dashboard: the request path with the cross-AZ and plane-changing hops
highlighted and timed, the backend's two NICs (client + mgmt, MTU 1442) read from its own
kernel, and the live infrastructure table from both AZs. It polls every 5s, so a broken
interconnect turns the page red within seconds — the fastest way to see the fabric's state.

**Troubleshooting (where to look):**
```bash
sudo systemctl status ovn-ic ovn-northd ovn-controller ovn-ic-nb-db ovn-ic-sb-db
sudo tail -n 40 /opt/ovn-lab/ovn-ic.log
sudo tail -n 40 /opt/ovn-lab/ovn-northd.log     # LB / NAT complaints show up here
sudo ovs-vsctl show
sudo incus exec <name> -- ip -4 addr show       # both NICs, mtu 1442?
```

---

## OVN-IC gotchas

The five hard-won lessons from [`pratice-ovn-ic`](../pratice-ovn-ic/README.md) all still
apply and are encoded in the `ovn_topology` role:

1. **AZ identity comes from `NB_Global.name`.**
2. **Transit switches are created in IC-NB** and propagated by `ovn-ic` — never created
   manually in a local NB. (Now two of them.)
3. **Restart `ovn-ic` after the topology is fully built** to avoid the startup race.
4. **`ic-route-adv` / `ic-route-learn` live on `NB_Global.options`** (OVN 24.03.6), not on
   the `Logical_Router`.
5. **Pin a gateway chassis on each transit LRP** (`lrp-set-gateway-chassis`) or the data
   plane stays broken even with routes advertised.

Three surfaced in the previous revision:

6. **`ovn-ic` needs an explicit `--unixctl`.** The source build defaults its control
   socket to `/usr/local/var/run/ovn/`, which doesn't exist, so it crash-loops with
   `binding failed: No such file or directory`.
7. **Incus 6.0's native OVS client can't attach to `br-int`**, so the workload NICs are
   `nictype=p2p`: Incus builds the veth pair and we add the host side to `br-int` with
   `ovs-vsctl ... iface-id=<lsp>` ourselves.
8. **Grant the app role on the demo table**, or `appuser` connects fine but gets
   `permission denied for table lab_info`.

And five more surfaced bringing *this* revision up:

9. **`ovn-controller` needs `/run/ovn` to exist, and cannot be told otherwise.** It binds
   its control socket at `/var/run/ovn/<pid>.ctl` and will not create the directory. Unlike
   `ovn-ic`, the apt-shipped binary has **no `--unixctl` option**, so the only fix is
   `RuntimeDirectory=ovn` in the unit. `/run` is a tmpfs, so after a reboot the directory
   is gone and every start fails. Both VMs were found in this state, restarting every two
   seconds — **the crash loop had written an 8 GB log and filled a 38 GB disk**, taking the
   whole lab down. The `common` role now also installs an hourly `logrotate` policy for
   `/opt/ovn-lab/*.log` so a restart loop can never do that again.
10. **A load balancer needs a router with at most one distributed gateway port.**
    `Load-balancer is not supported yet when there is more than one distributed gateway
    port on the router` — silently, with the VIP simply never answering. This is why
    north-south moved to a separate edge router.
11. **A VIP inside the tenant subnet needs the LB on the logical *switch*.** Attached only
    to a router, nothing answers ARP for the VIP, and the packet dies at
    `ls_in_l2_unknown`. Attached to the switch, OVN installs the ARP responder.
12. **…but a switch-attached LB is skipped for traffic that enters from a router port**
    (`ls_in_pre_lb: ip && inport == <router port> → next`). So external clients need the LB
    on a router as well — and on a *distributed* router OVN only programs it on the
    distributed gateway port, which external traffic does not arrive on. The working
    combination is: LB on the client **switch** (intra-VPC) **and** on the **edge gateway
    router** (external), the latter being a real gateway router (`options:chassis`) where
    the LB applies to every port.
13. **`lb_force_snat_ip=router_ip` is rejected by this build** — `bad ip router_ip in
    options of router`. The address must be given literally.
14. **A VIP is only usable from inside the AZ that owns it.** Following on from #11/#12:
    a workload in AZ1 cannot reach AZ2's VIP. Arriving over the transit switch it enters
    `ls-client-az2` from a router port (switch LB skipped, #12) and OVN does not program
    the router LB on that ingress path either, so it is routed on as a plain address and
    dies at ARP. The fix is a **service VIP in the consumer's own AZ** whose backend is the
    remote workload: the DNAT then happens locally and the rewritten packet crosses the
    interconnect as ordinary traffic. That is why AZ1 has `10.10.1.200`.
15. **A VIP inside the tenant subnet needs its LB on the router *as well as* the switch,
    or nothing answers ARP.** Attached to the switch alone, the DNAT rules are installed
    and `ovn-trace` shows them, but a client on that switch never gets an ARP reply for the
    VIP, so it never sends a packet at all — it fails looking exactly like a routing
    problem. Both attachments are required, for different reasons: the switch one does the
    load balancing, the router one makes the address answerable.
16. **Grant `DELETE`, not just `SELECT/INSERT/UPDATE`.** The infrastructure-state publisher
    replaces an AZ's rows on each run, so it needs `DELETE` — the same shape of failure as
    gotcha #8, one privilege further along.

Same build constraints as before: OVN compiled from source (v24.03.6), OVS pinned to
v3.3.0, because the `ovn-central` package on Ubuntu 24.04 ships neither the `ovn-ic` binary
nor the IC-NB/IC-SB schemas.

---

## Results

Validated end-to-end on the three VMs (`ansible-playbook verify.yml verify_ha.yml`, all
asserts green):

- **Control plane:** `ts-client` and `ts-mgmt` both declared in IC-NB and propagated;
  `az1-chassis` and `az2-chassis` registered in IC-SB; GENEVE up between
  `172.18.3.175 ↔ 172.18.33.126`.
- **Plane separation:** `lr-client-az1` learned `10.10.2.0/24` **and nothing from
  `10.20.x`**; `lr-mgmt-az1` learned `10.20.2.0/24` **and nothing from `10.10.x`** — and
  symmetrically on AZ2.
- **Plane isolation:** the client plane cannot reach `10.20.2.10` in either AZ, and the
  mgmt plane cannot reach the client plane. Before the Edge Firewall policies were added,
  this test **caught a real leak** through the shared edge router.
- **The application, end to end:** `GET /api/status` through AZ1's frontend VIP is answered
  by `app-vm-2` **in AZ2** with a live database, in ≈ **45 ms** total; the AZ1 service VIP
  `10.10.1.200` was answered 8/8 by the AZ2 backend; both AZs' own VIPs answered 12/12.
  The dashboard renders **19 infrastructure rows from both AZs, none in error**.
- **Paths #2/#3 — cross- vs intra-AZ over `ts-mgmt`:** RTT to the database is **3.191 ms**
  from AZ1 (over the interconnect) vs **0.059 ms** from AZ2 (local) — a ~54x difference
  that is the interconnect's real cost, measured in one run.
- **Path #4 — north-south:** all three workloads reach the internet (HTTP 200), each SNATed
  by its own plane.
- **MTU:** `-s 1414 -M do` succeeds, `-s 1415` fails.
- **Interconnect HA:** stopping the RAFT **leader** elected a new one within seconds; the
  degraded 2-of-3 cluster still **committed writes**; the data plane never noticed; the
  stopped member rejoined cleanly.
- **Idempotency:** re-running `ic_cluster`, `central`, `chassis` and `services` reports
  `changed=0` (`topology` reports 1: the deliberate `ovn-ic` restart).

---

## What changed in this revision

Rebuilt to match [`advanced-ovn-ic.pdf`](advanced-ovn-ic.pdf). The previous version was a
single flat plane with one global VIP and a single-VM interconnect database.

| Aspect | previous revision | this revision |
|---|---|---|
| Planes | one (`10.10.x` only) | **two** — client + management, isolated |
| Transit switches | one (`ts`) | **two** — `ts-client`, `ts-mgmt`, one GENEVE fabric |
| Workload NICs | 1 | **2** on the app backends (`db-vm` stays mgmt-only) |
| Database | `10.10.2.10`, on the tenant network | **`10.20.2.10`, management plane only** |
| Load balancer | one global VIP on AZ1 | **one VIP per AZ**, each fronting both backends |
| North-south | per-plane router owned its external port | **one edge gateway router per cell** + Edge Firewall |
| IC databases | standalone on az1 (SPOF) | **3-member RAFT** (az1 + az2 + quorum arbiter) |
| VMs | 2 | **3** (the third is arbiter only) |
| Application | one FastAPI app, identical in both AZs | **React (AZ1) + Java (AZ2) + Postgres**, split so using it crosses the AZs |
| Dashboard | a status page for one backend | **infrastructure dashboard** fed from the DB by both AZs |
| Tests | printed output you had to read | **assert-based**, fails the run + a separate HA suite |
| Log safety | none — a crash loop filled the disk | **logrotate policy** + fixed `ovn-controller` unit |

Naming changed accordingly (`lr-az1` → `lr-client-az1` / `lr-mgmt-az1`, `ts` → `ts-client` /
`ts-mgmt`), which is why `reset.yml` exists.

---

## Design decisions

- **OVN driven manually, Incus only provides the workloads.** Incus has native OVN
  networking, but it manages the NB itself and does not expose OVN-IC configuration. To keep
  full control of the interconnect we run OVN/`ovn-ic` by hand and attach Incus instances to
  `br-int` via OVS ports with the right `iface-id`.
- **Isolation by separate datapaths, not separate fabrics.** Two transit switches over one
  GENEVE tunnel is exactly what the design specifies. It is worth being explicit that this
  is a *logical* boundary: anyone who can inject into `br-int` or read the tunnel is not
  stopped by it. It is a routing/tenancy boundary, not an encryption boundary.
- **The database is single-NIC.** Making `db-vm` mgmt-only means client-plane isolation
  cannot be undone by a routing mistake — there is no address to reach. That is a stronger
  guarantee than a firewall rule, and it is why the "must fail" test is meaningful.
- **A dedicated edge tier per cell.** Driven both by the design and by the OVN constraint
  on load balancers and distributed gateway ports (gotcha #10).
- **Per-AZ VIPs rather than one global VIP.** The design is symmetric: every cell offers
  the service locally. Both VIPs still front both backends, so the cross-AZ path is still
  exercised from either side.
- **Incus containers, not VMs.** The hosts are 2 vCPU / 8 GB and nested KVM is heavy there.
  Containers boot in seconds and exercise the *exact same* OVN-IC GENEVE datapath.
- **The application is split across AZs on purpose.** Putting the frontend and the backend
  in different cells means the dataflow under test is the product's own traffic, not a
  synthetic probe. It also makes failures obvious: if the interconnect breaks, the
  dashboard says so within one poll.
- **Plain JDK for the backend, no Spring/Maven.** The workloads are small containers behind
  a double NAT; a dependency tree is a slow, flaky download for no benefit at this size.
  Everything the backend does — HTTP, JSON, JDBC, interface enumeration — is in the JDK
  plus the distro's JDBC driver.
- **Single Postgres in AZ2** (no replica). Architecturally this couples failure domains and
  adds latency for AZ1 — irrelevant for a study lab, and it gives the intra- vs. inter-AZ
  contrast for free.
- **The quorum VM is deliberately minimal.** No chassis, no `br-int`, no GENEVE, and only
  `openvswitch-common` installed (not `openvswitch-switch`) so it cannot accidentally
  become a datapath node. Its IC schemas are copied from az1 rather than compiled, because
  1 vCPU / 1 GB is not a build host.

---

## Improving toward a more real scenario

Viable from what we already have, roughly by effort/value:

1. **Persist the host network bits** (br-ex IP, iptables, VIP route) via `netplan` +
   `iptables-persistent`/a systemd unit — today they're lost on reboot and restored by
   re-running `chassis.yml services.yml`. Low effort, high value; the reboot that started
   this session's debugging is exactly the failure mode.
2. **Secrets with `ansible-vault`** — the DB password is plaintext in `group_vars`.
3. **Load-balancer health checks** (OVN LB `health_check`) so a dead backend is dropped.
4. **PostgreSQL primary/replica with failover** — a replica in AZ1 lets the AZ1 app read
   locally (kills the path-#2 cross-AZ latency), primary in AZ2.
5. **TLS on the databases** (`pssl`/`ssl` instead of `ptcp`) with certificates — now more
   valuable than before, since the IC cluster gossips over the network on 6647/6648.
6. **Real ACLs on the plane switches.** The planes are separated by routing today; OVN
   port groups + ACLs would enforce it at L2/L4 as well.
7. **More than two AZs** — the transit switches federate N zones; add `host_vars/az3.yml`
   and add it to `[azs]`. The Ansible is already variable-driven for this, and the IC
   cluster already tolerates a member joining.
8. **Workloads as Incus VMs** (nested KVM) for true isolation and a real virtio NIC.
9. **Multiple app replicas per AZ** behind each VIP.
10. **Observability**: `ovn-trace`/`ovs-appctl` for flow debugging (both were essential in
    this revision), OVS metrics, centralized logs from `/opt/ovn-lab/*.log`.
11. **Ansible quality**: `molecule` to test roles and `ansible-lint` in CI. `verify.yml` is
    already `assert:`-based and fails the build when a path breaks.

---

## Repository layout

```
advanced-ovn-ic/
├── README.md                 # this document
├── GUIA-DO-ZERO.md           # from-zero explanation, in Portuguese
├── advanced-ovn-ic.drawio    # editable diagram
├── advanced-ovn-ic.pdf       # rendered diagram — the spec this lab implements
└── ansible/
    ├── README.md             # operator quick-reference
    ├── ansible.cfg
    ├── inventory.ini         # [azs] az1 az2 · [ic_cluster] az1 az2 quorum
    ├── group_vars/all.yml
    ├── host_vars/{az1,az2,quorum}.yml
    ├── ping.yml common.yml ic_cluster.yml central.yml chassis.yml
    ├── topology.yml services.yml incus.yml workloads.yml site.yml
    ├── reset.yml             # teardown, for when the topology's SHAPE changes
    ├── verify.yml            # T1-T6, assert-based
    ├── verify_ha.yml         # H1-H4, interconnect HA (disruptive)
    └── roles/
        ├── common/ ic_cluster/ ovn_central/ ovn_chassis/
        ├── ovn_topology/     # plane.yml + plane_transit.yml, looped per plane
        ├── ovn_services/     # plane_edge.yml, looped per plane
        ├── incus/
        └── workloads/
            ├── files/Backend.java      # the Java backend tier
            ├── files/frontend/         # the React + Vite dashboard
            ├── tasks/deploy_db.yml deploy_backend.yml deploy_frontend.yml
            ├── tasks/publish_infra_state.yml   # OVN state -> Postgres
            └── templates/              # netplan, systemd units, nginx, db_setup.sh
```
