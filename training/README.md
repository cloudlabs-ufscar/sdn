# Training: Network Virtualization & SDN

This training is designed to build deep technical expertise in **Software-Defined Networking (SDN)**, following a specific path from virtual switching fundamentals to large-scale cloud orchestration.

The methodology focuses on the **"Ground-to-Cloud"** approach: understanding how a single packet moves through a virtual switch before managing thousands of packets across a data center.

##  Methodology

Each module in this repository corresponds to a core technical domain in the SDN stack. To maximize retention, every topic includes:

- **Theoretical Deep-Dive:** Covering internal architectures physical and virtual and protocols.
- **Packet Analysis:** Hands-on observation of traffic using tools like `ovs-appctl` and `tcpdump`.
- **Automation:** A dedicated script to deploy the specific topology required for that module's experiments.

## Roadmap

### Phase 0: Previous Requirements
- **Module 0:** Networking and Operational System Fundamentals 

### Phase 1: Virtual Switching Fundamentals
- **Module 01:** Introduction to Open vSwitch (OVS) — Understanding the OVS architecture: `ovs-vswitchd`, `ovsdb-server`, and the kernel datapath.
- **Module 02 & 03:** OpenFlow Deep-Dive — Introduction to the OpenFlow protocol and managing Multiple Flow Tables for complex packet processing logic.

### Phase 2: SDN & Overlay Networking
- **Module 04:** SDN Architecture — Transitioning from traditional networking to the decoupling of the Control Plane and Data Plane.
- **Module 05:** Geneve Overlay — Implementing tunneling and encapsulation using the Geneve protocol with Open vSwitch for network virtualization.

### Phase 3: Advanced Orchestration & OVN
- **Module 06:** Introduction to OVN (Open Virtual Network) — Learning the OVN Northbound/Southbound databases and how it abstracts OVS into a logical network.
- **Module 07:** OpenStack Neutron Integration — Understanding how SDN powers massive clouds and how Neutron interacts with OVS/OVN.

### Phase 4: Diagnostics & Performance
- **Module 08:** Packet Walkthrough — A complete technical audit of how a packet traverses the entire virtual stack, from source to destination.
- **Module 09:** OVS Troubleshooting — Practical techniques for debugging flows, resolving connectivity issues, and optimizing the datapath.

##  Laboratory Requirements

To execute the automation scripts provided in these modules, you will need:

- A Linux environment (Ubuntu 22.04+ or Arch Linux recommended)
- Nested Virtualization enabled (if running inside a VM)
- Packages: `openvswitch-switch`, `qemu-kvm`, `cloud-utils`

---
