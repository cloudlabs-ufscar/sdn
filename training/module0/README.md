# Module 0 - Introduction to Networking  
This module will serve to consolidate knowledge of networks and protocols, necessary for understanding SDN. It will be essential prior knowledge so that we can actually begin studying virtual networks.


## Programing Languages 
- **Python**: Is widely used for control-plane logic, scripting, and API interactions in open-source networking virtualization. Many networking frameworks leverage Python to streamline network provisioning, automation, and management in cloud environments. For example, OpenStack Neutron provides networking-as-a-service for cloud infrastructure, while Ansible automates network configuration and orchestration. 
- **Go/Golang**: Is widely used in control-plane logic, API interactions, and cloud-native networking due to its efficiency and concurrency model. Kubernetes, Cilium, and Incus leverage Go for scalable networking, while gRPC enables efficient microservice communication.
- **C/C++**: Is a fundamental programming language in open-source networking virtualization due to its low-level capabilities, efficiency, and direct hardware interaction.
- **Rust**: Is becoming increasingly important in virtual networking due to its memory safety, performance, and concurrency. It is used in modern networking projects like Linkerd, a service mesh that ensures fast, secure service-to-service communication in cloud-native applications. Aya, a Rust-based framework for building eBPF programs, provides safe and efficient networking functionality within the Linux kernel.
- **Bash**: The essential "glue" for system administration and executing quick tasks directly in the Linux terminal.

Mastering these languages ​​is fundamental for learning and acquiring knowledge in virtual networks.

---

## OSI Model
The Open Systems Interconnection (OSI) model is a conceptual framework that divides network communication functions into seven layers. The OSI data model provides a universal language for computer networks, so that various technologies can communicate using standard protocols or communication rules. 
Each technology in a specific layer must provide certain features and perform specific functions to be useful in the network. Technologies in the upper layers benefit from abstraction, as they can use lower-level technologies without needing to worry about the underlying implementation details.The layers of the Open Systems Interconnection (OSI) model encapsulate all types of network communication into software and hardware components.

<img width="512" height="512" alt="image" src="https://github.com/user-attachments/assets/76f8dd24-4f44-4b20-9c37-d4bad47f55f1" />

| Layer | Name | Function |
|---|---|---|
| 7 | Application | Direct interaction with software applications (e.g., your browser). |
| 6 | Presentation | Formats data (encryption, compression) so the application can read it. |
| 5 | Session | Coordinates communication between applications; starts and stops the "dialogue." |
| 4 | Transport | Breaks data into segments and ensures they are delivered reliably (flow control). |
| 3 | Network | Moves data between different networks; uses packets and IP addresses. |
| 2 | Data Link | Connects devices on the same network; uses frames and MAC addresses. |
| 1 | Physical | Transmits raw bits over cables, radio waves, or fiber optics. |

---

## Network Hardware 
Network hardware refers to the physical equipment needed for communication and interaction between devices on a computer network. These components are the building blocks of a network infrastructure and include a variety of hardware types, each serving a specific purpose.

### Core Networking Components

- **Switches**: The most common device for connecting multiple devices (computers, printers, servers) on a single LAN. They use MAC addresses to direct data specifically to the intended destination, improving efficiency.

- **Routers**: These act as the "traffic controllers" of the internet. They connect different networks together and determine the best path for data packets to travel from one network to another.

- **Modems**: Short for modulator-demodulator, these convert digital data from a computer into analog signals for transmission over telephone or cable lines (and vice versa).

- **Hubs**: A simpler, older version of a switch. Unlike a switch, a hub broadcasts data to all connected devices, which can lead to network congestion.

- **Bridges**: These connect two separate LANs or divide one large LAN into smaller sections to reduce traffic and improve performance.

---

## Protocols
A network protocol is a collection of rules that governs how data is transmitted, received, and interpreted between devices on a network, regardless of their internal structure or design.

### 1. Standard Network Protocols 

- **HTTP/HTTPS** (HyperText Transfer Protocol): The foundation of data exchange on the web. HTTPS adds a layer of security (SSL/TLS) to encrypt the data.

- **FTP** (File Transfer Protocol): Specifically designed for moving files between a client and a server.

- **TCP** (Transmission Control Protocol): A "connection-oriented" protocol that ensures data is delivered reliably and in the correct order.

- **UDP** (User Datagram Protocol): A "connectionless" protocol that prioritizes speed over reliability (common in gaming and streaming).

- **SMTP/POP3/IMAP**: The trio used for email—SMTP for sending, and POP3/IMAP for receiving.

### 2. Specific Protocols
- **DNS** (Domain Name System): It translates human-readable domain names (like google.com) into machine-readable IP addresses ($142.250.190.46$).
- **DHCP** (Dynamic Host Configuration Protocol): Automates the process of assigning IP addresses. Instead of a network admin manually typing an IP into every laptop, DHCP "leases" an available IP address to a device the moment it connects to the network.
- **ARP** (Address Resolution Protocol): The bridge between the Network Layer (Layer 3) and the Data Link Layer (Layer 2). It maps a known IP address to a physical MAC address so data can find the correct hardware on a local network.
- **NAT** (Network Address Translation): Used by your router to save IP addresses. It allows an entire private network (like home Wi-Fi) to browse the internet using a single Public IP address. It acts as a middleman, remapping private IPs to the public one and back again.

---

## Virtual Networking 
Virtual networking is a technology that enables the remote control and management of computers or servers over the Internet. It allows communication between two or more virtual machines in a way that mimics traditional networking. However, instead of relying on physical connections, virtual networking uses software and wireless technologies to interconnect devices, servers, and virtual machines within a virtual environment.

<img width="512" height="512" alt="image" src="https://github.com/user-attachments/assets/2c74a26a-7a92-47e7-9503-1c32956c7ac5" />

### Core Components
To function, virtual networking replicates traditional hardware using software:

- **VM** (Virtual Machine):  A software-based, emulated computer that runs its own operating system and applications as if it were a physical machine, but exists within a host computer.

- **vNIC** (Virtual Network Interface Card): A software-based version of a physical network card that allows a Virtual Machine (VM) to communicate with other devices.

- **Virtual Switch** (vSwitch): A software program that allows one VM to communicate with another on the same host, acting just like a physical Layer 2 switch.

- **Virtual Router**: Manages traffic between different virtual networks or connects them to the physical internet.

### Common Types of Virtual Networking

- **VLAN** (Virtual Local Area Network): Divides a physical network into smaller, isolated logical segments. Even if devices are plugged into the same switch, a VLAN ensures they cannot see each other’s traffic unless authorized.

- **VPN** (Virtual Private Network): Creates a secure, encrypted "tunnel" over a public network (the internet). It allows remote users to appear as if they are directly connected to a private office network.

- **VXLAN** (Virtual Extensible LAN): An encapsulation protocol used in large data centers. It allows Layer 2 segments to "stretch" across Layer 3 network boundaries, supporting millions of isolated networks.

### Linux

It provides the actual "parts" used to build a network.

- Namespaces: The roadmap emphasizes these for Isolation. Without namespaces, you couldn't have multiple customers on one server without their data mixing.

- VETH & Bridges: These are the "Physical Layer" in software. The roadmap teaches these so you understand how a packet physically moves from a Container's virtual card to the server's real hardware.

- Netfilter (iptables/nftables): It is where ACLs (Access Control Layers) are enforced to keep the virtual network safe.

### Orchestration Plataforms

- **Kubernetes (K8s)**
  
The Role: It manages Application Containers.

The Network: It uses CNI (Container Network Interface). The roadmap places this here because K8s needs a "flat" network where every container can talk to every other container, usually managed by plugins like Calico or Cilium.

- **OpenStack**

The Role: It manages Virtual Machines (VMs) for giant clouds.

The Network: It uses Neutron. This is the most complex part of the roadmap because Neutron creates entire "Virtual Data Centers" with routers, firewalls, and load balancers all made of Linux code.

- **Incus (LXD)**

The Role: It manages System Containers.

The Network: It is simpler than OpenStack. It uses Linux Bridges and OVN (Open Virtual Network) to provide a fast, easy-to-use virtual network for developers.

### Physical Networking x Virtual Networking
| Feature | Physical | Virtual |
|---|---|---|
| Hardware | Physical Switches, Routers, Cables | Software-defined (vSwitch, vRouter) |
| Flexibility | Rigid; requires manual wiring | Highly flexible; automated via software |
| Cost | High (CapEx for hardware) | Lower (OpEx for software/resources) |
| Isolation | Physical separation of wires | Logical separation (Namespaces/VLANs) |

--- 

## Pratical Laboratory
Let's try to implement this network topology:

<img width="512" height="512" alt="image" src="https://github.com/user-attachments/assets/74229c07-a726-4136-ac79-302b207111ab" />


### 1. Core Components Installation

**KVM**
```bash
# Arch
pacman -S qemu

# Debian
apt install qemu-system
```

**Open vSwitch**
```bash
# Arch
pacman -S openvswitch

# Debian
apt install openvswitch-switch openvswitch-common
```

**Start OVS server**
```bash
sudo /usr/share/openvswitch/scripts/ovs-ctl start
```

---

### 2. VM Setup & Open vSwitch Configuration

**Download Ubuntu cloud image**
```bash
wget https://cloud-images.ubuntu.com/releases/noble/release/ubuntu-24.04-server-cloudimg-amd64.img
```

**Create two overlay images from the base**
```bash
qemu-img create -f qcow2 -b ubuntu-24.04-server-cloudimg-amd64.img -F qcow2 e1000.qcow2
qemu-img create -f qcow2 -b ubuntu-24.04-server-cloudimg-amd64.img -F qcow2 virtio.qcow2
```

**Create and bring up TAP devices**
```bash
sudo ip tuntap add mode tap user $USER name tap0
sudo ip tuntap add mode tap user $USER name tap1
sudo ip link set tap0 up
sudo ip link set tap1 up
```

**Create OVS bridge and add TAP ports**
```bash
sudo ovs-vsctl add-br ovs-br
sudo ovs-vsctl add-port ovs-br tap0
sudo ovs-vsctl add-port ovs-br tap1
```

**Assign IP to bridge for host communication**
```bash
sudo ip addr add 192.168.100.1/24 dev ovs-br
sudo ip link set ovs-br up
```

---

### 3. Networking & NAT

**Enable IP forwarding**
```bash
sudo sysctl -w net.ipv4.ip_forward=1
```

**Set up NAT** *(replace `wlp2s0` with your internet-connected interface)*
```bash
sudo iptables -t nat -A POSTROUTING -s 192.168.100.0/24 -o wlp2s0 -j MASQUERADE
sudo iptables -A FORWARD -i ovs-br -o wlp2s0 -j ACCEPT
sudo iptables -A FORWARD -i wlp2s0 -o ovs-br -m state --state RELATED,ESTABLISHED -j ACCEPT
```

---

### 4. DHCP Server (dnsmasq)

**Install dnsmasq**
```bash
sudo apt install dnsmasq
```

**Edit configuration**
```bash
sudo vim /etc/dnsmasq.conf
```

**Enable the service**
```bash
sudo systemctl start dnsmasq.service
```

**Allow DHCP traffic** *(may not be necessary)*
```bash
sudo iptables -A INPUT -p udp --dport 67 -j ACCEPT
sudo iptables -A INPUT -p udp --dport 68 -j ACCEPT
```

---

### 5. Cloud-Init

**Install cloud-image-utils**
```bash
# Arch
sudo pacman -S cloud-image-utils

# Debian/Ubuntu
sudo apt install cloud-utils
```

**Generate cloud-init images**
```bash
cloud-localds --network-config=./config-data/network-config my-seed.img  ./config-data/user-data.yaml
cloud-localds --network-config=./config-data/network-config my-seed2.img ./config-data/user-data.yaml
```

---

### 6. Start Virtual Machines

**VM 1 — Legacy e1000 driver**
```bash
qemu-system-x86_64 \
  -name "Legacy-Net-Driver" \
  -cpu host \
  -enable-kvm \
  -drive if=virtio,format=qcow2,file=e1000.qcow2 \
  -drive if=virtio,format=raw,file=my-seed.img \
  -m 2048 -smp 2 \
  -netdev tap,id=mynet0,ifname=tap0,script=no,downscript=no \
  -device e1000,netdev=mynet0,mac=52:54:00:12:34:56 \
  -nographic -serial mon:stdio
```

**VM 2 — Virtio driver**
```bash
qemu-system-x86_64 \
  -name "Virtio-Net-Driver" \
  -cpu host \
  -enable-kvm \
  -drive if=virtio,format=qcow2,file=virtio.qcow2 \
  -drive if=virtio,format=raw,file=my-seed2.img \
  -m 2048 -smp 2 \
  -netdev tap,id=mynet1,ifname=tap1,script=no,downscript=no \
  -device virtio-net-pci,netdev=mynet1,mac=52:54:00:12:34:57 \
  -nographic -serial mon:stdio
```

> **Note:** If DHCP fails, you can configure the network manually inside the VM:
> ```bash
> sudo ip a add 192.168.100.3/24 dev ens3
> sudo ip r add default via 192.168.100.1 dev ens3
> sudo vim /etc/resolv.conf  # set nameserver 8.8.8.8
> ```

---

### 7. Linux Network Namespaces

```bash
# Create a network namespace
sudo ip netns add ns1

# Create a veth pair and move one end into the namespace
sudo ip link add veth-host type veth peer name veth-ns
sudo ip link set veth-ns netns ns1

# Bring up both ends
sudo ip netns exec ns1 ip link set veth-ns up
sudo ip link set veth-host up

# Add the host-side veth to the OVS bridge
sudo ovs-vsctl add-port ovs-br veth-host

# Enter the namespace
sudo ip netns exec ns1 bash

# Obtain an IP via DHCP (if dnsmasq is running)
dhclient -v
```

---

### 8. Benchmarking

```bash
# Install iperf3 on hosts and VMs
sudo apt update && sudo apt install iperf3

# On the host — start iperf3 server
iperf3 -s -B 192.168.100.1

# On VMs/namespaces — connect to the server
iperf3 -c 192.168.100.1
```

---

### 9. Cleanup

```bash
sudo ovs-vsctl del-br ovs-br
sudo ip link del tap0
sudo ip link del tap1
sudo ip netns del ns1
sudo ip link del veth-host
```
