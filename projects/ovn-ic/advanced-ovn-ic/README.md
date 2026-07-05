# Multi-AZ DBaaS-style lab over OVN-IC (CLO-73)

**Status: implemented and validated end-to-end on two VMs, provisioned with Ansible.**
The operator quick-reference lives in [`ansible/README.md`](ansible/README.md); this
document is the full story — design, architecture, how to run it from scratch, every
test, the gotchas, and how to push it closer to production.

## Table of contents

- [Context](#context)
- [Goal](#goal)
- [Architecture](#architecture)
- [How the OVN databases are arranged](#how-the-ovn-databases-are-arranged)
- [Addressing](#addressing)
- [Traffic paths this lab exercises](#traffic-paths-this-lab-exercises-that-ping-did-not)
- [How it is implemented — Ansible](#how-it-is-implemented--ansible)
- [Running it from zero](#running-it-from-zero)
- [Testing — every check](#testing--every-check)
- [OVN-IC gotchas](#ovn-ic-gotchas)
- [Results](#results)
- [What's new vs. the previous lab](#whats-new-vs-the-previous-lab)
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
(`dd-azdc-architecture`, `dd-xaas-network`): one tenant, one VPC federated across two AZs,
a real web application load-balanced across both AZs, and a managed database the app consumes.

## Goal

Go beyond ping: exercise the OVN-IC **datapath** with real TCP traffic (HTTP + the Postgres
wire protocol) crossing GENEVE tunnels between two separate Incus clouds, plus two OVN
features the previous lab never touched — the **native OVN load balancer** and **SNAT to
the internet**.

---

## Architecture

Two robust-enough VMs, each one a self-contained AZ (Incus cloud + its own OVN control
plane + `ovn-ic` + chassis + gateway role). A single tenant VPC is federated across both
AZs through one transit switch. The workloads are **Incus system containers** plugged
straight into `br-int` (see [Design decisions](#design-decisions) for containers vs. VMs).

- **app-vm-1** (AZ1) and **app-vm-2** (AZ2): identical FastAPI backends on `:8000`,
  each one also serving a tiny HTML dashboard at `/ui`.
- **db-vm** (AZ2): single PostgreSQL instance, the app's database.
- **OVN load balancer**: VIP `10.10.1.100:80` distributes to `app-vm-1:8000` and
  `app-vm-2:8000`. Half the requests land on the AZ2 backend, crossing the transit
  switch over GENEVE on the way *in*.
- **SNAT** on each AZ router gives the containers outbound internet (package installs)
  and lets you reach the VIP from outside the fabric.

```
                          ┌──────────────────────────────────────┐
                          │  IC-NB (tcp 6645)  +  IC-SB (tcp 6646)│   global, shared
                          │       (run on host az1)               │   interconnect DBs
                          └───────────────┬──────────────────────┘
            ovn-ic (az1) ─────────────────┴───────────────── ovn-ic (az2)
   ══════════════════════════ AZ 1 ═══════════║═══════════════ AZ 2 ══════════════════════

 HOST az1  172.18.3.175  (pub 201.23.73.63)   ║   HOST az2  172.18.33.126 (pub 201.23.74.156)
 NB 6641 · SB 6642 · northd · ovn-controller  ║   NB 6641 · SB 6642 · northd · ovn-controller
 ┌──────────────────────────────────────┐    ║    ┌──────────────────────────────────────┐
 │ lr-az1 (logical router)               │    ║    │ lr-az2 (logical router)               │
 │  • lrp-az1-ls   10.10.1.1/24  (gw)    │    ║    │  • lrp-az2-ls   10.10.2.1/24  (gw)    │
 │  • lrp-az1-ts   169.254.100.1 ●───────┼────╫────┼──● lrp-az2-ts   169.254.100.2         │
 │  • lrp-az1-ext  192.168.241.2         │ transit │  • lrp-az2-ext  192.168.242.2         │
 │  • LB  VIP 10.10.1.100:80 ─┐          │ switch  │  • SNAT 10.10.2.0/24 → .242.2         │
 │  • SNAT 10.10.1.0/24→.241.2│          │  "ts"   │                                       │
 │ ls-az1 (logical switch)    │          │(GENEVE) │ ls-az2 (logical switch)               │
 │  • app-vm-1 10.10.1.10:8000│◄─┐       │    ║    │  • app-vm-2 10.10.2.20:8000 ◄─┐       │
 │                            └──┼─backends──────────┼─► (LB sends ~half to AZ2) ──┘       │
 │                               │       │    ║    │  • db-vm    10.10.2.10:5432 ◄─ app    │
 └───────────────────────────────┼───────┘    ║    └──────────────────────────────────────┘
   br-int ─ br-ex(.1) ─ host MASQUERADE ─ ens3 ─► INTERNET ◄─ ens3 ─ host ─ br-ex ─ br-int
                                              ║
   underlay: GENEVE/UDP 6081  172.18.3.175 ◄══════════► 172.18.33.126  (carries the "ts")
```

Three layers stack up:

- **Underlay** (physical): the two VMs reach each other on their private IPs; GENEVE
  (UDP 6081) flows there. (The public `201.23.x` IPs are 1:1 NAT, used only for
  north-south, never for the tunnel.)
- **Logical overlay** (OVN): each AZ has a switch + router; the transit switch joins the
  two routers; OVN-IC advertises/learns the connected routes (`10.10.1.0/24` ⇄
  `10.10.2.0/24`).
- **Application**: containers on `br-int`, FastAPI + PostgreSQL.

### How the OVN databases are arranged

Each AZ keeps its **own** control plane; only the two interconnect databases are shared.

| Database  | Port | Scope                | Runs on              | Who connects to it                                   |
|-----------|------|----------------------|----------------------|------------------------------------------------------|
| local NB  | 6641 | per-AZ, private      | each AZ (127.0.0.1)  | that AZ's `ovn-northd`, `ovn-nbctl`, `ovn-ic`         |
| local SB  | 6642 | per-AZ, private      | each AZ (127.0.0.1)  | that AZ's `ovn-northd`, `ovn-controller`, `ovn-ic`    |
| **IC-NB** | 6645 | **global, shared**   | az1 (172.18.3.175)   | **both** AZs' `ovn-ic`                               |
| **IC-SB** | 6646 | **global, shared**   | az1 (172.18.3.175)   | **both** AZs' `ovn-ic`                               |

The transit switch is declared in IC-NB and `ovn-ic` copies it into every local NB;
registered gateways and the advertised/learned routes live in IC-SB. A workload's traffic
never depends on the *other* AZ's NB/SB — only on those two shared IC databases plus the
GENEVE tunnel. (For HA you'd run IC-NB/IC-SB clustered; one host is fine for a lab.)

### Addressing

| Plane          | AZ1 (host az1)            | AZ2 (host az2)                | Transit / shared              |
|----------------|---------------------------|-------------------------------|-------------------------------|
| Host (underlay)| `172.18.3.175`            | `172.18.33.126`               | GENEVE UDP 6081               |
| VPC subnet     | `10.10.1.0/24` (gw .1)    | `10.10.2.0/24` (gw .1)        | `ts` → `169.254.100.0/24`     |
| Workloads      | `app-vm-1` .10 · VIP .100 | `app-vm-2` .20 · `db-vm` .10  | `lrp-ts`: az1 .1 / az2 .2     |
| External (SNAT)| `192.168.241.0/24` (host .1, router .2) | `192.168.242.0/24` (host .1, router .2) | — |
| MTU            | overlay workloads = `1442` (1500 − 58 GENEVE) | | |

---

## Traffic paths this lab exercises (that ping did not)

1. **LB ingress crossing AZs.** A request to the VIP can be sent by the OVN LB to
   `app-vm-2` in AZ2 — so the very first hop already traverses the `ts` over GENEVE.
2. **Cross-AZ DBaaS query.** `app-vm-1` (AZ1) → `db-vm` (AZ2): a real Postgres connection
   over the transit switch. This is where MTU and GENEVE encap overhead finally bite.
3. **Intra-AZ query for contrast.** `app-vm-2` (AZ2) → `db-vm` (AZ2): same query, local
   path — a clean before/after latency comparison against path #2 in a single test run.
4. **North-south via SNAT.** Containers reach the internet through the AZ router's external
   port; the VIP is reachable from outside the fabric.

---

## How it is implemented — Ansible

Everything is provisioned with **Ansible** (no bash setup scripts), run from a **WSL
Ubuntu control node** that already has the `az1`/`az2` SSH aliases. The VMs only need SSH
+ Python 3 (both present) and passwordless sudo.

- **`inventory.ini`** — hosts `az1`, `az2` (group `azs`) and `ic_host` (just `az1`).
- **`group_vars/all.yml`** — values shared by both AZs (versions, ports, transit subnet,
  MTU, VIP, DB credentials).
- **`host_vars/az1.yml` / `az2.yml`** — per-AZ values (AZ name, private/public IP, subnets,
  MACs, external subnet, and the list of workloads).
- **roles** (one concern each):

  | role | what it does |
  |------|--------------|
  | `common`       | apt base (OVS/OVN), disables the apt OVN services, **compiles `ovn-ic` + IC schemas from source** (the Ubuntu package omits them). Guarded so the ~10-20 min build runs once. |
  | `ovn_central`  | creates the `.db` files and runs NB/SB/`ovn-northd` (and IC-NB/IC-SB on az1) as **systemd units**. |
  | `ovn_chassis`  | OVS chassis config (GENEVE encap-ip = private IP), `ovn-controller`, external bridge `br-ex`, host `ip_forward` + MASQUERADE, firewall for GENEVE + IC ports. |
  | `ovn_topology` | `NB_Global` identity/route options, LS/LR/LRP, gateway chassis on the transit LRP, the `ts` in IC-NB, starts `ovn-ic`, waits for propagation, connects router↔ts, restarts `ovn-ic`. Encodes the gotchas. |
  | `ovn_services` | the external port + default route + **OVN SNAT** (with `--gateway-port`), the **load balancer** (VIP → both backends), `lb_force_snat_ip`, and a host route to the VIP. |
  | `incus`        | installs Incus and `admin init --minimal`. |
  | `workloads`    | the OVN logical ports, Incus containers with a **p2p veth plugged into `br-int`** (iface-id set by us), static netplan (IP/MTU/gw/DNS), then PostgreSQL on `db-vm` and FastAPI on the apps. |

Idempotency is real: re-running any playbook reports `changed=0`. There is one playbook
per stage (`common.yml`, `central.yml`, …) plus `site.yml` (all stages) and `verify.yml`.

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
ansible-playbook ping.yml         # SSH + Python reachable on both VMs
ansible-playbook common.yml       # deps + compile OVN v24.03.6 / OVS v3.3.0  (~10-20 min)
ansible-playbook central.yml      # NB/SB + northd on both; IC-NB/IC-SB on az1
ansible-playbook chassis.yml      # OVS chassis + ovn-controller + br-ex + host SNAT
ansible-playbook topology.yml     # LS/LR/LRP + transit switch + ovn-ic (federation)
ansible-playbook services.yml     # OVN load balancer + OVN SNAT
ansible-playbook incus.yml        # install + init Incus
ansible-playbook workloads.yml    # containers on br-int + FastAPI + PostgreSQL

ansible-playbook verify.yml       # exercises the four traffic paths
```

`ansible-playbook site.yml` runs stages 2-8 in one go. Everything is idempotent, so you can
re-run any stage at any time. Reset/teardown recipes are in [`ansible/README.md`](ansible/README.md).

---

## Testing — every check

On whichever VM you `ssh` into, first export the DB locations so the `ovn-*` tools don't
need `--db` (no `sudo` needed — the DBs are on local TCP; only `ovs-vsctl` needs `sudo`):

```bash
export OVN_NB_DB=tcp:127.0.0.1:6641
export OVN_SB_DB=tcp:127.0.0.1:6642
export OVN_IC_NB_DB=tcp:172.18.3.175:6645
export OVN_IC_SB_DB=tcp:172.18.3.175:6646
```

**One-shot (from the control node):** `ansible-playbook verify.yml`.

**Control plane (OVN-IC):**
```bash
ovn-ic-sbctl show                     # both gateways (az1-chassis, az2-chassis) + transit ports
ovn-ic-nbctl show                     # the transit switch as declared globally
ovn-nbctl lr-route-list lr-az1        # on az1: shows 10.10.2.0/24 ... (learned)
ovn-nbctl lr-route-list lr-az2        # on az2: shows 10.10.1.0/24 ... (learned)
ovn-sbctl show                        # chassis + which one owns each port binding
sudo ovs-vsctl show | grep -A2 geneve # the GENEVE tunnel to the peer's encap-ip
```

**Data plane — ICMP (the classic interconnect proof).** The interconnect links the
*workloads* in different AZs; the AZ hosts reach each other on the underlay, which is just
the tunnel transport.
```bash
sudo incus exec app-vm-2 -- ping -c3 10.10.2.10        # intra-AZ (AZ2 -> db, local)
sudo incus exec app-vm-1 -- ping -c3 10.10.2.10        # inter-AZ (AZ1 -> db, over GENEVE)
sudo incus exec app-vm-1 -- ping -c3 10.10.2.20        # inter-AZ (AZ1 -> app-vm-2)
```

**MTU / GENEVE pressure.** Workload MTU is 1442; with DF the largest ICMP payload is 1414:
```bash
sudo incus exec app-vm-1 -- ping -c2 -M do -s 1414 10.10.2.10   # OK (fills the 1442 frame)
sudo incus exec app-vm-1 -- ping -c2 -M do -s 1500 10.10.2.10   # fails: fragmentation needed
```

**Data plane — L4 (HTTP + Postgres, the real point):**
```bash
for i in $(seq 10); do curl -s http://10.10.1.100/; echo; done       # #1 LB flips AZ1/AZ2 (on az1)
sudo incus exec app-vm-1 -- curl -s localhost:8000/ ; echo           # #2 cross-AZ DB (higher latency)
sudo incus exec app-vm-2 -- curl -s localhost:8000/ ; echo           # #3 intra-AZ DB (lower latency)
# raw Postgres wire protocol, cross-AZ:
sudo incus exec app-vm-1 -- /opt/app/venv/bin/python -c \
"import psycopg; print(psycopg.connect('host=10.10.2.10 dbname=appdb user=appuser password=apppass').execute('SELECT now()').fetchone())"
```

**North-south (SNAT):**
```bash
sudo incus exec app-vm-1 -- curl -sI https://example.org | head -1   # #4 internet -> HTTP 200
curl -s http://10.10.1.100/ ; echo                                   # the VIP from the az1 host
```

**Web UI.** The app serves a dashboard at `/ui`. From your laptop/WSL, tunnel to the VIP
through az1 and open it in a browser:
```bash
ssh -L 8080:10.10.1.100:80 az1     # then browse http://localhost:8080/ui
```
The badge flips between **AZ1** and **AZ2** as the load balancer picks a backend, and the
latency line switches between *cross-AZ (over GENEVE)* and *intra-AZ (local)*.

**Idempotency:** `ansible-playbook chassis.yml` → expect `changed=0`.

**Troubleshooting (where to look):**
```bash
sudo systemctl status ovn-ic ovn-northd ovn-controller
sudo tail -n 40 /opt/ovn-lab/ovn-ic.log
sudo ovs-vsctl show
sudo incus list
sudo incus exec <name> -- ip -4 addr show eth0
```

---

## OVN-IC gotchas

The five hard-won lessons from [`pratice-ovn-ic`](../pratice-ovn-ic/README.md) all still
apply and are encoded in the `ovn_topology` role:

1. **AZ identity comes from `NB_Global.name`.**
2. **The transit switch is created in IC-NB** and propagated by `ovn-ic` — never created
   manually in the local NB.
3. **Restart `ovn-ic` after the topology is fully built** to avoid the startup race.
4. **`ic-route-adv` / `ic-route-learn` live on `NB_Global.options`** (OVN 24.03.6), not on
   the `Logical_Router`.
5. **Pin a gateway chassis on the transit LRP** (`lrp-set-gateway-chassis`) or the data
   plane stays broken even with routes advertised.

Three more surfaced bringing *this* lab up, and are fixed in the roles:

6. **`ovn-ic` needs an explicit `--unixctl`.** The source build defaults its control
   socket to `/usr/local/var/run/ovn/`, which doesn't exist, so it crash-loops with
   `binding failed: No such file or directory` and never propagates the transit switch.
   The unit passes `--unixctl=/opt/ovn-lab/ovn-ic.ctl`.
7. **Incus 6.0's native OVS client can't attach to `br-int`** (`Failed to connect to OVS:
   ... listdbs failure - unexpected EOF`), even though `ovs-vsctl` works. So the workload
   nic is `nictype=p2p`: Incus builds the veth pair and we add the host side to `br-int`
   with `ovs-vsctl ... iface-id=<lsp>` ourselves — also more in line with the
   "OVN driven manually" design.
8. **Grant the app role on the demo table.** The table is created by the `postgres`
   superuser, so `appuser` connects fine but gets `permission denied for table lab_info`
   until granted. `db_setup.sh` runs the `GRANT`s.

Same build constraints as before: OVN compiled from source (v24.03.6), OVS pinned to v3.3.0,
because the `ovn-central` package on Ubuntu 24.04 ships neither the `ovn-ic` binary nor the
IC-NB/IC-SB schemas.

---

## Results

Validated end-to-end on the two VMs:

- **Control plane:** both gateways (`az1-chassis`, `az2-chassis`) registered in IC-SB;
  GENEVE tunnels up between `172.18.3.175 ↔ 172.18.33.126`; connected routes learned across
  AZs (`10.10.1.0/24` and `10.10.2.0/24` show as `(learned)` on the opposite AZ).
- **Path #1 — LB across AZs:** hitting the VIP repeatedly flips `served_by` between
  `app-vm-1` (AZ1) and `app-vm-2` (AZ2); the AZ2 hits cross the transit switch.
- **Paths #2/#3 — cross- vs intra-AZ DB:** `app-vm-1` (cross-AZ, over GENEVE) ≈ **35 ms**
  vs `app-vm-2` (intra-AZ, local) ≈ **14 ms** for the same query.
- **Path #4 — SNAT:** containers reach the internet (`HTTP 200`); the VIP is reachable from
  the az1 host.
- **ICMP:** inter-AZ and intra-AZ pings 0% loss; MTU `-s 1414 -M do` succeeds, `-s 1500`
  fails (the GENEVE encap pressure).
- **Idempotency:** re-running stages reports `changed=0`.

---

## What's new vs. the previous lab

| Aspect      | pratice-ovn-ic            | this lab                                       |
|-------------|---------------------------|------------------------------------------------|
| Workloads   | network namespaces        | **Incus containers** plugged into `br-int`     |
| Traffic     | ICMP echo                 | **HTTP + Postgres** (real TCP over GENEVE)     |
| Routers     | 1 per AZ                  | 1 per AZ + **OVN LB** + **SNAT**               |
| Automation  | bash setup scripts        | **Ansible** (roles, idempotent)                |
| Useful?     | connectivity proof only   | a real app you can `curl`, query, and browse   |

---

## Design decisions

- **OVN driven manually, Incus only provides the workloads.** Incus has native OVN
  networking, but it manages the NB itself and does not expose OVN-IC configuration. To keep
  full control of the interconnect we run OVN/`ovn-ic` by hand and attach Incus instances to
  `br-int` via OVS ports with the right `iface-id`.
- **Incus containers, not VMs.** The hosts are 2 vCPU / 8 GB and nested KVM is heavy there.
  Containers boot in seconds and exercise the *exact same* OVN-IC GENEVE datapath; the
  MTU/encap pressure applies identically on the veth into `br-int`. Promoting workloads to
  Incus VMs (nested KVM is available) is a documented next step.
- **Native OVN load balancer** instead of a HAProxy VM — fewer moving parts and it
  demonstrates an OVN feature directly. `lb_force_snat_ip=router_ip` makes the cross-AZ
  backend work (the backend replies to the gateway router, so the LB can un-DNAT).
- **Single Postgres in AZ2** (no replica). Architecturally this couples failure domains and
  adds latency for AZ1 — irrelevant for a study lab, and it gives the intra- vs. inter-AZ
  contrast for free. Promoting to primary/replica with failover is the natural next layer.

---

## Improving toward a more real scenario

Viable from what we already have, roughly by effort/value:

1. **Persist the host network bits** (br-ex IP, iptables, VIP route) via `netplan` +
   `iptables-persistent`/a systemd unit — today they're lost on reboot and restored by
   re-running `chassis.yml services.yml`. Low effort, high value.
2. **Secrets with `ansible-vault`** — the DB password is plaintext in `group_vars`.
3. **Load-balancer health checks** (OVN LB `health_check`) so a dead backend is dropped.
4. **PostgreSQL primary/replica with failover** — a replica in AZ1 lets the AZ1 app read
   locally (kills the path-#2 cross-AZ latency), primary in AZ2; decouples failure domains.
5. **HA for the IC databases** — IC-NB/IC-SB run only on az1 today (a control-plane SPOF).
   Run them as a clustered (RAFT) ovsdb reachable by all AZs. The data plane survives an IC
   outage (routes stay programmed); topology changes don't.
6. **TLS on the databases** (`pssl`/`ssl` instead of `ptcp`) with certificates.
7. **More than two AZs** — the transit switch federates N zones; add `host_vars/az3.yml`
   (new subnet, encap-ip, …) and run. The Ansible is already variable-driven for this.
8. **Workloads as Incus VMs** (nested KVM) for true isolation and a real virtio NIC.
9. **Multiple app replicas per AZ** + a per-AZ LB, with the global VIP fronting them.
10. **Observability**: `ovn-trace`/`ovs-appctl` for flow debugging, OVS metrics, centralized
    logs from `/opt/ovn-lab/*.log`.
11. **Ansible quality**: `molecule` to test roles, `ansible-lint` in CI, and turn
    `verify.yml` into `assert:`-based checks that fail the build when a path breaks.

---

## Repository layout

```
advanced-ovn-ic/
├── README.md                 # this document
├── advanced-ovn-ic.drawio    # editable diagram
├── advanced-ovn-ic.pdf       # rendered diagram
└── ansible/
    ├── README.md             # operator quick-reference
    ├── ansible.cfg
    ├── inventory.ini
    ├── group_vars/all.yml
    ├── host_vars/{az1,az2}.yml
    ├── ping.yml common.yml central.yml chassis.yml topology.yml
    ├── services.yml incus.yml workloads.yml site.yml verify.yml
    └── roles/
        ├── common/ ovn_central/ ovn_chassis/ ovn_topology/
        ├── ovn_services/ incus/
        └── workloads/        # incl. files/app.py (FastAPI + /ui) and db_setup.sh.j2
```
