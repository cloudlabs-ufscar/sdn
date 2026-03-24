#!/bin/bash

echo "This script automates the virtual lab for this module."

# --- Configuration Variables ---
BRIDGE_NAME="ovs-br"
GATEWAY_IP="192.168.100.1/24"
VM_NET_RANGE="192.168.100.0/24"
INTERNET_IF="wlp2s0" # CHANGE THIS to your actual interface (eth0, wlan0, etc)
IMAGE_URL="https://cloud-images.ubuntu.com/releases/noble/release/ubuntu-24.04-server-cloudimg-amd64.img"
BASE_IMG="ubuntu-24.04.img"

# Ensure script is run as root
if [[ $EUID -ne 0 ]]; then
   echo "This script must be run as root"
   exit 1
fi

echo "--- [1/6] Installing Dependencies ---"
apt update && apt install -y qemu-system-x86_64 openvswitch-switch cloud-utils dnsmasq wget iperf3

# Start OVS if not running
/usr/share/openvswitch/scripts/ovs-ctl start

echo "--- [2/6] Preparing Images ---"
if [ ! -f "$BASE_IMG" ]; then
    wget -O "$BASE_IMG" "$IMAGE_URL"
fi

qemu-img create -f qcow2 -b "$BASE_IMG" -F qcow2 e1000.qcow2 10G
qemu-img create -f qcow2 -b "$BASE_IMG" -F qcow2 virtio.qcow2 10G

echo "--- [3/6] Setting up OVS and Networking ---"
# Setup Bridge
ovs-vsctl --may-exist add-br $BRIDGE_NAME
ip addr add $GATEWAY_IP dev $BRIDGE_NAME 2>/dev/null
ip link set $BRIDGE_NAME up

# Setup TAPs
for i in 0 1; do
    ip tuntap add mode tap user $USER name tap$i 2>/dev/null
    ip link set tap$i up
    ovs-vsctl --may-exist add-port $BRIDGE_NAME tap$i
done

# Enable Forwarding and NAT
sysctl -w net.ipv4.ip_forward=1
iptables -t nat -A POSTROUTING -s $VM_NET_RANGE -o $INTERNET_IF -j MASQUERADE
iptables -A FORWARD -i $BRIDGE_NAME -o $INTERNET_IF -j ACCEPT
iptables -A FORWARD -i $INTERNET_IF -o $BRIDGE_NAME -m state --state RELATED,ESTABLISHED -j ACCEPT

echo "--- [4/6] Configuring DHCP (dnsmasq) ---"
# Create a temporary dnsmasq config
cat <<EOF > /tmp/dnsmasq.lab.conf
interface=$BRIDGE_NAME
dhcp-range=192.168.100.10,192.168.100.50,12h
dhcp-option=option:router,192.168.100.1
dhcp-option=option:dns-server,8.8.8.8
EOF
# Kill existing dnsmasq and start with our config
pkill dnsmasq
dnsmasq -C /tmp/dnsmasq.lab.conf

echo "--- [5/6] Creating Cloud-Init Data ---"
mkdir -p config-data
cat <<EOF > ./config-data/user-data.yaml
#cloud-config
password: password
chpasswd: { expire: False }
ssh_pwauth: True
EOF

cat <<EOF > ./config-data/network-config
version: 2
ethernets:
  ens3:
    dhcp4: true
EOF

cloud-localds my-seed.img ./config-data/user-data.yaml --network-config=./config-data/network-config
cloud-localds my-seed2.img ./config-data/user-data.yaml --network-config=./config-data/network-config

echo "--- [6/6] Launching Linux Namespace (ns1) ---"
ip netns add ns1
ip link add veth-host type veth peer name veth-ns
ip link set veth-ns netns ns1
ip netns exec ns1 ip link set veth-ns up
ip link set veth-host up
ovs-vsctl --may-exist add-port $BRIDGE_NAME veth-host

echo "-------------------------------------------------------"
echo "LAB READY!"
echo "1. To start VM1 (e1000): Run the QEMU command manually (e1000.qcow2)"
echo "2. To start VM2 (virtio): Run the QEMU command manually (virtio.qcow2)"
echo "3. To enter Namespace: sudo ip netns exec ns1 bash"
echo "4. To Cleanup: Run 'ovs-vsctl del-br $BRIDGE_NAME && ip netns del ns1'"
echo "-------------------------------------------------------"
