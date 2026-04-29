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
1. The AZ name that `ovn-ic` registers in IC-SB comes from the `NB_Global.name` field of the local NB — without it, no *gateway* is registered even with the topology fully correct.
2. The *transit switch* on the local NB **must not be created manually**: `ovn-ic` detects the `ts` declared in IC-NB and creates it automatically in each AZ with the `interconn-ts` annotation already applied; trying to create it manually causes a collision.
3. Route propagation between AZs depends on `ic-route-adv` and `ic-route-learn` being configured not only on the local *logical router*, but also on the *transit switch* in IC-NB.

### Expected results
Obtain a **proof of concept for communication between virtual machines in different Incus clouds**, validating the use of OVN-IC as a federation solution applicable to our infrastructure. This will advance issue [CLO-73](https://linear.app/cloudlabs/issue/CLO-73/configure-ovn-ic-federation).

The lab today has:
- *gateways* successfully registered;
- GENEVE tunnels established between the VMs;
- *intra-AZ* traffic operational.

The last step in progress is the propagation of *Route records* in IC-SB, which is still being adjusted to unblock *inter-AZ* traffic. The produced *scripts* are **idempotent** — they perform a full *cleanup* before each execution — and remain as a reproducible reference for spinning up the environment again, documenting the gotchas that are not evident in the official documentation.
