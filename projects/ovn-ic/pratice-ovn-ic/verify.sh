#!/bin/bash

AZ1_IP="172.18.3.181"

# Detect which AZ we're on
if ip netns list 2>/dev/null | grep -q "vm1-az1"; then
    AZ="az1"
else
    AZ="az2"
fi

echo "=== Running on: $AZ ==="
echo ""

echo "--- IC-NB show ---"
ovn-ic-nbctl --db="tcp:${AZ1_IP}:6645" show 2>/dev/null || echo "(unavailable)"
echo ""

echo "--- IC-SB Gateways ---"
ovn-ic-sbctl --db="tcp:${AZ1_IP}:6646" list Gateway 2>/dev/null || echo "(unavailable)"
echo ""

echo "--- IC-SB Routes ---"
ovn-ic-sbctl --db="tcp:${AZ1_IP}:6646" list Route 2>/dev/null || echo "(unavailable)"
echo ""

echo "--- OVN NB Logical Routers ---"
ovn-nbctl --db=tcp:127.0.0.1:6641 lr-list 2>/dev/null || echo "(unavailable)"
echo ""

echo "--- OVN NB Routes (lr-az1 / lr-az2) ---"
if [ "$AZ" = "az1" ]; then
    ovn-nbctl --db=tcp:127.0.0.1:6641 lr-route-list lr-az1 2>/dev/null || echo "(unavailable)"
else
    ovn-nbctl --db=tcp:127.0.0.1:6641 lr-route-list lr-az2 2>/dev/null || echo "(unavailable)"
fi
echo ""

echo "--- OVS GENEVE tunnels ---"
sudo ovs-vsctl show | grep -A3 geneve || echo "(none)"
echo ""

echo "--- Port bindings ---"
ovn-sbctl --db=tcp:127.0.0.1:6642 list Port_Binding 2>/dev/null | grep -E "^(logical_port|chassis|encap)" || echo "(unavailable)"
echo ""

echo "=== PING TESTS ==="

if [ "$AZ" = "az1" ]; then
    echo "--- vm1-az1 → vm2-az1 (intra-AZ) ---"
    sudo ip netns exec vm1-az1 ping -c3 -W2 10.0.1.20 || echo "FAIL"
    echo ""
    echo "--- vm1-az1 → vm1-az2 (inter-AZ) ---"
    sudo ip netns exec vm1-az1 ping -c3 -W2 10.0.2.10 || echo "FAIL"
    echo ""
    echo "--- vm1-az1 → vm2-az2 (inter-AZ) ---"
    sudo ip netns exec vm1-az1 ping -c3 -W2 10.0.2.20 || echo "FAIL"
else
    echo "--- vm1-az2 → vm2-az2 (intra-AZ) ---"
    sudo ip netns exec vm1-az2 ping -c3 -W2 10.0.2.20 || echo "FAIL"
    echo ""
    echo "--- vm1-az2 → vm1-az1 (inter-AZ) ---"
    sudo ip netns exec vm1-az2 ping -c3 -W2 10.0.1.10 || echo "FAIL"
    echo ""
    echo "--- vm1-az2 → vm2-az1 (inter-AZ) ---"
    sudo ip netns exec vm1-az2 ping -c3 -W2 10.0.1.20 || echo "FAIL"
fi
