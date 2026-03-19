# Software-Defined Network (SDN)
Software-defined networking (SDN) is an architectural approach that virtualizes networks, allowing them to be centrally managed through software rather than individual hardware devices. In this repository, we will focus more on the study and development of OVN (Open Virtual Network).

## Open Virtual Network (OVN)
Open Virtual Network (OVN) is an open-source, Apache 2 licensed software-defined networking (SDN) platform that provides virtual network abstraction, such as logical switches and routers, for virtual machines and containers. Built on top of **Open vSwitch (OVS)**, it separates the logical networking topology from the physical network infrastructure.

## How to install OVN

**1. Install OVN and Open vSwitch (OVS)**
   
 ```bash
 sudo apt update
```
 ```bash
  sudo apt install -y openvswitch-switch ovn-central ovn-host ovn-common
```
  
**2. Start and Enable Services**

  ```bash
  # Start OVS
  sudo systemctl start openvswitch-switch

 # Start OVN Northbound and Southbound DBs
  sudo systemctl start ovn-central

 # Start the OVN controller (the "agent" on the host
  sudo systemctl start ovn-host
```

**3. Useful Commands**

```sudo ovn-nbctl show```	View the Logical topology (Switches, Routers, Ports).

```sudo ovn-sbctl show```	View the Physical binding (Which port is on which host).

```sudo ovs-vsctl show```	View the Open vSwitch bridges (br-int) and ports.
