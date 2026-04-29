# OVN Interconnect Practical Laboratory

A functional OVN Interconnect laboratory to understand, in practice, how OVN federates multiple Availability Zones into a unified logical mesh.

## Overview

OVN-IC synchronizes, via two global databases (IC-NB and IC-SB), the transit switches, gateways, and routes between AZs, each of which has its own control plane (ovn-northd, NB DB, SB DB) and local chassis.

## Project Evolution

### First Attempt: Single VM with Namespaces

The first attempt was made on a single VM, with both AZs simulated entirely using Linux network namespaces — one namespace per AZ running its own ovs-vswitchd and ovn-controller, and other namespaces representing the workloads.

**Advantages:**
- Quick validation of OVN-IC logical topology
- Low infrastructure cost

**Limitations:**
- Masks critical datapath aspects
- GENEVE tunnel resolves within the same kernel
- No real firewall between chassis
- MTU and encapsulation overhead are not exercised
- Synchronization bugs between separate instances remain hidden

### Second Phase: Two Separate VMs

The second phase migrated to two separate Ubuntu 24.04 VMs:
- **AZ1:** 172.18.3.181
- **AZ2:** 172.18.17.9

IC-NB/SB hosted on VM1 and accessed remotely via TCP by VM2 — an architecture-wise production-equivalent scenario.

## Challenges and Solutions

### OVN Packaging

The setup quickly revealed a relevant packaging limitation: the `ovn-central` package on Ubuntu 24.04 does not distribute the `ovn-ic` binary or the IC-NB/IC-SB schemas.

**Solution:** Compile OVN from source code (v24.03.6), pinning OVS to release v3.3.0 to avoid incompatibility with the upstream main branch.

### Setup on Each VM

On each VM:
1. Bring up databases: ovsdb-server, ovn-northd, ovn-controller, and ovn-ic
2. Configure the chassis in Open vSwitch with `ovn-is-interconn=true`
3. Create the logical topology:
   - A logical switch for the workloads
   - A logical router as gateway
   - Connection of the router to the shared transit switch
4. Simulate workloads with network namespaces connected to br-int via veth pairs

## Critical Technical Learnings

### 1. NB_Global.name is Critical

The AZ name that ovn-ic registers in IC-SB comes from the `NB_Global.name` field of the local NB. **Without it, no gateway is registered even with the entire topology configured correctly.**

### 2. Transit Switch Created Automatically

The transit switch in the local NB **must not be created manually**. The ovn-ic:
- Detects the ts declared in IC-NB
- Creates it automatically in each AZ
- Applies the `interconn-ts` annotation automatically
- Attempting to create it manually causes collision

### 3. Route Advertisement at Two Levels

Route propagation between AZs depends on:
- `ic-route-adv` and `ic-route-learn` on the local logical router
- `ic-route-adv` and `ic-route-learn` on the IC-NB transit switch

## Current Status

✅ **Working:**
- Gateway registration
- GENEVE tunnels established between VMs
- Intra-AZ traffic operational

🔄 **In Progress:**
- Route record propagation in IC-SB
- Unblocking inter-AZ traffic

## Reproducibility

The scripts produced are **idempotent** — they perform complete cleanup before each execution — and serve as a reproducible reference for bringing up the environment again, documenting the gotchas that are not evident in the official documentation.

## Next Steps

1. Finalize Route record propagation in IC-SB
2. Validate end-to-end inter-AZ traffic
3. Document final topology with diagrams
4. Create troubleshooting guide based on errors encountered

## References

- [OVN Interconnect Documentation](https://ovn.org/)
- OVN v24.03.6 (compiled from source)
- Open vSwitch v3.3.0
