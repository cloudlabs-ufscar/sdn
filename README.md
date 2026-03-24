# Open-Source Network Virtualization & SDN Training 

This repository is a structured guide focused on developing solid technical competencies in **Software-Defined Networking (SDN)**, with an emphasis on **Open vSwitch (OVS)** and **Open Virtual Network (OVN)**.

The objective is to empower developers and network engineers to design, implement, and operate virtual networks, as well as troubleshoot real-world issues in cloud environments and programmable infrastructure.

---

## Repository Structure

### 1. Training Section
The training is divided into two main sections to facilitate learning progression:
* **Theory:** Detailed and intuitive explanation of concepts through documentation.
* **Practice:** Hands-on exercises to consolidate knowledge.
* **Automation:** A dedicated automation script for each module that provisions the laboratory (VMs, Bridges, Namespaces) instantly.

#### Modules:
- **Module 0:** Networking Fundamentals.
- **Module 1:** OpenFlow Introduction I.
- **Module 2:** OpenFlow Introduction II.
- **Module 3:** OpenFlow and multiple FlowTables.
- **Module 4:** SDN Introduction.
- **Module 5:** Geneve Overlay with Open vSwitch.
- **Module 6:** OVN Introduction I.
- **Module 7:** OVN Introduction II.
- **Module 8:** OpenStack Neutron Introduction.
- **Module 9:** Packet Walkthrough.
- **Module 10:** OVS Troubleshooting.


### 2. Projects Section
These projects are currently under development:


#### **Project A: OVN Interconnect**
Objective: Implement and validate the interconnection of two distinct Incus clusters located on different networks to simulate a multi-region cloud architecture.

Key Architectures:

- Deployment of OVN Interconnect to bridge logical networks across physical boundaries.

- Integration of distributed storage solutions (Ceph and Linstor) over OVN-interconnected infrastructure.

Validation & Lab Tests:

- Live Migration: Moving VMs between clusters with zero downtime.

- L2 Stretch: Proving seamless Ping connectivity between VMs in the same logical OVN network across different clusters.

- External Access: Implementing Floating IP allocation for cross-cluster service exposure.


#### **Project B: SDN Observability & SRE Stack**
Objective: Deploy a full-stack monitoring solution to gain deep visibility into the OVN control plane and data plane performance.

The Stack:

- Collection: Metrics extraction using ovn-exporter.

- Storage & Visualization: Prometheus and Grafana integration.

Experiments:

- Resilience Testing: Analyzing OVN heartbeat behavior and latency during simulated split-brain scenarios.

- Load Testing: Stressing the SDN using ovn-fake-multinode before moving to a production-grade cluster.

- Telemetry: Developing custom dashboards to visualize OVN flows, logical switch port statistics, and tunnel health.


#### **Project C: Open-Source Contribution (OVN Core)**
Objective: Bridge the gap between user-level implementation and kernel/daemon-level development.

Activities:

Deep-dive analysis of the OVN/OVS source code (C/Python).

Debugging low-level flow issues within the ovn-controller and Northbound/Southbound databases.

Goal: Achieve at least one active upstream contribution to the official OVN open-source project, improving the ecosystem for the community.
