#!/usr/bin/env python3
"""
Read-only infrastructure probe agent for the advanced-ovn-ic lab.

Runs on each AZ *host*, because the things it reports — transit switches, learned
routes, GENEVE tunnels, the interconnect RAFT cluster — only exist in the OVN
databases and OVS on the host. The workloads cannot see any of it.

The dashboard's "Testar" buttons call this through the Java backend, so a press
runs the checks live rather than reading something cached.

Security posture (this is a lab, but the shape matters):
  * READ ONLY. Every command is a *-list / show / status. There is no endpoint
    that changes state, and no user input reaches a shell — the command for each
    check is a fixed list, chosen by a name from a whitelist.
  * Bound to the AZ's private (underlay) address, never to a tenant network.
  * Reachable only from the management plane: workloads route to the underlay via
    their mgmt NIC, and ovn_chassis adds an iptables rule allowing only the mgmt
    plane's SNAT address plus the peer AZ's private address. See the comment on
    that rule for the one case where the distinction is coarse.
"""
import json
import re
import subprocess
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

import os

AZ = os.environ.get("AZ_NAME", "unknown")
BIND = os.environ.get("PROBE_BIND", "127.0.0.1")
PORT = int(os.environ.get("PROBE_PORT", "9101"))
NB = os.environ.get("NB_DB", "tcp:127.0.0.1:6641")
IC_SB = os.environ.get("IC_SB_DB", "")
LAB_DIR = os.environ.get("LAB_DIR", "/opt/ovn-lab")
PEER_IP = os.environ.get("PEER_PRIVATE_IP", "")
CHASSIS = os.environ.get("CHASSIS_NAME", "")
PEER_CHASSIS = os.environ.get("PEER_CHASSIS_NAME", "")
TRANSIT = [t for t in os.environ.get("TRANSIT_SWITCHES", "").split(",") if t]
CLUSTER_SIZE = int(os.environ.get("IC_CLUSTER_SIZE", "3"))
# Per plane: which router to read, which peer subnet it must have learned, and
# which prefix would prove the planes leaked. Read from a file rather than an env
# var: systemd mangles the embedded quotes of inline JSON.
PLANES_FILE = os.environ.get("PLANES_FILE", "")
try:
    with open(PLANES_FILE) as fh:
        PLANES = json.load(fh)
except Exception:
    PLANES = []

TIMEOUT = 8
# A RAFT peer quiet for longer than this is treated as gone. The election timer
# is 1s, so this is many heartbeats' worth of silence.
PEER_SILENCE_MS = 5000


def run(cmd):
    """Run a fixed command list. Returns (rc, output)."""
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=TIMEOUT)
        return p.returncode, (p.stdout + p.stderr)
    except subprocess.TimeoutExpired:
        return 124, "timed out"
    except Exception as exc:  # noqa: BLE001 - report, never crash the agent
        return 1, str(exc)


def check_transit_switches():
    rc, out = run(["ovn-nbctl", f"--db={NB}", "ls-list"])
    missing = [t for t in TRANSIT if t not in out]
    return {
        "item": "transit switches propagated",
        "value": ", ".join(TRANSIT) if not missing else f"missing: {', '.join(missing)}",
        "status": "ok" if rc == 0 and not missing else "error",
    }


def check_gateways():
    if not IC_SB:
        return {"item": "interconnect gateways", "value": "no IC-SB configured", "status": "warn"}
    rc, out = run(["ovn-ic-sbctl", f"--db={IC_SB}", "show"])
    want = [c for c in (CHASSIS, PEER_CHASSIS) if c]
    missing = [c for c in want if c not in out]
    return {
        "item": "interconnect gateways registered",
        "value": ", ".join(want) if not missing else f"missing: {', '.join(missing)}",
        "status": "ok" if rc == 0 and not missing else "error",
    }


def check_raft():
    """Report LIVE cluster health, not just the configured membership.

    `cluster/status` lists every server in the RAFT configuration whether or not
    it is currently reachable, so counting those lines would call a cluster with
    a dead member healthy. The liveness signal is per-peer: each remote server
    line carries "last msg N ms ago", which stops advancing when that member
    goes away. The election timer here is 1s, so anything past a few seconds of
    silence is genuinely gone rather than merely between heartbeats.
    """
    rc, out = run(["ovs-appctl", "-t", f"{LAB_DIR}/ic-nb.ctl",
                   "cluster/status", "OVN_IC_Northbound"])
    if rc != 0:
        return {"item": "IC database cluster (RAFT)",
                "value": "cluster unreachable", "status": "error"}

    role = next((l.split(":", 1)[1].strip() for l in out.splitlines()
                 if l.startswith("Role:")), "unknown")

    leader = next((l.split(":", 1)[1].strip() for l in out.splitlines()
                   if l.startswith("Leader:")), "unknown")

    # Only the LEADER exchanges heartbeats with every peer. On a follower the
    # "last msg" of the other followers never advances, so judging them from here
    # would report a permanently degraded cluster that is in fact healthy. A
    # follower can only speak for its own link to the leader.
    if role != "leader":
        healthy = leader not in ("unknown", "")
        return {
            "item": "IC database cluster (RAFT)",
            "value": (f"follower of {leader}" if healthy else "NO LEADER — quorum lost")
                     + " · member liveness is only observable on the leader",
            "status": "ok" if healthy else "error",
        }

    configured, alive, stale = 0, 0, []
    in_servers = False
    for line in out.splitlines():
        if line.startswith("Servers:"):
            in_servers = True
            continue
        if not in_servers or not line.strip():
            continue
        m = re.match(r"\s+(\w+)\s+\(", line)
        if not m:
            continue
        configured += 1
        sid = m.group(1)
        if "(self)" in line:
            alive += 1                      # we are answering, by definition
            continue
        last = re.search(r"last msg (\d+) ms ago", line)
        if last and int(last.group(1)) < PEER_SILENCE_MS:
            alive += 1
        else:
            stale.append(sid)

    if configured == 0:
        return {"item": "IC database cluster (RAFT)",
                "value": "could not parse cluster status", "status": "error"}

    detail = f"{alive}/{configured} members alive · {role}"
    if stale:
        detail += f" · silent: {', '.join(stale)}"

    if alive == configured:
        status = "ok"
    elif alive > configured // 2:
        # Still a majority, so writes keep committing — degraded, not broken.
        status = "warn"
        detail += " · QUORUM HELD (degraded)"
    else:
        status = "error"
        detail += " · QUORUM LOST"
    return {"item": "IC database cluster (RAFT)", "value": detail, "status": status}


def check_geneve():
    rc, out = run(["ovs-vsctl", "show"])
    ok = rc == 0 and PEER_IP and PEER_IP in out
    return {
        "item": "GENEVE tunnel to peer",
        "value": f"{PEER_IP} (UDP 6081)" if ok else f"no tunnel to {PEER_IP}",
        "status": "ok" if ok else "error",
    }


def check_planes():
    """Each plane's router must have learned its peer's subnet — and nothing from
    the other plane. A cross-plane route here means the transit switches leaked."""
    out_rows = []
    for pl in PLANES:
        rc, out = run(["ovn-nbctl", f"--db={NB}", "lr-route-list", pl["lr"]])
        learned = pl["peer_cidr"] in out
        foreign = pl["other_prefix"] in out
        if rc != 0:
            status, value = "error", "router unreachable"
        elif not learned:
            status, value = "error", f"has not learned {pl['peer_cidr']}"
        elif foreign:
            status, value = "error", f"LEAK: sees {pl['other_prefix']}x routes"
        else:
            status, value = "ok", f"learned {pl['peer_cidr']} via {pl['ts']}"
        out_rows.append({"item": f"{pl['name']} plane routes", "value": value, "status": status})
    return out_rows


CHECKS = {
    "transit": check_transit_switches,
    "gateways": check_gateways,
    "raft": check_raft,
    "geneve": check_geneve,
}


def run_all():
    rows = []
    for name, fn in CHECKS.items():
        try:
            r = fn()
        except Exception as exc:  # noqa: BLE001
            r = {"item": name, "value": str(exc), "status": "error"}
        r["az"] = AZ
        r["category"] = "control plane"
        rows.append(r)
    for r in check_planes():
        r["az"] = AZ
        r["category"] = "control plane"
        rows.append(r)
    return rows


# Map each check to a stable metric name. Booleans, so Grafana can show them as
# UP/DOWN and alert on them; the human-readable detail stays in /probe.
METRIC_OF = {
    "transit switches propagated": "ovn_transit_switches_ok",
    "interconnect gateways registered": "ovn_ic_gateways_ok",
    "GENEVE tunnel to peer": "ovn_geneve_tunnel_up",
}


def metrics():
    """Prometheus exposition of the same checks /probe returns.

    Running the checks costs a handful of ovn-nbctl calls, so at a 15s scrape
    interval this is cheap; the timing of each check is exported too, which is
    itself a useful signal (a slow ovn-nbctl means a struggling database).
    """
    lines = [
        "# HELP ovn_probe_duration_seconds Time to run one control-plane check",
        "# TYPE ovn_probe_duration_seconds gauge",
    ]
    body = []
    raft_alive = raft_configured = 0

    for fn_name, fn in list(CHECKS.items()) + [("planes", None)]:
        t0 = time.monotonic()
        rows = check_planes() if fn is None else [fn()]
        dur = time.monotonic() - t0
        lines.append(f'ovn_probe_duration_seconds{{az="{AZ}",check="{fn_name}"}} {dur:.4f}')

        for r in rows:
            ok = 1 if r["status"] == "ok" else 0
            name = METRIC_OF.get(r["item"])
            if name:
                body.append(f'{name}{{az="{AZ}"}} {ok}')
            elif r["item"].endswith("plane routes"):
                plane = r["item"].split()[0]
                body.append(f'ovn_plane_routes_ok{{az="{AZ}",plane="{plane}"}} {ok}')
            elif "RAFT" in r["item"]:
                m = re.search(r"(\d+)/(\d+) members alive", r["value"])
                if m:
                    raft_alive, raft_configured = int(m.group(1)), int(m.group(2))
                else:
                    # A follower cannot count peers; report configured size and
                    # treat "has a leader" as all-alive rather than inventing a
                    # number that would look like a fault.
                    raft_configured = CLUSTER_SIZE
                    raft_alive = CLUSTER_SIZE if ok else 0
                body.append(f'ovn_ic_raft_healthy{{az="{AZ}"}} {ok}')

    body.append(f'ovn_ic_raft_members_alive{{az="{AZ}"}} {raft_alive}')
    body.append(f'ovn_ic_raft_members_configured{{az="{AZ}"}} {raft_configured}')

    for metric, help_ in [
        ("ovn_transit_switches_ok", "Both transit switches propagated into this AZ"),
        ("ovn_ic_gateways_ok", "Both AZ gateways registered in IC-SB"),
        ("ovn_geneve_tunnel_up", "GENEVE tunnel to the peer chassis is present"),
        ("ovn_plane_routes_ok", "Plane learned its peer subnet and no foreign route"),
        ("ovn_ic_raft_healthy", "IC database cluster is healthy from this member"),
        ("ovn_ic_raft_members_alive", "Live members of the IC database cluster"),
        ("ovn_ic_raft_members_configured", "Configured members of the IC database cluster"),
    ]:
        lines.append(f"# HELP {metric} {help_}")
        lines.append(f"# TYPE {metric} gauge")
    return "\n".join(lines + body) + "\n"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802 - required by BaseHTTPRequestHandler
        path = self.path.rstrip("/")
        if path not in ("/probe", "/health", "/metrics"):
            self.send_error(404)
            return
        if path == "/metrics":
            body = metrics().encode()
            ctype = "text/plain; version=0.0.4"
        elif path == "/health":
            body = json.dumps({"status": "ok", "az": AZ}).encode()
            ctype = "application/json"
        else:
            body = json.dumps({"az": AZ, "checks": run_all()}).encode()
            ctype = "application/json"
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass  # journald already records the unit; per-request noise is not useful


if __name__ == "__main__":
    HTTPServer((BIND, PORT), Handler).serve_forever()
