# Advanced OVN-IC lab — Ansible automation

> This is the **operator quick-reference**. The full project documentation —
> architecture, diagram, design decisions, results, and how to evolve it — lives in
> the parent [`../README.md`](../README.md).

This directory provisions the whole [`advanced-ovn-ic`](../README.md) lab with
**Ansible** instead of the bash scripts used in `pratice-ovn-ic`. If you have
never touched Ansible, read the primer below first — it is short.

---

## 1. Ansible in five minutes

- **Control node** — the machine that *runs* Ansible. Here that is your **WSL
  Ubuntu**, because your SSH key and the `az1`/`az2` aliases live in its
  `~/.ssh/config`. Ansible is *not* installed on the VMs; it only needs to be on
  the control node.
- **Managed nodes** — the machines Ansible configures: `az1` and `az2`. They only
  need SSH access and a Python 3 interpreter (both already present).
- **Inventory** (`inventory.ini`) — the list of managed nodes and the groups they
  belong to.
- **Module** — a small unit of work that knows how to reach a desired state, e.g.
  `apt` (a package is installed), `copy` (a file has this content), `systemd_service`
  (a service is running). You describe the *end state*; the module figures out the
  steps.
- **Task** — one call to a module.
- **Playbook** — a YAML file mapping a group of hosts to a list of tasks/roles.
- **Role** — a reusable bundle of tasks + templates + handlers (e.g. `common`,
  `ovn_topology`). Roles keep large playbooks readable.
- **Handler** — a task that runs only when *notified* by another task that changed
  something (we use one to restart `ovn-ic` after the topology changes).
- **Idempotency** — running a playbook twice leaves the system in the same state.
  Tasks report **changed** only when they actually change something, otherwise
  **ok**. This is the big win over bash: no need to "clean up everything" first.
- **`become`** — run a task with `sudo` (passwordless here).

A run prints one line per task per host: `ok` (already correct), `changed` (just
fixed it), `failed`, or `skipped`. The end-of-run **PLAY RECAP** sums it up.

---

## 2. Install Ansible on the WSL control node

Run these **inside WSL** (`wsl` from PowerShell, or your Ubuntu terminal):

```bash
sudo apt update
sudo apt install -y pipx
pipx ensurepath
pipx install --include-deps ansible
exec $SHELL          # reload PATH so the `ansible` command is found
ansible --version    # confirm it works
```

`pipx` installs Ansible in an isolated venv so it never clashes with system
Python. (You can also use `sudo apt install ansible`, but pipx gives a newer
version.)

---

## 3. First contact — prove connectivity

From this directory (`.../advanced-ovn-ic/ansible`):

```bash
ansible-playbook ping.yml
```

`ansible.builtin.ping` logs in over SSH, runs a tiny Python payload and expects
`pong`. Green means SSH + sudo user + Python all work — the foundation every later
stage relies on. It uses your existing `~/.ssh/config`, so it connects exactly
like `ssh az1`.

If you hit an SSH host-key prompt, it is already silenced by
`host_key_checking = False` in `ansible.cfg`.

---

## 4. Project layout

```
ansible/
├── ansible.cfg            # default flags (inventory, ssh tuning)
├── inventory.ini          # hosts: az1, az2 (+ ic_host group)
├── group_vars/all.yml     # variables shared by both AZs
├── host_vars/az1.yml      # AZ1-specific values (subnets, MACs, workloads...)
├── host_vars/az2.yml      # AZ2-specific values
├── ping.yml               # connectivity smoke test
├── common.yml             # Stage 2 playbook
├── site.yml               # runs every stage end-to-end (added last)
└── roles/
    ├── common/            # apt deps + compile OVN-IC from source
    ├── ovn_central/       # NB/SB ovsdb + northd (+ IC-NB/IC-SB on az1)
    ├── ovn_chassis/       # OVS chassis, ovn-controller, br-ex, host SNAT
    ├── ovn_topology/      # NB_Global, LS/LR/LRP, transit switch, gw chassis
    ├── ovn_services/      # OVN load balancer + SNAT/NAT
    ├── incus/             # install + init Incus
    └── workloads/         # containers on br-int, FastAPI + Postgres
```

---

## 5. Run order

Each stage has its own playbook so you can run and inspect them one at a time
(recommended while learning). `site.yml` chains stages 2-8 in order.

| # | Command                          | What it does                                       |
|---|----------------------------------|----------------------------------------------------|
| 0 | `ansible-playbook ping.yml`      | connectivity check                                 |
| 2 | `ansible-playbook common.yml`    | base packages + compile OVN-IC (~10-20 min)        |
| 3 | `ansible-playbook central.yml`   | NB/SB + northd (+ IC-NB/IC-SB on az1)              |
| 4 | `ansible-playbook chassis.yml`   | OVS chassis, ovn-controller, br-ex, host SNAT      |
| 5 | `ansible-playbook topology.yml`  | LS/LR/LRP, transit switch, gateways, ovn-ic        |
| 6 | `ansible-playbook services.yml`  | OVN load balancer + OVN SNAT                        |
| 7 | `ansible-playbook incus.yml`     | install + init Incus                                |
| 8 | `ansible-playbook workloads.yml` | containers on br-int + FastAPI + PostgreSQL        |
|   | `ansible-playbook site.yml`      | **all of stages 2-8, in order**                    |
| ✓ | `ansible-playbook verify.yml`    | exercises the four traffic paths                   |

Recommended first time: run stages 2-8 one at a time and read the output, then
run `verify.yml`. After that, `site.yml` re-runs everything idempotently.

Useful flags while learning:

- `--check` — dry run, report what *would* change (not every module supports it).
- `--limit az1` — act on one host only.
- `-v` / `-vvv` — more verbosity (full command output, SSH debugging).
- `--start-at-task "name"` — resume from a given task.

---

## 6. Gotchas carried over from `pratice-ovn-ic`

These are encoded directly in the roles, but keep them in mind when reading:

1. AZ identity comes from `NB_Global.name`.
2. The transit switch is created in IC-NB and propagated by `ovn-ic` — never in the
   local NB.
3. Restart `ovn-ic` after the topology is fully built (a handler does this).
4. `ic-route-adv` / `ic-route-learn` live on `NB_Global.options`, not the router.
5. Pin a gateway chassis on the transit LRP (`lrp-set-gateway-chassis`).

### New gotchas found bringing this lab up

These bit during the first real run and are now fixed in the roles:

6. **`ovn-ic` needs an explicit `--unixctl`.** The source-built binary defaults its
   control socket to `/usr/local/var/run/ovn/`, which doesn't exist, so it crash-loops
   with `binding failed: No such file or directory` and never propagates the transit
   switch. The unit passes `--unixctl={{ lab_dir }}/ovn-ic.ctl` instead.
7. **Incus 6.0's native OVS client can't attach to br-int** (`Failed to connect to
   OVS: ... listdbs failure - unexpected EOF`), even though `ovs-vsctl` works fine. So
   we use a `nictype=p2p` nic — Incus just builds the veth pair and we add the host
   side to br-int with `ovs-vsctl ... iface-id=<lsp>` ourselves. This is also more in
   line with the "OVN driven manually" design.
8. **Grant the app role on the demo table.** The table is created by the `postgres`
   superuser, so `appuser` connects fine but gets `permission denied for table
   lab_info` until granted. `db_setup.sh` now runs the `GRANT`s.

---

## 7. How the OVN databases are arranged

Short answer to *"is the IC database shared between the VMs?"*: the **interconnect**
databases are shared; the per-AZ ones are not.

| Database  | Port | Scope                | Runs on              | Who connects to it                                   |
|-----------|------|----------------------|----------------------|------------------------------------------------------|
| local NB  | 6641 | per-AZ, private      | each AZ (127.0.0.1)  | that AZ's `ovn-northd`, `ovn-nbctl`, `ovn-ic`         |
| local SB  | 6642 | per-AZ, private      | each AZ (127.0.0.1)  | that AZ's `ovn-northd`, `ovn-controller`, `ovn-ic`    |
| **IC-NB** | 6645 | **global, shared**   | az1 (172.18.3.175)   | **both** AZs' `ovn-ic`                               |
| **IC-SB** | 6646 | **global, shared**   | az1 (172.18.3.175)   | **both** AZs' `ovn-ic`                               |

Each AZ keeps its **own** control plane (its own `ovn-northd` + NB/SB) — that
isolation is the whole point of OVN-IC. The only thing both AZs touch is the pair
of **global IC databases** (hosted here on az1): the transit switch is declared in
IC-NB and `ovn-ic` copies it into every local NB; the registered gateways and the
routes each AZ advertises/learns live in IC-SB. So a workload's traffic never
depends on the *other* AZ's NB/SB — only on those two shared IC databases plus the
GENEVE tunnel between chassis. (For HA you'd run IC-NB/IC-SB clustered and reachable
by all AZs; a single host is fine for a lab.)

---

## 8. Testing — every check

On whichever VM you `ssh` into, first export the DB locations so the `ovn-*` tools
don't need `--db` (they read these env vars; no `sudo` needed — the DBs are on local
TCP. Only `ovs-vsctl` needs `sudo`):

```bash
export OVN_NB_DB=tcp:127.0.0.1:6641
export OVN_SB_DB=tcp:127.0.0.1:6642
export OVN_IC_NB_DB=tcp:172.18.3.175:6645
export OVN_IC_SB_DB=tcp:172.18.3.175:6646
```

### 8.0 One-shot (from the control node)
```bash
ansible-playbook verify.yml          # runs paths #1-#4 below and prints the results
```

### 8.1 Control plane (OVN-IC)
```bash
ovn-ic-sbctl show                     # both gateways (az1-chassis, az2-chassis) + transit ports
ovn-ic-nbctl show                     # the transit switch as declared globally
ovn-nbctl lr-route-list lr-az1        # on az1: shows 10.10.2.0/24 ... (learned)
ovn-nbctl lr-route-list lr-az2        # on az2: shows 10.10.1.0/24 ... (learned)
ovn-sbctl show                        # chassis + which one owns each port binding
sudo ovs-vsctl show | grep -A2 geneve # the GENEVE tunnel to the peer's encap-ip
```

### 8.2 Data plane — ICMP (the classic interconnect proof)
The interconnect links the **workloads** in different AZs (over the transit switch /
GENEVE). The AZ *hosts* (az1/az2) reach each other on the underlay 172.18.x — that's
just the tunnel transport, not the interconnect.

```bash
# intra-AZ (both in AZ2, local):
sudo incus exec app-vm-2 -- ping -c3 10.10.2.10        # -> db-vm

# inter-AZ (AZ1 -> AZ2, crosses the transit switch over GENEVE):
sudo incus exec app-vm-1 -- ping -c3 10.10.2.10        # -> db-vm
sudo incus exec app-vm-1 -- ping -c3 10.10.2.20        # -> app-vm-2
```

### 8.3 MTU / GENEVE pressure
The workload NIC MTU is 1442 (1500 − 58 of GENEVE encap). With don't-fragment, the
largest ICMP payload that fits is 1442 − 28 = 1414 bytes:

```bash
sudo incus exec app-vm-1 -- ping -c2 -M do -s 1414 10.10.2.10   # OK (fills the 1442 frame)
sudo incus exec app-vm-1 -- ping -c2 -M do -s 1500 10.10.2.10   # fails: fragmentation needed
```
This is the pressure plain ping hid: at the default 1500 MTU, full-size cross-AZ TCP
would silently stall.

### 8.4 Data plane — L4 (HTTP + Postgres, the real point)
```bash
# Path #1 - load balancer across AZs (served_by flips app-vm-1 / app-vm-2):
for i in $(seq 10); do curl -s http://10.10.1.100/; echo; done      # on az1

# Path #2 - cross-AZ DB query (AZ1 app -> AZ2 db, over GENEVE): higher db_latency_ms
sudo incus exec app-vm-1 -- curl -s localhost:8000/ ; echo

# Path #3 - intra-AZ DB query (AZ2 app -> AZ2 db, local): lower db_latency_ms
sudo incus exec app-vm-2 -- curl -s localhost:8000/ ; echo

# Raw Postgres wire protocol, cross-AZ, by hand:
sudo incus exec app-vm-1 -- /opt/app/venv/bin/python -c \
"import psycopg; print(psycopg.connect('host=10.10.2.10 dbname=appdb user=appuser password=apppass').execute('SELECT now()').fetchone())"
```

### 8.5 North-south (SNAT)
```bash
# Path #4 - internet from a container, via the OVN router's external port + host MASQUERADE:
sudo incus exec app-vm-1 -- curl -sI https://example.org | head -1   # HTTP/2 200

# the VIP reached from outside the fabric (the az1 host itself):
curl -s http://10.10.1.100/ ; echo                                   # on az1
```

### 8.6 The web UI (frontend)
The FastAPI app serves a one-page dashboard at `/ui`. From your laptop/WSL, tunnel
to the VIP through az1 and open it in a browser:

```bash
ssh -L 8080:10.10.1.100:80 az1
# then browse:  http://localhost:8080/ui
```
The page auto-refreshes every 3s; the badge flips between **AZ1** and **AZ2** as the
OVN load balancer picks a backend, and the latency line switches between *cross-AZ
(over the GENEVE tunnel)* and *intra-AZ (local)*. (`Connection: close` is set so each
refresh is a fresh connection the LB can re-balance.) JSON lives at `/` and `/health`.

### 8.7 Idempotency
```bash
ansible-playbook chassis.yml          # re-run any stage: expect changed=0
```

### 8.8 If something looks off — where to look
```bash
sudo systemctl status ovn-ic ovn-northd ovn-controller    # daemons up?
sudo tail -n 40 /opt/ovn-lab/ovn-ic.log                   # interconnect sync / errors
sudo ovs-vsctl show                                       # bridges, tunnels, ports
sudo incus list                                           # containers running?
sudo incus exec <name> -- ip -4 addr show eth0            # got its 10.10.x.x / mtu 1442?
```

---

## 9. Resetting

The roles are idempotent, so you normally just re-run a playbook. To wipe the
workloads and start the data plane fresh:

```bash
# remove the containers on both AZs
ansible azs -b -m shell -a 'incus delete -f app-vm-1 app-vm-2 db-vm 2>/dev/null; true'
# then re-run from stage 8
ansible-playbook workloads.yml
```

To rebuild the OVN logical topology from scratch, delete the local NB/SB
databases and re-run `central.yml` onward:

```bash
ansible azs -b -m shell -a 'systemctl stop ovn-ic ovn-northd ovn-nb-db ovn-sb-db; rm -f /opt/ovn-lab/ovnnb.db /opt/ovn-lab/ovnsb.db'
ansible-playbook central.yml topology.yml services.yml workloads.yml
```

A reboot loses the non-persistent host bits (br-ex IP, iptables, VIP route);
just re-run `chassis.yml services.yml` to restore them.

---

## 10. Note on the WSL ↔ remote shell

Ansible runs entirely on the WSL control node and talks to the VMs over SSH, so
none of the Windows/WSL/Git-Bash quoting quirks apply here — you invoke
`ansible-playbook` from a normal WSL shell.

