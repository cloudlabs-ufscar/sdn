# Practical laboratory for testing and understanding the OVN Interconnect (CLO-77)

### Scenario
**OVN Interconnect (OVN-IC)** is the mechanism that allows federating multiple *Availability Zones* (AZs) into a unified logical network, enabling VMs and *workloads* in independent OVN installations to communicate with each other as if they were in the same fabric. This interconnection is performed through two global databases (**IC-NB** and **IC-SB**), which synchronize *transit switches*, *gateways* and routes across AZs — while each AZ keeps its own *control plane* (`ovn-northd`, NB-DB, SB-DB) and its local *chassis*, ensuring operational isolation without losing the ability to communicate between domains.

### Problem
Configuring Interconnect in practice is non-trivial and requires a proof of concept before adoption. It is necessary to make two separate OVN installations, each with its own independent *control plane*, discover one another, register *gateways* in the global databases, establish **GENEVE** tunnels between *chassis*, and propagate routes consistently. On top of that, there are relevant gaps in the official documentation and in distribution packaging — for example, the `ovn-central` package on Ubuntu 24.04 ships neither the `ovn-ic` binary nor the IC-NB/IC-SB *schemas* —, which increases the complexity of building a functional environment.

### Proposal
Set up a functional OVN-IC lab in a virtualized environment to understand, in practice, how OVN federates multiple AZs, with the perspective of later evolving toward real use on **Stratus/Cirrus**.

**Phase 1 — single-VM lab (issue [CLO-77](https://linear.app/cloudlabs/issue/CLO-77/practical-laboratory-for-testing-and-understanding-the-ovn)):**
The first attempt was made on a single VM, with both AZs simulated entirely through *Linux network namespaces*: one *namespace* per AZ running its own `ovs-vswitchd` and `ovn-controller`, plus additional *namespaces* representing the *workloads*. This setup allowed us to quickly validate the OVN-IC logical topology (*transit switch*, *routers*, *route advertisement*) at no infrastructure cost.

However, this experiment masks critical aspects of the *datapath*:
- the GENEVE tunnel is resolved inside the same *kernel*;
- there is no real *firewall* between *chassis*;
- MTU and *encap* overhead are not exercised;
- synchronization *bugs* between separate `ovn-controller` instances stay hidden.

**Phase 2 — two separate Ubuntu 24.04 VMs:**
For these reasons, the second phase migrated to two separate Ubuntu 24.04 VMs (**AZ1** at `172.18.3.181`, **AZ2** at `172.18.17.9`), with IC-NB/SB hosted on VM1 and accessed remotely over TCP from VM2 — a scenario that is architecturally equivalent to production.

The setup quickly revealed a relevant packaging limitation: since the `ovn-central` package on Ubuntu 24.04 does not ship the `ovn-ic` binary nor the IC-NB/IC-SB *schemas*, we had to compile **OVN from source (v24.03.6)**, pinning **OVS to release v3.3.0** to avoid incompatibility with the upstream *main* branch. On each VM, the *setup*:
- brings up the `ovsdb-server`, `ovn-northd`, `ovn-controller` and `ovn-ic` databases/services;
- configures the *chassis* in OVS with `ovn-is-interconn=true`;
- creates the logical topology: a *logical switch* for the *workloads*, a *logical router* acting as a *gateway*, and the connection of that *router* to the shared *transit switch*.

The *workloads* are simulated with *network namespaces* connected to `br-int` via *veth pairs*.

**Key debugging lessons:**

1. **AZ identity comes from `NB_Global.name`.** The AZ name that `ovn-ic` registers in IC-SB is derived from the `NB_Global.name` field of the local NB — without it, no *gateway* is registered even with the topology fully correct.

2. **The transit switch is managed by `ovn-ic`.** The `ts` on the local NB **must not be created manually**: `ovn-ic` detects the `ts` declared in IC-NB and creates it automatically in each AZ with the `interconn-ts` annotation already applied; trying to create it manually causes a collision.

3. **`ovn-ic` startup race condition.** The first `ovn-ic` instance can process the `lsp-ts-az*` notification before its local cache contains the corresponding `lrp-az*-ts`, producing the warning `Can't get router uuid for transit switch port` followed by `Route sync ignores port ... Deleting it` — the port is dropped and never reprocessed. The fix is to restart `ovn-ic` after the full topology has been built, forcing a clean state read via the OVSDB monitor.

4. **`ic-route-adv` and `ic-route-learn` live on `NB_Global.options`, not on `Logical_Router`.** This was the most time-consuming gotcha: the documentation suggests these options are per-router, but in OVN 24.03.6 they only have effect when applied on `NB_Global`. Confirmed by inspecting `strings` on the `ovn-ic` binary — the `Transit_Switch` table doesn't even have an `options` column in this version. Without this fix, `ovn-ic`'s debug log shows `Route ad: skip network 10.0.1.1/24 of lrp lrp-az1-ls.`, meaning it silently refuses to advertise connected networks even with `ic-route-adv=true` set on the LR.

5. **Explicit gateway chassis on the transit LRP.** Even with the *control plane* working (routes advertised and learned in IC-SB), the *data plane* stayed broken. Inspecting the local SB `Port_Binding`, `lsp-ts-az2` (as seen from AZ1) had `chassis=[]`, and in IC-SB the `gateway` field was empty. It was necessary to explicitly pin a gateway chassis on the LRP that connects to the transit switch: `ovn-nbctl lrp-set-gateway-chassis lrp-az1-ts az1-chassis 1`. Without this, `ovn-ic` does not bind a gateway to the Port_Binding and inter-AZ traffic never gets encapsulated in GENEVE.

### Results
The lab is now **fully operational end-to-end**:
- *gateways* registered in both AZs (`az1-chassis`, `az2-chassis` in IC-SB);
- GENEVE tunnels established between the VMs;
- connected route propagation working automatically across AZs (`10.0.1.0/24` and `10.0.2.0/24` visible as `(learned)` routes on the opposite AZ);
- *intra-AZ* and *inter-AZ* pings succeed with **0% packet loss**:
  - `vm1-az1 ↔ vm2-az1`, `vm1-az2 ↔ vm2-az2` (intra-AZ);
  - `vm1-az1 ↔ vm1-az2`, `vm1-az1 ↔ vm2-az2`, `vm1-az2 ↔ vm2-az1` (inter-AZ).

The produced *scripts* are **idempotent** — they perform a full *cleanup* before each execution — and remain as a reproducible reference for spinning up the environment again, documenting the gotchas above that are not evident in the official documentation.

This proof of concept validates **OVN-IC as a federation solution applicable to our infrastructure** and unblocks the next step of the project, advancing issue [CLO-73](https://linear.app/cloudlabs/issue/CLO-73/configure-ovn-ic-federation): communication between virtual machines in different Incus clouds.

Run `setup-az1.sh` first and wait for it to finish, then run `setup-az2.sh` on the other VM. Both scripts are idempotent and can be re-run at any time.
