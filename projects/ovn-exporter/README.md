# Deploy existing OVN-SB Event Monitoring Dashboard (CLO-57)

### Scenario
Observability is a fundamental pillar in OVN-based SDN environments, especially at scale, where tracking the *control plane* behavior in real time is essential to ensure reliable network operation. The Southbound database (**OVN-SB**) holds a central position in this ecosystem, since it concentrates information about *chassis*, *port bindings*, *MAC bindings*, *logical flows* and other entities that materialize the logical configuration into actual flows in the *datapath*. CloudFerro itself highlighted this point in their presentation *"[Having OVN at scale and sanity at once by CloudFerro](https://drive.google.com/file/d/1JqA8b8MJrOquNhvCVMpMWVZLk_E9msOt)"*, reinforcing the importance of dedicated instrumentation for this kind of environment.

### Problem
The native metrics exposed by OVN components do not granularly cover the create, update and delete events that occur on OVN-SB tables. Without this visibility, it becomes hard to diagnose bottlenecks, anomalous behaviors or to understand the pace of changes applied by the *control plane* — a gap that motivated the opening of issue **[CLO-57](https://linear.app/cloudlabs/issue/CLO-57/deploy-existing-ovn-southbound-event-monitoring-dashboard)**, aimed precisely at filling this observability void.

### Proposal
Resume the development of *[ovn-exporter](https://github.com/CloudFerro/ovn-event-exporter)*, with the goal of delivering a Python *exporter* for **Prometheus**, along with a **Grafana** *dashboard*, capable of monitoring and visualizing the relevant OVN-SB events in real time.

For this development, we decided to use the **ovn-fake-multinode** repository, which simulates multiple physical nodes on a single machine and is ideal for small-scale tests. After bootstrapping the environment, we built the *exporter* that scraped metrics from OVN-SB and, whenever there were *create*, *delete* and *update* operations, was able to capture these events in a structured way for exposure in the Prometheus format.

### Expected results
To gain detailed and low-level observability over OVN-SB events, materialized as Prometheus-formatted metrics and Grafana *dashboards* that allow tracking the *control plane* dynamics in real time.

With the *exporter* already developed, only the integration of these metrics with Prometheus/Grafana was missing. However, we did not move forward with finalizing the development, since CloudFerro itself published an *open source* version of this *exporter* — the **ovn-event-exporter**. Even so, it is worth highlighting the importance of this work for understanding, at a *low level*, how OVN-SB communicates the actions that pass through it; this *exporter* can still be used or adapted to a different reality in the future.
