# OVN Interconnect (OVN-IC) 

## What is OVN-IC?

**OVN Interconnect (OVN-IC)** is the OVN component that enables the federation of multiple **Availability Zones (AZs)** — that is, multiple independent OVN installations — into a single, unified logical network. With OVN-IC, VMs and *workloads* hosted in physically and administratively separate OVN deployments can communicate as if they were in the same logical fabric, while each deployment retains autonomy over its own *control plane*.

In a single-AZ OVN deployment, the *control plane* is composed of `ovn-northd`, the **Northbound (NB-DB)** and **Southbound (SB-DB)** databases, and `ovn-controller` instances on each *chassis*. OVN-IC introduces two **global** databases on top of this model:

- **IC-NB (Interconnect Northbound):** declares the federation intent — which transit switches exist and which AZs participate.
- **IC-SB (Interconnect Southbound):** distributes runtime information across AZs — registered *gateways*, advertised routes, etc.

Each AZ runs an `ovn-ic` *daemon* that synchronizes the local NB/SB with the global IC-NB/IC-SB. Inter-AZ traffic flows through a **transit switch**, a special logical switch that acts as a shared backbone, and is encapsulated over **GENEVE** tunnels established between the *gateway chassis* of each AZ.

---

## Why study OVN-IC?

### 1. It solves a real problem in multi-site SDN
Modern cloud infrastructures rarely live inside a single data center or a single failure domain. Operators frequently need to:
- isolate workloads by region, tenant or compliance domain;
- keep independent *control planes* for blast-radius reasons;
- still allow controlled communication between those domains.

OVN-IC is a **first-class, native answer** to this need within the OVN ecosystem — there is no need to bolt on external VPNs, BGP overlays or proprietary federation layers.

### 2. It pushes you deep into OVN internals
Setting up OVN-IC forces a hands-on understanding of:
- the relationship between **NB → SB → datapath** in a single AZ;
- how `ovn-northd`, `ovn-controller` and the **OVSDB** protocol cooperate;
- how **GENEVE tunnels** are negotiated and which fields the encapsulation actually carries;
- how *logical routers*, *gateways* and *transit switches* interact;
- route advertisement / learning semantics (`ic-route-adv`, `ic-route-learn`).

This makes OVN-IC an **excellent learning vehicle** for SDN as a whole — every layer of OVN is exercised in a single lab.

### 3. The documentation is sparse
The official documentation covers the conceptual model but glosses over many practical details: AZ naming rules, automatic transit-switch creation, packaging gaps in popular distributions (e.g., the `ovn-central` package on Ubuntu 24.04 does not ship the `ovn-ic` binary or the IC-NB/IC-SB *schemas*). Studying OVN-IC means producing the kind of documentation that the community itself benefits from.

---

## Why develop with OVN-IC?

### Architecturally clean federation
Unlike approaches that simply stretch a single *control plane* across regions (introducing scale and reliability issues), OVN-IC keeps each AZ **fully independent**: each one has its own NB, SB, `ovn-northd` and *chassis*. The federation layer is additive, not invasive — if IC-NB/IC-SB are unavailable, intra-AZ traffic keeps working.

### Predictable and observable
Because all federation state lives in well-defined OVSDB tables (gateways, routes, transit switches), the system is **introspectable** with the same tooling already used for OVN: `ovn-ic-nbctl`, `ovn-ic-sbctl`, plus the regular `ovn-nbctl` / `ovn-sbctl`. This plays well with observability stacks (e.g., Prometheus *exporters* for OVN-SB events).

### Reproducible labs
With ` ovn-fake-multinode` for fast iteration and a pair of VMs for production-like validation, it is feasible to build **idempotent setup scripts** that spin up a full multi-AZ OVN-IC environment from scratch. This is a strong base for CI-style regression testing of network changes.

### Direct path to production
The lab topology (two AZs, transit switch, *gateway chassis*, GENEVE tunnels) is **architecturally equivalent** to what a production multi-cloud or multi-region deployment looks like. The same scripts and configuration patterns developed in the lab translate directly to environments such as **Stratus/Cirrus** or any Incus-based multi-cloud infrastructure.

---

## When does OVN-IC make sense?

| Scenario | Fit |
|----------|-----|
| Multiple independent clouds/regions that need L2/L3 connectivity between workloads | **Strong** |
| Compliance/blast-radius isolation with controlled cross-domain traffic | **Strong** |
| Single-site, single-AZ deployment | Overkill — plain OVN is enough |
| Federation between OVN and **non-OVN** networks | Use BGP/EVPN instead |
| Need for *control plane* HA only (not federation) | Use **clustered NB/SB**, not OVN-IC |

---

## Key concepts at a glance

- **AZ (Availability Zone):** an independent OVN installation, with its own `ovn-northd`, NB-DB, SB-DB and *chassis*.
- **IC-NB / IC-SB:** global databases that hold the federation state shared between AZs.
- **Transit switch:** logical switch declared in IC-NB and automatically materialized in each AZ; acts as the inter-AZ backbone.
- **Gateway chassis:** *chassis* in an AZ designated to terminate GENEVE tunnels coming from other AZs.
- **`ovn-is-interconn=true`:** OVS flag that marks a *chassis* as eligible to participate in OVN-IC.
- **`ic-route-adv` / `ic-route-learn`:** options that control which routes are advertised to and learned from other AZs.

---

## Further reading

- [OVN repository](https://github.com/ovn-org/ovn) — source code, *schemas* and reference documentation.
- [OVN Interconnect documentation](https://docs.ovn.org/en/latest/tutorials/ovn-interconnection.html) — official tutorial (high-level).
- [ovn-fake-multinode](https://github.com/ovn-org/ovn-fake-multinode) — ideal for iterating quickly on OVN-IC topologies.
