# Core Tools for SDN Project Development with OVN

This document gathers the main tools used in the development and experimentation of **Software-Defined Networking (SDN)** solutions based on **OVN**. It is meant as a starting point for anyone joining the SDN front who needs to set up an environment for studying, prototyping or validating ideas.

---

## OVN — Open Virtual Network

[Official repository](https://github.com/ovn-org/ovn)

**OVN** is the heart of any project developed in this front. It is a network virtualization solution built on top of Open vSwitch that adds native support for logical entities such as *logical switches*, *logical routers*, ACLs, *load balancers*, NAT and DHCP, abstracting network configuration away from the physical *datapath*.

The OVN architecture is organized around two main databases:

- **OVN Northbound (NB-DB):** describes the desired logical topology (the network "intent").
- **OVN Southbound (SB-DB):** translates that topology into *logical flows* and *binding* information consumed by the *chassis*.

Main components:
- `ovn-northd` — converts NB into SB.
- `ovn-controller` — runs on each *chassis* and programs the local OVS based on SB.
- `ovn-nbctl` / `ovn-sbctl` — CLIs to inspect and manipulate the databases.

It is the canonical reference for source code, *schemas* and documentation. Whenever there is doubt about behavior, this is the first place to look.

---

## Open vSwitch (OVS)

[Official repository](https://github.com/openvswitch/ovs)

**OVS** is the foundation OVN is built upon. It is the programmable virtual *switch* that actually performs packet *forwarding* on each node. Key components:

- `ovs-vswitchd` — *daemon* responsible for applying flows in the *datapath*.
- `ovsdb-server` — stores the local *switch* configuration.
- `ovs-vsctl` / `ovs-ofctl` — CLIs for configuration and flow inspection.

OVS and OVN versions must be compatible with each other — it is generally necessary to pin a specific OVS *release* when compiling OVN from source (e.g., OVN v24.03 with OVS v3.3.0).

---

## ovn-fake-multinode

[Official repository](https://github.com/ovn-org/ovn-fake-multinode)

Tool for **simulating multiple OVN physical nodes inside a single machine**, using containers. Each "node" runs its own `ovn-controller` and `ovs-vswitchd`, connected to a central NB/SB *cluster*.

It is the most practical option for:
- prototyping topologies and quickly validating logical configurations;
- testing *exporters*, automation *scripts* and tools that depend on NB/SB events;
- reproducing bugs or behaviors without having to provision VMs or *hardware*.

**Important limitation:** by sharing the same *kernel*, it masks aspects of the real *datapath* (locally resolved GENEVE tunnels, no *firewall* between *chassis*, MTU/encap overhead not exercised). To validate production-like behavior it is necessary to evolve toward separate VMs.

---

## ovn-heater

[Official repository](https://github.com/ovn-org/ovn-heater)

*Framework* for **load and performance testing** of OVN. It automatically provisions an environment based on `ovn-fake-multinode` and runs parameterizable scenarios (massive creation of *logical switches*, *port bindings*, *load balancers*, etc.), collecting metrics of convergence time and resource usage.

Useful when the focus is on understanding *control plane* scalability — for example, evaluating the impact of a change in `ovn-northd` or comparing versions.

---

## ovn-kubernetes

[Official repository](https://github.com/ovn-org/ovn-kubernetes)

**Kubernetes CNI implementation based on OVN**. Not strictly required for pure infrastructure projects, but a valuable reference for how OVN is integrated into large-scale production environments — and a source of many reusable *patterns* and auxiliary tools.

---

## ovn-event-exporter

[Official repository (CloudFerro)](https://github.com/cloudferro/ovn-event-exporter)

Prometheus *exporter* developed by CloudFerro that captures, in real time, *create*, *update* and *delete* events on OVN-SB tables and exposes them as metrics. It replaces the initial in-house development effort (issue CLO-57) and is the recommended starting point for anyone needing granular *control plane* observability.

---

## Quick reference

| Tool | When to use |
|------|-------------|
| **OVN** | Always. It is the base of everything. |
| **OVS** | Always — comes with OVN, but may need version pinning when building from *source*. |
| **ovn-fake-multinode** | Fast prototyping, logical tests, development of auxiliary tools. |
| **ovn-heater** | Load testing, *benchmarks* and scalability analysis. |
| **ovn-kubernetes** | When the scenario involves Kubernetes or as an integration reference. |
| **ovn-event-exporter** | OVN-SB observability via Prometheus/Grafana. |

For production-like validations (real datapath, GENEVE tunnels between hosts, *firewall* between *chassis*), none of these tools replaces setting up the environment on **separate VMs or hosts**.
