# Multi-AZ DBaaS-style lab over OVN-IC (CLO-73)

## Context

This lab is the evolution of the [single-service OVN-IC proof of concept](../pratice-ovn-ic/README.md),
where two simulated AZs only validated intra/inter-AZ **ICMP** between *network namespaces*.
That setup proved the OVN-IC control plane (transit switch, gateways, route advertisement)
but, as its own README notes, it **masks the datapath**: no real TCP service, no MTU/encap
pressure, no firewall between chassis, workloads that are just namespaces.

This project takes the next step asked by issue
[CLO-73](https://linear.app/cloudlabs/issue/CLO-73/configure-ovn-ic-federation):
**real virtual machines in different Incus clouds, federated by OVN-IC, running a useful workload.**

It is intentionally a *scaled-down* adaptation of the production Magalu architectures
(`dd-azdc-architecture`, `dd-xaas-network`): one tenant, one VPC federated across two AZs,
a real web application load-balanced across both AZs, and a managed database the app consumes.

## Goal

Go beyond ping: exercise the OVN-IC **datapath** with real TCP traffic (HTTP + the Postgres
wire protocol) crossing GENEVE tunnels between two separate Incus clouds, plus two OVN
features the previous lab never touched — the **native OVN load balancer** and **SNAT to
the internet**.

## Topology

Two robust-enough VMs, each one a self-contained AZ (Incus cloud + its own OVN control
plane + `ovn-ic` + chassis + gateway role). A single tenant VPC is federated across both
AZs through one transit switch.


- **app-vm-1** (AZ1) and **app-vm-2** (AZ2): identical FastAPI backends listening on `:8000`.
- **db-vm** (AZ2): single Postgres instance, the app's database.
- **OVN load balancer**: VIP `10.10.1.100:80` distributes to `app-vm-1:8000` and
  `app-vm-2:8000`. Half the requests land on the AZ2 backend, crossing the transit switch
  on the way *in*.
- **SNAT** on each AZ router gives the Incus instances outbound internet (package installs)
  and lets you reach the VIP from outside the fabric.

### Addressing

| Plane          | AZ1 (VM1)               | AZ2 (VM2)               | Transit                       |
|----------------|-------------------------|-------------------------|-------------------------------|
| VPC subnet     | `10.10.1.0/24` (gw .1)  | `10.10.2.0/24` (gw .1)  | `ts` → `169.254.100.0/24`     |
| Workloads      | `app-vm-1` .10 · VIP .100 | `app-vm-2` .20 · `db-vm` .10 | `lrp-ts`: az1 .1 / az2 .2 |

## Traffic paths this lab exercises (that ping did not)

1. **LB ingress crossing AZs.** A request to the VIP can be sent by the OVN LB to
   `app-vm-2` in AZ2 — so the very first hop already traverses the `ts` over GENEVE.
2. **Cross-AZ DBaaS query.** `app-vm-1` (AZ1) → `db-vm` (AZ2): a real Postgres connection
   over the transit switch. This is where MTU and GENEVE encap overhead finally bite.
3. **Intra-AZ query for contrast.** `app-vm-2` (AZ2) → `db-vm` (AZ2): same query, local
   path — a clean before/after latency comparison against path #2 in a single test run.
4. **North-south via SNAT.** Instances reach the internet through the AZ router's external
   port; the VIP is reachable from outside the fabric.

## What carries over from the previous lab

The hard-won OVN-IC gotchas documented in
[`pratice-ovn-ic/README.md`](../pratice-ovn-ic/README.md) all still apply and are reused here:

1. AZ identity comes from `NB_Global.name`.
2. The transit switch is created in IC-NB and propagated by `ovn-ic` — never created manually
   in the local NB.
3. Restart `ovn-ic` after the topology is fully built to avoid the startup race condition.
4. `ic-route-adv` / `ic-route-learn` live on `NB_Global.options` (OVN 24.03.6), not on the
   `Logical_Router`.
5. Pin a gateway chassis on the transit LRP (`lrp-set-gateway-chassis`) or the data plane
   stays broken even with routes advertised.

Same build constraints too: OVN compiled from source (v24.03.6), OVS pinned to v3.3.0,
because the `ovn-central` package on Ubuntu 24.04 ships neither the `ovn-ic` binary nor the
IC-NB/IC-SB schemas.

## What's new vs. the previous lab

| Aspect      | pratice-ovn-ic            | this lab                                  |
|-------------|---------------------------|-------------------------------------------|
| Workloads   | network namespaces        | **Incus instances** plugged into `br-int` |
| Traffic     | ICMP echo                 | **HTTP + Postgres** (real TCP over GENEVE) |
| Routers     | 1 per AZ                  | 1 per AZ + **OVN LB** + **SNAT**          |
| Useful?     | connectivity proof only   | a real app you can `curl` and query       |

## Design decisions

- **OVN driven manually, Incus only provides the workloads.** Incus has native OVN
  networking, but it manages the NB itself and does not expose OVN-IC configuration. To keep
  full control of the interconnect we run OVN/`ovn-ic` by hand (as in the previous lab) and
  attach Incus instances to `br-int` via OVS ports with the right `iface-id`.
- **Native OVN load balancer** instead of a HAProxy VM — fewer moving parts and it
  demonstrates an OVN feature directly.
- **Single Postgres in AZ2** (no replica). Architecturally this couples failure domains and
  adds latency for AZ1 — irrelevant for a study lab, and it gives us the intra- vs. inter-AZ
  contrast for free. Promoting this to primary/replica with failover is the natural next
  layer.

## Status

Design only. Scripts (`setup-az1.sh`, `setup-az2.sh`) to be built and validated directly on
the two VMs, starting from the working scripts in `../pratice-ovn-ic/`.
