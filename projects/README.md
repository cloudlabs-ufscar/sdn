# Projects: SDN Implementations

This section is dedicated to the practical application of SDN architectures in high-complexity, production-like scenarios. Moving beyond individual modules, these projects focus on the intercommunication of distributed systems, multi-datacenter simulation, and the continuous observability of the OVN control plane.

The objective is to simulate real-world cloud provider environments, ensuring that the Software-Defined Network is not only functional but also resilient, observable, and high-performing.

##  Project A: OVN Interconnect (Multi-DC Architecture)
Focuses on the logical interconnection of separate **Incus** clusters to simulate a multi-region infrastructure.

**Core Research Areas:**
* **OVN-IC (Interconnect):** Configuring Transit Switches and Chassis synchronization across different physical networks.
* **Storage Integration:** Validating **CEPH** and **LINSTOR** performance over a virtualized L2/L3 stretch.
* **Workload Mobility:** Testing live migration and Floating IP (FIP) consistency across cluster boundaries.

##  Project B: SRE & SDN Observability
Implementation of a full-stack monitoring system to ensure the health of the OVN databases and flow tables.

**Core Research Areas:**
* **Metric Scraping:** Utilizing `ovn-exporter.py` to extract real-time data from the Southbound and Northbound databases.
* **Visualization:** Designing Prometheus & Grafana dashboards for proactive troubleshooting.
* **Stress Analysis:** Conducting load tests and heartbeat resilience checks, transitioning from `ovn-fake-multinode` to production clusters.

##  Project C: Open-Source Contribution
A deep-dive into the low-level architecture of the OVN project to contribute back to the community.

**Core Research Areas:**
* **Source Code Analysis:** Studying the C and Python implementations of OVN daemons.
* **Upstream Development:** Identifying bugs or feature gaps and submitting patches to the official OVN/OVS project.


> Note: These projects are currently under development and may be subject to change at any time.
