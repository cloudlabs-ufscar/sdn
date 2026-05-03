#!/bin/bash
set -e

AZ1_IP="172.18.3.181"
AZ2_IP="172.18.17.9"
LAB_DIR="/opt/ovn-lab"
AZ2_DIR="$LAB_DIR/az2"
OVN_SRC="/tmp/ovn-24.03.6"

echo "=== CLEANUP ==="

# Kill ovn-ic via pidfile (default rundir)
if [ -f /usr/local/var/run/ovn/ovn-ic.pid ]; then
    sudo kill "$(cat /usr/local/var/run/ovn/ovn-ic.pid)" 2>/dev/null || true
fi

# Kill processes via pidfiles in lab dir
for pidfile in "$AZ2_DIR"/*.pid; do
    [ -f "$pidfile" ] && sudo kill "$(cat "$pidfile")" 2>/dev/null || true
done

# Kill by process name
sudo pkill -f "ovn-northd" 2>/dev/null || true
sudo pkill -f "ovn-controller" 2>/dev/null || true
sudo pkill -f "ovsdb-server.*ovnnb" 2>/dev/null || true
sudo pkill -f "ovsdb-server.*ovnsb" 2>/dev/null || true
sudo pkill -f "ovn-ic" 2>/dev/null || true

sleep 2

# Free ports used by AZ2 local databases
for port in 6641 6642; do
    sudo fuser -k "${port}/tcp" 2>/dev/null || true
done

sleep 1

# Remove OVS ports FIRST (before namespaces/veths are destroyed)
for port in veth-vm1-az2 veth-vm2-az2; do
    sudo ovs-vsctl --if-exists del-port "$port"
done

# Delete network namespaces (also destroys veth pairs)
for ns in vm1-az2 vm2-az2; do
    sudo ip netns del "$ns" 2>/dev/null || true
done

# Delete any leftover veth pairs
for veth in veth-vm1-az2 veth-vm2-az2; do
    sudo ip link del "$veth" 2>/dev/null || true
done

# Remove lab directory
sudo rm -rf "$AZ2_DIR"

echo "=== INSTALL DEPENDENCIES ==="
sudo apt-get update -qq
sudo apt-get install -y \
    openvswitch-switch \
    ovn-central \
    ovn-host \
    git automake autoconf libtool make gcc \
    libssl-dev libcap-ng-dev python3-dev python3-pip \
    python3-sphinx libunbound-dev \
    --no-install-recommends

sudo systemctl stop ovn-central ovn-host ovn-ovsdb-server-nb ovn-ovsdb-server-sb 2>/dev/null || true
sudo systemctl disable ovn-central ovn-host ovn-ovsdb-server-nb ovn-ovsdb-server-sb 2>/dev/null || true
sudo systemctl enable openvswitch-switch
sudo systemctl start openvswitch-switch
sudo mkdir -p /var/run/openvswitch

echo "=== COMPILE OVN + OVN-IC FROM SOURCE ==="
sudo rm -rf "$OVN_SRC"
sudo mkdir -p "$OVN_SRC"
sudo chown "$USER":"$USER" "$OVN_SRC"

cd "$OVN_SRC"

# Clone and build OVS v3.3.0 — compatible with OVN v24.03.x
# (OVS main renamed obs_domain_id → obs_domain_imm, breaking OVN 24.03 build)
git clone --depth=1 --branch v3.3.0 https://github.com/openvswitch/ovs.git
cd ovs
./boot.sh
./configure
make -j"$(nproc)"
cd ..

# Clone and build OVN (includes ovn-ic)
git clone --depth=1 --branch v24.03.6 https://github.com/ovn-org/ovn.git
cd ovn
./boot.sh
./configure --with-ovs-source=../ovs --with-ovs-build=../ovs
make -j"$(nproc)"

OVN_IC_BIN="$(find "$OVN_SRC" -name ovn-ic -type f -executable | head -1)"
OVN_IC_NBCTL="$(find "$OVN_SRC" -name ovn-ic-nbctl -type f -executable | head -1)"
OVN_IC_SBCTL="$(find "$OVN_SRC" -name ovn-ic-sbctl -type f -executable | head -1)"

echo "ovn-ic binary: $OVN_IC_BIN"

if [ -z "$OVN_IC_BIN" ]; then
    echo "ERROR: could not locate ovn-ic binary"
    exit 1
fi

sudo cp "$OVN_IC_BIN" /usr/local/bin/ovn-ic
sudo cp "$OVN_IC_NBCTL" /usr/local/bin/ovn-ic-nbctl
sudo cp "$OVN_IC_SBCTL" /usr/local/bin/ovn-ic-sbctl

echo "=== CREATE LAB DIRECTORY ==="
sudo mkdir -p "$AZ2_DIR"
sudo chown -R "$USER":"$USER" "$LAB_DIR"

echo "=== START AZ2 NB DATABASE (port 6641) ==="
ovsdb-tool create "$AZ2_DIR/ovnnb.db" /usr/share/ovn/ovn-nb.ovsschema
ovsdb-server "$AZ2_DIR/ovnnb.db" \
    --remote="ptcp:6641:127.0.0.1" \
    --unixctl="$AZ2_DIR/ovnnb.ctl" \
    --pidfile="$AZ2_DIR/ovnnb.pid" \
    --log-file="$AZ2_DIR/ovnnb.log" \
    --detach

echo "=== START AZ2 SB DATABASE (port 6642) ==="
ovsdb-tool create "$AZ2_DIR/ovnsb.db" /usr/share/ovn/ovn-sb.ovsschema
ovsdb-server "$AZ2_DIR/ovnsb.db" \
    --remote="ptcp:6642:127.0.0.1" \
    --unixctl="$AZ2_DIR/ovnsb.ctl" \
    --pidfile="$AZ2_DIR/ovnsb.pid" \
    --log-file="$AZ2_DIR/ovnsb.log" \
    --detach

sleep 2

echo "=== START OVN-NORTHD ==="
sudo mkdir -p /var/run/ovn
ovn-northd \
    --ovnnb-db="tcp:127.0.0.1:6641" \
    --ovnsb-db="tcp:127.0.0.1:6642" \
    --unixctl="$AZ2_DIR/ovn-northd.ctl" \
    --pidfile="$AZ2_DIR/ovn-northd.pid" \
    --log-file="$AZ2_DIR/ovn-northd.log" \
    --detach

sleep 1

echo "=== CONFIGURE CHASSIS (AZ2) ==="
sudo ovs-vsctl set open_vswitch . \
    external_ids:system-id="az2-chassis" \
    external_ids:ovn-remote="tcp:127.0.0.1:6642" \
    external_ids:ovn-encap-type="geneve" \
    external_ids:ovn-encap-ip="${AZ2_IP}" \
    external_ids:ovn-is-interconn="true"

echo "=== START OVN-CONTROLLER ==="
sudo ovn-controller \
    --pidfile="$AZ2_DIR/ovn-controller.pid" \
    --log-file="$AZ2_DIR/ovn-controller.log" \
    --detach

sleep 2

echo "=== SET AZ NAME + IC ROUTE OPTIONS IN NB_GLOBAL ==="
# ovn-ic derives the availability zone name from NB_Global.name.
# In OVN 24.03 the ic-route-adv/ic-route-learn options live on NB_Global
# (not on Logical_Router) — setting them on the LR has no effect.
ovn-nbctl --db=tcp:127.0.0.1:6641 set NB_Global . \
    name=az2 \
    options:ic-route-adv=true \
    options:ic-route-learn=true

echo "=== BUILD OVN TOPOLOGY (AZ2) ==="
NB="ovn-nbctl --db=tcp:127.0.0.1:6641"

# AZ2 logical switch
$NB ls-add ls-az2
$NB lsp-add ls-az2 lsp-vm1-az2
$NB lsp-set-addresses lsp-vm1-az2 "00:00:00:02:00:01 10.0.2.10"
$NB lsp-set-port-security lsp-vm1-az2 "00:00:00:02:00:01 10.0.2.10"
$NB lsp-add ls-az2 lsp-vm2-az2
$NB lsp-set-addresses lsp-vm2-az2 "00:00:00:02:00:02 10.0.2.20"
$NB lsp-set-port-security lsp-vm2-az2 "00:00:00:02:00:02 10.0.2.20"

# AZ2 logical router
$NB lr-add lr-az2
$NB lrp-add lr-az2 lrp-az2-ls 00:00:00:02:ff:01 10.0.2.1/24
$NB lsp-add ls-az2 lsp-az2-router
$NB lsp-set-type lsp-az2-router router
$NB lsp-set-addresses lsp-az2-router router
$NB lsp-set-options lsp-az2-router router-port=lrp-az2-ls

# Router port for transit switch — must exist before ovn-ic starts
# so ovn-ic finds it when it auto-creates lsp-ts-az2
$NB lrp-add lr-az2 lrp-az2-ts 00:00:00:02:ff:02 169.254.100.2/24

# Pin the TS router port to a gateway chassis. Without this the IC-SB
# Port_Binding gateway field stays empty and remote chassis can't
# encapsulate inter-AZ traffic (data-plane breaks even with routes).
$NB lrp-set-gateway-chassis lrp-az2-ts az2-chassis 1

echo "=== START OVN-IC (connecting to AZ1 IC databases) ==="
# ts already exists in IC-NB (created by AZ1); lrp-az2-ts now exists in local NB
# ovn-ic will propagate ts and auto-create lsp-ts-az2 finding lrp-az2-ts
sudo mkdir -p /usr/local/var/run/ovn
sudo ovn-ic \
    --ic-nb-db="tcp:${AZ1_IP}:6645" \
    --ic-sb-db="tcp:${AZ1_IP}:6646" \
    --ovnnb-db="tcp:127.0.0.1:6641" \
    --ovnsb-db="tcp:127.0.0.1:6642" \
    --log-file="$AZ2_DIR/ovn-ic.log" \
    --pidfile="$AZ2_DIR/ovn-ic.pid" \
    --detach

sleep 5

# Wait for ovn-ic to propagate ts into local OVN NB
echo "Aguardando ovn-ic propagar transit switch 'ts' para o NB da AZ2..."
for i in $(seq 1 30); do
    if ovn-nbctl --db=tcp:127.0.0.1:6641 ls-list 2>/dev/null | grep -qw "ts"; then
        echo "transit switch 'ts' disponível no NB da AZ2"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "ERROR: transit switch 'ts' não apareceu no NB da AZ2 em 60s"
        echo "Verifique: ovn-ic log em $AZ2_DIR/ovn-ic.log"
        exit 1
    fi
    sleep 2
done

# Connect lr-az2 to transit switch
$NB lsp-add ts lsp-ts-az2
$NB lsp-set-type lsp-ts-az2 router
$NB lsp-set-addresses lsp-ts-az2 router
$NB lsp-set-options lsp-ts-az2 router-port=lrp-az2-ts

# Restart ovn-ic so it reads the complete topology from scratch (avoids
# the race condition where it processed lsp-ts-az2 before lrp-az2-ts was
# in its OVSDB cache)
echo "=== RESTART OVN-IC (topologia completa) ==="
sudo kill "$(cat "$AZ2_DIR/ovn-ic.pid")" 2>/dev/null || true
sleep 2
sudo ovn-ic \
    --ic-nb-db="tcp:${AZ1_IP}:6645" \
    --ic-sb-db="tcp:${AZ1_IP}:6646" \
    --ovnnb-db="tcp:127.0.0.1:6641" \
    --ovnsb-db="tcp:127.0.0.1:6642" \
    --log-file="$AZ2_DIR/ovn-ic.log" \
    --pidfile="$AZ2_DIR/ovn-ic.pid" \
    --detach
sleep 5

echo "=== CREATE VM NAMESPACES (AZ2) ==="

# vm1-az2: 10.0.2.10
sudo ip netns add vm1-az2
sudo ip link add veth-vm1-az2 type veth peer name veth-vm1-az2-ns
sudo ip link set veth-vm1-az2-ns netns vm1-az2
sudo ip netns exec vm1-az2 ip link set veth-vm1-az2-ns address 00:00:00:02:00:01
sudo ip netns exec vm1-az2 ip addr add 10.0.2.10/24 dev veth-vm1-az2-ns
sudo ip netns exec vm1-az2 ip link set veth-vm1-az2-ns up
sudo ip netns exec vm1-az2 ip route add default via 10.0.2.1
sudo ip link set veth-vm1-az2 up
sudo ovs-vsctl add-port br-int veth-vm1-az2 \
    -- set interface veth-vm1-az2 external_ids:iface-id=lsp-vm1-az2

# vm2-az2: 10.0.2.20
sudo ip netns add vm2-az2
sudo ip link add veth-vm2-az2 type veth peer name veth-vm2-az2-ns
sudo ip link set veth-vm2-az2-ns netns vm2-az2
sudo ip netns exec vm2-az2 ip link set veth-vm2-az2-ns address 00:00:00:02:00:02
sudo ip netns exec vm2-az2 ip addr add 10.0.2.20/24 dev veth-vm2-az2-ns
sudo ip netns exec vm2-az2 ip link set veth-vm2-az2-ns up
sudo ip netns exec vm2-az2 ip route add default via 10.0.2.1
sudo ip link set veth-vm2-az2 up
sudo ovs-vsctl add-port br-int veth-vm2-az2 \
    -- set interface veth-vm2-az2 external_ids:iface-id=lsp-vm2-az2

echo "=== CONFIGURE UFW (allow AZ1 GENEVE) ==="
sudo ufw allow from "${AZ1_IP}" to any port 6081 proto udp comment "GENEVE from AZ1" 2>/dev/null || true

echo ""
echo "=== AZ2 SETUP COMPLETE — aguardando registro do gateway (15s) ==="
sleep 15

echo ""
echo "IC-SB gateways (esperado: az1-chassis e az2-chassis):"
ovn-ic-sbctl --db="tcp:${AZ1_IP}:6646" list Gateway 2>/dev/null || true
echo ""
echo "OVS show (esperado: porta geneve para AZ1):"
sudo ovs-vsctl show
