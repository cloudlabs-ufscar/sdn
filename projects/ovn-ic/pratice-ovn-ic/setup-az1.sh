#!/bin/bash
set -e

AZ1_IP="172.18.3.181"
AZ2_IP="172.18.17.9"
LAB_DIR="/opt/ovn-lab"
AZ1_DIR="$LAB_DIR/az1"
IC_DIR="$LAB_DIR/ic"
OVN_SRC="/tmp/ovn-24.03.6"

echo "=== CLEANUP ==="

# Kill ovn-ic (default rundir from source build)
if [ -f /usr/local/var/run/ovn/ovn-ic.pid ]; then
    sudo kill "$(cat /usr/local/var/run/ovn/ovn-ic.pid)" 2>/dev/null || true
fi

# Kill processes via lab pidfiles
for pidfile in "$AZ1_DIR"/*.pid "$IC_DIR"/*.pid; do
    [ -f "$pidfile" ] && sudo kill "$(cat "$pidfile")" 2>/dev/null || true
done

# Kill by process name
sudo pkill -f "ovn-northd"    2>/dev/null || true
sudo pkill -f "ovn-controller" 2>/dev/null || true
sudo pkill -f "ovsdb-server.*ovnnb" 2>/dev/null || true
sudo pkill -f "ovsdb-server.*ovnsb" 2>/dev/null || true
sudo pkill -f "ovsdb-server.*ic-nb" 2>/dev/null || true
sudo pkill -f "ovsdb-server.*ic-sb" 2>/dev/null || true
sudo pkill -f "ovn-ic" 2>/dev/null || true

sleep 2

# Free ports
for port in 6641 6642 6645 6646; do
    sudo fuser -k "${port}/tcp" 2>/dev/null || true
done

sleep 1

# Remove OVS ports FIRST (before namespaces/veths are destroyed)
for port in veth-vm1-az1 veth-vm2-az1; do
    sudo ovs-vsctl --if-exists del-port "$port"
done

# Delete network namespaces (also destroys veth pairs)
for ns in vm1-az1 vm2-az1; do
    sudo ip netns del "$ns" 2>/dev/null || true
done

# Delete any leftover veth pairs
for veth in veth-vm1-az1 veth-vm2-az1; do
    sudo ip link del "$veth" 2>/dev/null || true
done

# Remove lab directories
sudo rm -rf "$AZ1_DIR" "$IC_DIR"

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
IC_NB_SCHEMA="$(find "$OVN_SRC" -name ovn-ic-nb.ovsschema | head -1)"
IC_SB_SCHEMA="$(find "$OVN_SRC" -name ovn-ic-sb.ovsschema | head -1)"

echo "ovn-ic binary: $OVN_IC_BIN"
echo "IC-NB schema:  $IC_NB_SCHEMA"
echo "IC-SB schema:  $IC_SB_SCHEMA"

if [ -z "$OVN_IC_BIN" ] || [ -z "$IC_NB_SCHEMA" ] || [ -z "$IC_SB_SCHEMA" ]; then
    echo "ERROR: could not locate ovn-ic binary or schemas"
    exit 1
fi

sudo cp "$OVN_IC_BIN"    /usr/local/bin/ovn-ic
sudo cp "$OVN_IC_NBCTL"  /usr/local/bin/ovn-ic-nbctl
sudo cp "$OVN_IC_SBCTL"  /usr/local/bin/ovn-ic-sbctl

echo "=== CREATE LAB DIRECTORIES ==="
sudo mkdir -p "$AZ1_DIR" "$IC_DIR"
sudo chown -R "$USER":"$USER" "$LAB_DIR"

echo "=== START IC-NB DATABASE (port 6645) ==="
ovsdb-tool create "$IC_DIR/ic-nb.db" "$IC_NB_SCHEMA"
ovsdb-server "$IC_DIR/ic-nb.db" \
    --remote="ptcp:6645" \
    --unixctl="$IC_DIR/ic-nb.ctl" \
    --pidfile="$IC_DIR/ic-nb.pid" \
    --log-file="$IC_DIR/ic-nb.log" \
    --detach

echo "=== START IC-SB DATABASE (port 6646) ==="
ovsdb-tool create "$IC_DIR/ic-sb.db" "$IC_SB_SCHEMA"
ovsdb-server "$IC_DIR/ic-sb.db" \
    --remote="ptcp:6646" \
    --unixctl="$IC_DIR/ic-sb.ctl" \
    --pidfile="$IC_DIR/ic-sb.pid" \
    --log-file="$IC_DIR/ic-sb.log" \
    --detach

echo "=== START AZ1 NB DATABASE (port 6641) ==="
ovsdb-tool create "$AZ1_DIR/ovnnb.db" /usr/share/ovn/ovn-nb.ovsschema
ovsdb-server "$AZ1_DIR/ovnnb.db" \
    --remote="ptcp:6641:127.0.0.1" \
    --unixctl="$AZ1_DIR/ovnnb.ctl" \
    --pidfile="$AZ1_DIR/ovnnb.pid" \
    --log-file="$AZ1_DIR/ovnnb.log" \
    --detach

echo "=== START AZ1 SB DATABASE (port 6642) ==="
ovsdb-tool create "$AZ1_DIR/ovnsb.db" /usr/share/ovn/ovn-sb.ovsschema
ovsdb-server "$AZ1_DIR/ovnsb.db" \
    --remote="ptcp:6642:127.0.0.1" \
    --unixctl="$AZ1_DIR/ovnsb.ctl" \
    --pidfile="$AZ1_DIR/ovnsb.pid" \
    --log-file="$AZ1_DIR/ovnsb.log" \
    --detach

sleep 2

echo "=== START OVN-NORTHD ==="
sudo mkdir -p /var/run/ovn
ovn-northd \
    --ovnnb-db="tcp:127.0.0.1:6641" \
    --ovnsb-db="tcp:127.0.0.1:6642" \
    --unixctl="$AZ1_DIR/ovn-northd.ctl" \
    --pidfile="$AZ1_DIR/ovn-northd.pid" \
    --log-file="$AZ1_DIR/ovn-northd.log" \
    --detach

sleep 1

echo "=== CONFIGURE CHASSIS (AZ1) ==="
sudo ovs-vsctl set open_vswitch . \
    external_ids:system-id="az1-chassis" \
    external_ids:ovn-remote="tcp:127.0.0.1:6642" \
    external_ids:ovn-encap-type="geneve" \
    external_ids:ovn-encap-ip="${AZ1_IP}" \
    external_ids:ovn-is-interconn="true"

echo "=== START OVN-CONTROLLER ==="
sudo ovn-controller \
    --pidfile="$AZ1_DIR/ovn-controller.pid" \
    --log-file="$AZ1_DIR/ovn-controller.log" \
    --detach

sleep 2

echo "=== SET AZ NAME + IC ROUTE OPTIONS IN NB_GLOBAL ==="
# ovn-ic derives the availability zone name from NB_Global.name.
# In OVN 24.03 the ic-route-adv/ic-route-learn options live on NB_Global
# (not on Logical_Router) — setting them on the LR has no effect.
ovn-nbctl --db=tcp:127.0.0.1:6641 set NB_Global . \
    name=az1 \
    options:ic-route-adv=true \
    options:ic-route-learn=true

echo "=== START OVN-IC ==="
sudo mkdir -p /usr/local/var/run/ovn
sudo ovn-ic \
    --ic-nb-db="tcp:127.0.0.1:6645" \
    --ic-sb-db="tcp:127.0.0.1:6646" \
    --ovnnb-db="tcp:127.0.0.1:6641" \
    --ovnsb-db="tcp:127.0.0.1:6642" \
    --log-file="$AZ1_DIR/ovn-ic.log" \
    --pidfile="$AZ1_DIR/ovn-ic.pid" \
    --detach

sleep 5

echo "=== BUILD OVN TOPOLOGY (AZ1) ==="
NB="ovn-nbctl --db=tcp:127.0.0.1:6641"

# AZ1 logical switch
$NB ls-add ls-az1
$NB lsp-add ls-az1 lsp-vm1-az1
$NB lsp-set-addresses lsp-vm1-az1 "00:00:00:01:00:01 10.0.1.10"
$NB lsp-set-port-security lsp-vm1-az1 "00:00:00:01:00:01 10.0.1.10"
$NB lsp-add ls-az1 lsp-vm2-az1
$NB lsp-set-addresses lsp-vm2-az1 "00:00:00:01:00:02 10.0.1.20"
$NB lsp-set-port-security lsp-vm2-az1 "00:00:00:01:00:02 10.0.1.20"

# AZ1 logical router
$NB lr-add lr-az1
$NB lrp-add lr-az1 lrp-az1-ls 00:00:00:01:ff:01 10.0.1.1/24
$NB lsp-add ls-az1 lsp-az1-router
$NB lsp-set-type lsp-az1-router router
$NB lsp-set-addresses lsp-az1-router router
$NB lsp-set-options lsp-az1-router router-port=lrp-az1-ls

# Router port for transit switch — must exist before ts-add ts
# so ovn-ic can link lsp-ts-az1 when the port is created below
$NB lrp-add lr-az1 lrp-az1-ts 00:00:00:01:ff:02 169.254.100.1/24

# Pin the TS router port to a gateway chassis. Without this the IC-SB
# Port_Binding gateway field stays empty and remote chassis can't
# encapsulate inter-AZ traffic (data-plane breaks even with routes).
$NB lrp-set-gateway-chassis lrp-az1-ts az1-chassis 1

# Add transit switch to IC-NB — ovn-ic propagates ts to local NB
ovn-ic-nbctl --db="tcp:127.0.0.1:6645" ts-add ts

# Wait for ovn-ic to propagate ts into local OVN NB
echo "Aguardando ovn-ic propagar transit switch 'ts' para o NB da AZ1..."
for i in $(seq 1 30); do
    if ovn-nbctl --db=tcp:127.0.0.1:6641 ls-list 2>/dev/null | grep -qw "ts"; then
        echo "transit switch 'ts' disponível no NB da AZ1"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "ERROR: transit switch 'ts' não apareceu no NB da AZ1 em 60s"
        echo "Verifique: ovn-ic log em $AZ1_DIR/ovn-ic.log"
        exit 1
    fi
    sleep 2
done

# Connect lr-az1 to transit switch
$NB lsp-add ts lsp-ts-az1
$NB lsp-set-type lsp-ts-az1 router
$NB lsp-set-addresses lsp-ts-az1 router
$NB lsp-set-options lsp-ts-az1 router-port=lrp-az1-ts

# Restart ovn-ic so it reads the complete topology from scratch (avoids
# the race condition where it processed lsp-ts-az1 before lrp-az1-ts was
# in its OVSDB cache)
echo "=== RESTART OVN-IC (topologia completa) ==="
sudo kill "$(cat "$AZ1_DIR/ovn-ic.pid")" 2>/dev/null || true
sleep 2
sudo ovn-ic \
    --ic-nb-db="tcp:127.0.0.1:6645" \
    --ic-sb-db="tcp:127.0.0.1:6646" \
    --ovnnb-db="tcp:127.0.0.1:6641" \
    --ovnsb-db="tcp:127.0.0.1:6642" \
    --log-file="$AZ1_DIR/ovn-ic.log" \
    --pidfile="$AZ1_DIR/ovn-ic.pid" \
    --detach
sleep 5

echo "=== CREATE VM NAMESPACES (AZ1) ==="

# vm1-az1: 10.0.1.10
sudo ip netns add vm1-az1
sudo ip link add veth-vm1-az1 type veth peer name veth-vm1-az1-ns
sudo ip link set veth-vm1-az1-ns netns vm1-az1
sudo ip netns exec vm1-az1 ip link set veth-vm1-az1-ns address 00:00:00:01:00:01
sudo ip netns exec vm1-az1 ip addr add 10.0.1.10/24 dev veth-vm1-az1-ns
sudo ip netns exec vm1-az1 ip link set veth-vm1-az1-ns up
sudo ip netns exec vm1-az1 ip route add default via 10.0.1.1
sudo ip link set veth-vm1-az1 up
sudo ovs-vsctl add-port br-int veth-vm1-az1 \
    -- set interface veth-vm1-az1 external_ids:iface-id=lsp-vm1-az1

# vm2-az1: 10.0.1.20
sudo ip netns add vm2-az1
sudo ip link add veth-vm2-az1 type veth peer name veth-vm2-az1-ns
sudo ip link set veth-vm2-az1-ns netns vm2-az1
sudo ip netns exec vm2-az1 ip link set veth-vm2-az1-ns address 00:00:00:01:00:02
sudo ip netns exec vm2-az1 ip addr add 10.0.1.20/24 dev veth-vm2-az1-ns
sudo ip netns exec vm2-az1 ip link set veth-vm2-az1-ns up
sudo ip netns exec vm2-az1 ip route add default via 10.0.1.1
sudo ip link set veth-vm2-az1 up
sudo ovs-vsctl add-port br-int veth-vm2-az1 \
    -- set interface veth-vm2-az1 external_ids:iface-id=lsp-vm2-az1

echo "=== CONFIGURE UFW (allow AZ2) ==="
sudo ufw allow from "${AZ2_IP}" to any port 6645 proto tcp comment "IC-NB from AZ2" 2>/dev/null || true
sudo ufw allow from "${AZ2_IP}" to any port 6646 proto tcp comment "IC-SB from AZ2" 2>/dev/null || true
sudo ufw allow from "${AZ2_IP}" to any port 6081 proto udp comment "GENEVE from AZ2" 2>/dev/null || true

echo ""
echo "=== AZ1 SETUP COMPLETE — aguardando registro do gateway (15s) ==="
sleep 15

echo ""
echo "IC-NB show:"
ovn-ic-nbctl --db="tcp:127.0.0.1:6645" show || true
echo ""
echo "IC-SB gateways (esperado: az1-chassis):"
ovn-ic-sbctl --db="tcp:127.0.0.1:6646" list Gateway 2>/dev/null || true
echo ""
echo "OVS show:"
sudo ovs-vsctl show
