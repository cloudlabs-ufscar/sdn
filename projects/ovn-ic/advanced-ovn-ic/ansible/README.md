# Advanced OVN-IC lab — Ansible automation

> This is the **operator quick-reference**. The full project documentation —
> architecture, diagram, design decisions, results, and how to evolve it — lives in
> the parent [`../README.md`](../README.md).

This directory provisions the whole [`advanced-ovn-ic`](../README.md) lab with
**Ansible**: two availability zones plus a quorum arbiter, two isolated network
planes federated by two transit switches, a clustered interconnect database, and
a three-tier application split across the AZs.

---

## 1. Ansible in five minutes

- **Control node** — the machine that *runs* Ansible. Here that is your **WSL
  Ubuntu**, because your SSH key and the `az1`/`az2`/`quorum` aliases live in its
  `~/.ssh/config`. Ansible is *not* installed on the VMs.
- **Managed nodes** — `az1`, `az2` and `quorum`. They only need SSH access and a
  Python 3 interpreter.
- **Inventory** (`inventory.ini`) — the managed nodes and their groups.
- **Module** — a unit of work that reaches a desired state (`apt`, `copy`,
  `systemd_service`). You describe the *end state*; the module figures out the steps.
- **Task** — one call to a module. **Playbook** — hosts mapped to tasks/roles.
- **Role** — a reusable bundle of tasks + templates + handlers.
- **Handler** — runs only when *notified* by a task that changed something.
- **Idempotency** — running twice leaves the same state; tasks report **changed**
  only when they actually changed something.
- **`become`** — run the task with `sudo`.

---

## 2. Install Ansible on the WSL control node

```bash
sudo apt update && sudo apt install -y pipx
pipx ensurepath && pipx install --include-deps ansible
exec $SHELL          # reload PATH
ansible --version
```

---

## 3. Inventory: three hosts, two groups

```
[azs]         az1, az2      -> real availability zones: chassis, workloads, GENEVE
[ic_cluster]  az1, az2, quorum
```

`quorum` is an **arbiter only**: no chassis, no `br-int`, no GENEVE, no workloads.
It exists so the interconnect database cluster has an odd number of members and
keeps a majority when either AZ is lost. It is never in `[azs]`.

Prove connectivity first:

```bash
ansible-playbook ping.yml
```

---

## 4. Project layout

```
ansible/
├── ansible.cfg            # default flags (inventory, ssh tuning)
├── inventory.ini          # [azs] + [ic_cluster]
├── group_vars/all.yml     # shared: ports, transit switches, IC cluster, app tiers
├── host_vars/az1.yml      # AZ1: identity, edge, planes[], workloads[]
├── host_vars/az2.yml      # AZ2: same shape; holds the backend + database
├── host_vars/quorum.yml   # just the arbiter's address
├── ping.yml common.yml ic_cluster.yml central.yml chassis.yml
├── topology.yml services.yml incus.yml workloads.yml site.yml
├── reset.yml              # teardown, for when the topology's SHAPE changes
├── verify.yml             # T1-T7, assert-based
├── verify_ha.yml          # H1-H4, interconnect HA (disruptive)
└── roles/
    ├── common/            # apt deps, compile OVN-IC from source, logrotate policy
    ├── ic_cluster/        # IC-NB/IC-SB as a 3-member RAFT cluster
    ├── ovn_central/       # per-AZ NB/SB ovsdb + northd
    ├── ovn_chassis/       # OVS chassis, ovn-controller, br-ex, host SNAT, firewall
    ├── ovn_topology/      # NB_Global, both transit switches, per-plane LS/LR/LRP
    ├── ovn_services/      # edge gateway router + Edge Firewall + load balancers
    ├── incus/             # install + init Incus
    └── workloads/         # dual-NIC containers, the three app tiers, infra state
```

The **whole topology is data**. `host_vars/*.yml` describes each AZ as a list of
`planes` (switch, router, subnet, transit switch, edge address, SNAT address, VIP)
and a list of `workloads`, each with a `nics` list — one entry per plane. Adding a
plane, a workload or a second NIC is a data change, not a code change.

---

## 5. Run order

| # | Command | What it does |
|---|---------|--------------|
| 0 | `ansible-playbook ping.yml` | connectivity check |
| 2 | `ansible-playbook common.yml` | base packages + compile OVN-IC (~10-20 min) + logrotate |
| 3a| `ansible-playbook ic_cluster.yml` | **IC-NB/IC-SB RAFT cluster** across az1 + az2 + quorum |
| 3b| `ansible-playbook central.yml` | per-AZ NB/SB + northd |
| 4 | `ansible-playbook chassis.yml` | OVS chassis, ovn-controller, br-ex, host SNAT |
| 5 | `ansible-playbook topology.yml` | both planes, both transit switches, gateways, ovn-ic |
| 6 | `ansible-playbook services.yml` | edge gateway router + load balancers |
| 7 | `ansible-playbook incus.yml` | install + init Incus |
| 8 | `ansible-playbook workloads.yml` | containers + Postgres + Java backend + React frontend |
|   | `ansible-playbook site.yml` | **all of stages 2-8, in order** |
| ✓ | `ansible-playbook verify.yml` | T1-T7 |
| ✓ | `ansible-playbook verify_ha.yml` | H1-H4 (stops a database briefly) |

Useful flags: `--check` (dry run), `--limit az1`, `-v`/`-vvv`,
`--start-at-task "name"`.

**Idempotency:** re-running any stage reports `changed=0`, with one deliberate
exception — `topology.yml` always restarts `ovn-ic` (gotcha #3).

---

## 6. The application

Three tiers, split across the AZs so that using the app *is* the interconnect test:

```
browser ──ssh tunnel──► AZ1 VIP 10.10.1.100:80
                          └─► nginx + React            app-vm-1 · AZ1 · client plane
                                └─► /api/* ─► AZ1 service VIP 10.10.1.200
                                                └─► OVN LB ─► ts-client ─► GENEVE   ◄── CROSS-AZ
                                                      └─► Java backend  app-vm-2 · AZ2
                                                            └─► JDBC ─► db-vm 10.20.2.10  ◄── MGMT
```

| Tier | Where | Stack | Port |
|---|---|---|---|
| Frontend | `app-vm-1`, AZ1 | React 18 + Vite, nginx | client `:80` |
| Backend | `app-vm-2`, AZ2 | Java 21, JDK HttpServer + JDBC | client `:8080` |
| Database | `db-vm`, AZ2 | PostgreSQL | mgmt `:5432` |

Open the dashboard:

```bash
ssh -L 8080:10.10.1.100:80 az1     # then browse http://localhost:8080/
```

The infrastructure table it shows is real: each AZ host reads its own OVN state and
writes it into the `infra_state` table over the management plane
(`roles/workloads/tasks/publish_infra_state.yml`); the Java backend serves it; React
renders it. Re-run `workloads.yml` to refresh it.

---

## 6b. Observability and traffic generation

One Prometheus + Grafana per AZ, on the **management plane**, in `obs-vm`. Each cell
scrapes only its own targets.

| Exporter | Where | Port |
|---|---|---|
| node_exporter | both AZ hosts | `9100` (private IP) |
| infra probe (OVN state as metrics) | both AZ hosts | `9101` |
| node_exporter | every container | `9100` (mgmt IP) |
| Java backend metrics | `app-vm-2` | `9102` (**mgmt NIC only**) |
| postgres_exporter | `db-vm` | `9187` |
| load generator | `load-vm` | `9103` |

`load-vm` (AZ1, dual-homed) drives the frontend VIP with a weighted endpoint mix at a
rate that follows a slow sine, plus a periodic database probe over `ts-mgmt` — otherwise
the management transit switch would carry no measurable cross-AZ load.

```bash
ssh -L 3000:localhost:3000 az1     # Grafana    → http://localhost:3000 (admin/admin)
ssh -L 9090:localhost:9090 az1     # Prometheus → http://localhost:9090

# scrape health
ansible azs -b -m shell -a 'incus exec obs-vm -- curl -s http://$(incus exec obs-vm -- hostname -I | cut -d" " -f1):9090/api/v1/targets?state=active' | grep -o '"health":"[a-z]*"' | sort | uniq -c
```

Grafana is reached through an **Incus proxy device**, so the AZ host needs no route into
the management plane. Retention is capped at `6h` AND `1GB` — time alone does not bound
bytes, and that is how this lab filled a disk once already.

Tuning knobs live in `group_vars/all.yml`: `loadgen_base_rps`, `loadgen_amplitude`,
`loadgen_period_s`, `prometheus_retention_*`.

---

## 7. How the OVN databases are arranged

| Database  | Port(s) | Scope | Runs on | Who connects |
|-----------|---------|-------|---------|--------------|
| local NB  | 6641 | per-AZ, private | each AZ (127.0.0.1) | that AZ's `ovn-northd`, `ovn-nbctl`, `ovn-ic` |
| local SB  | 6642 | per-AZ, private | each AZ (127.0.0.1) | that AZ's `ovn-northd`, `ovn-controller`, `ovn-ic` |
| **IC-NB** | 6645 client / 6647 RAFT | **global, clustered** | az1 + az2 + quorum | **both** AZs' `ovn-ic` |
| **IC-SB** | 6646 client / 6648 RAFT | **global, clustered** | az1 + az2 + quorum | **both** AZs' `ovn-ic` |

Each AZ keeps its own control plane — that isolation is the point of OVN-IC. Only
the two IC databases are shared, and they are clustered so no single VM can take
the federation down. **Three** members, not two: RAFT needs a strict majority, and
a majority of 2 is 2, so a two-member cluster stops on any single failure.

```bash
# cluster health, from any member
sudo ovs-appctl -t /opt/ovn-lab/ic-nb.ctl cluster/status OVN_IC_Northbound
sudo ovs-appctl -t /opt/ovn-lab/ic-sb.ctl cluster/status OVN_IC_Southbound
```

---

## 8. Testing

```bash
ansible-playbook verify.yml         # T1-T7
ansible-playbook verify_ha.yml      # H1-H4, disruptive
```

Every check is an `assert`, so a broken path **fails the run**.

| Group | Proves |
|---|---|
| T1 | both transit switches federated; both gateways in IC-SB; GENEVE up; **each plane learned only its own plane's remote subnet** |
| T2 | the client plane cannot reach the database; the mgmt plane cannot reach the client plane; `db-vm` has no client NIC |
| T3 | each AZ's VIP answers 12/12; the AZ1 service VIP is answered 8/8 by the AZ2 backend |
| T4 | the backend reads Postgres over its mgmt NIC; raw wire protocol from both AZs; RTT cross-AZ vs intra-AZ |
| T5 | every workload reaches the internet through its plane's SNAT |
| T6 | MTU ceiling: 1414B passes, 1415B rejected |
| T7 | the React bundle is served; `/api/status` is answered **by the other AZ** with a live DB; the backend is dual-homed at MTU 1442; infra rows from both AZs, none in error |
| H1-H4 | 3 members healthy → stop the **leader** → survivors elect one and still **commit writes** → data plane unaffected → member rejoins |

Manual poking:

```bash
export OVN_NB_DB=tcp:127.0.0.1:6641
export OVN_SB_DB=tcp:127.0.0.1:6642

ovn-ic-nbctl --db=tcp:172.18.3.175:6645,tcp:172.18.33.126:6645,tcp:172.18.3.240:6645 show
ovn-nbctl lr-route-list lr-client-az1   # 10.10.2.0/24 (learned), no 10.20.x
ovn-nbctl lr-route-list lr-mgmt-az1     # 10.20.2.0/24 (learned), no 10.10.x
ovn-nbctl lb-list
sudo ovs-vsctl show | grep -A2 geneve

# the application, from az1
curl -s http://10.10.1.100/api/status | python3 -m json.tool

# isolation — these MUST fail
sudo incus exec app-vm-1 -- ping -c2 -W2 -I eth0 10.20.2.10
sudo incus exec app-vm-1 -- ping -c2 -W2 -I eth1 10.10.2.20
```

Where to look when something is off:

```bash
sudo systemctl status ovn-ic ovn-northd ovn-controller ovn-ic-nb-db ovn-ic-sb-db
sudo tail -n 40 /opt/ovn-lab/ovn-northd.log     # LB / NAT complaints land here
sudo tail -n 40 /opt/ovn-lab/ovn-ic.log
sudo incus exec app-vm-2 -- journalctl -u backend -n 30
sudo incus exec app-vm-1 -- journalctl -u nginx -n 30
```

---

## 9. Resetting

The roles are idempotent, so normally just re-run a playbook. Re-running
`workloads.yml` also refreshes the dashboard's infrastructure table.

**When the topology's *shape* changes** (renamed routers, a new plane, a new
transit switch) the roles cannot rename or remove what a previous design created:

```bash
ansible-playbook reset.yml && ansible-playbook site.yml
```

`reset.yml` deletes the containers, the workload veths, and the local NB/SB **and**
the IC cluster databases — everything it removes is rebuilt by `site.yml`. It does
**not** touch the source build in `/opt/ovn-build`, the installed binaries, or the
Incus storage pool.

Just the workloads:

```bash
ansible azs -b -m shell -a 'incus delete -f app-vm-1 app-vm-2 db-vm 2>/dev/null; true'
ansible-playbook workloads.yml
```

A reboot loses the non-persistent host bits (br-ex IP, iptables, VIP routes); re-run
`chassis.yml services.yml` to restore them.

---

## 10. Gotchas

The five from `pratice-ovn-ic` plus eleven found here are documented with their
symptoms in [`../README.md`](../README.md#ovn-ic-gotchas). The ones most likely to
bite you while operating this lab:

- **`ovn-controller` needs `/run/ovn` to exist** and has no `--unixctl` option to
  point elsewhere; the unit uses `RuntimeDirectory=ovn`. Without it, it crash-loops
  after every reboot — and that loop once filled a 38 GB disk with an 8 GB log.
  `common.yml` now installs an hourly logrotate policy for `/opt/ovn-lab/*.log`.
- **A load balancer needs a router with at most one distributed gateway port**,
  which is why north-south lives on a separate edge router.
- **A VIP is only usable from inside the AZ that owns it**, which is why AZ1 has a
  service VIP (`10.10.1.200`) fronting the backend that lives in AZ2.
- **A VIP inside the tenant subnet needs its LB attached to the router as well as
  the switch**, or nothing answers ARP for it.
