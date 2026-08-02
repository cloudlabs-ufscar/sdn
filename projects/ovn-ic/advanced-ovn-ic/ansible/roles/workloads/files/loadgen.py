#!/usr/bin/env python3
"""
Traffic generator for the advanced-ovn-ic lab.

It exists because a dashboard with no load shows nothing. Everything it does is
designed to put traffic on a path the architecture actually cares about:

  CLIENT plane, cross-AZ:
      -> the frontend VIP in AZ1
      -> nginx serves React and proxies /api/* to the service VIP
      -> OVN load balancer DNATs across ts-client (GENEVE) into AZ2
      -> the Java backend
      -> PostgreSQL on the management plane
    So one request exercises both load balancers, the client transit switch and
    the database.

  MANAGEMENT plane, cross-AZ:
      a periodic query straight from this container's mgmt NIC to the database
      in the other AZ, over ts-mgmt. The application path never touches ts-mgmt
      across AZs (the backend and the database are both in AZ2), so without this
      the management transit switch would carry no measurable load.

The request rate follows a slow sine rather than a constant, so the graphs have
shape: a flat line tells you nothing about whether the system responds to load.

Prometheus scrapes /metrics on the MANAGEMENT nic, like every other workload
here — instrumentation is an operations concern, not a tenant one.
"""
import math
import os
import random
import socket
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

TARGET       = os.environ.get("LOADGEN_TARGET", "")           # http://10.10.1.100
DB_HOST      = os.environ.get("DB_HOST", "")
DB_PORT      = int(os.environ.get("DB_PORT", "5432"))
BASE_RPS     = float(os.environ.get("LOADGEN_BASE_RPS", "6"))
AMPLITUDE    = float(os.environ.get("LOADGEN_AMPLITUDE", "0.7"))
PERIOD_S     = float(os.environ.get("LOADGEN_PERIOD_S", "600"))
DB_INTERVAL  = float(os.environ.get("LOADGEN_DB_INTERVAL_S", "5"))
BIND         = os.environ.get("LOADGEN_BIND", "127.0.0.1")
PORT         = int(os.environ.get("LOADGEN_METRICS_PORT", "9103"))
AZ           = os.environ.get("AZ_NAME", "unknown")

# Weighted mix, so the database sees reads AND writes instead of one flat query.
MIX = [
    ("/api/status", 5),
    ("/api/report", 3),
    ("/api/orders", 2),   # the write path
    ("/api/health", 2),
]
_POOL = [p for p, w in MIX for _ in range(w)]

BUCKETS = [1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500]

_lock = threading.Lock()
_req = {}          # endpoint -> count
_err = {}          # endpoint -> count
_buckets = {}      # endpoint -> [counts per bucket + Inf]
_sum = {}          # endpoint -> total ms
_db_ok = 0
_db_fail = 0
_db_ms = 0.0
_db_n = 0
_current_rps = 0.0


def _observe(endpoint, ms, failed):
    with _lock:
        _req[endpoint] = _req.get(endpoint, 0) + 1
        if failed:
            _err[endpoint] = _err.get(endpoint, 0) + 1
        b = _buckets.setdefault(endpoint, [0] * (len(BUCKETS) + 1))
        for i, edge in enumerate(BUCKETS):
            if ms <= edge:
                b[i] += 1
                break
        else:
            b[-1] += 1
        _sum[endpoint] = _sum.get(endpoint, 0.0) + ms


def _hit(endpoint):
    url = TARGET + endpoint
    t0 = time.perf_counter()
    failed = False
    try:
        with urllib.request.urlopen(url, timeout=15) as r:
            r.read()
            if r.status >= 400:
                failed = True
    except Exception:
        failed = True
    _observe(endpoint, (time.perf_counter() - t0) * 1000.0, failed)


def target_rps(now):
    """A slow sine around BASE_RPS. Never drops below ~10% so the app never
    goes fully idle (an idle system hides problems too)."""
    phase = math.sin(2 * math.pi * (now % PERIOD_S) / PERIOD_S)
    return max(BASE_RPS * 0.1, BASE_RPS * (1 + AMPLITUDE * phase))


def driver():
    """Paces requests to the current target rate, firing each in its own thread
    so a slow response does not throttle the offered load (which would hide the
    very latency we are trying to measure)."""
    global _current_rps
    while True:
        rps = target_rps(time.time())
        with _lock:
            _current_rps = rps
        threading.Thread(target=_hit, args=(random.choice(_POOL,),), daemon=True).start()
        time.sleep(1.0 / max(rps, 0.1))


def db_driver():
    """Cross-AZ traffic on the MANAGEMENT plane. A plain TCP connect to the
    database is enough to put bytes on ts-mgmt and to prove the path is up,
    without needing a Postgres client in this container."""
    global _db_ok, _db_fail, _db_ms, _db_n
    while True:
        t0 = time.perf_counter()
        try:
            with socket.create_connection((DB_HOST, DB_PORT), timeout=8):
                ok = True
        except Exception:
            ok = False
        ms = (time.perf_counter() - t0) * 1000.0
        with _lock:
            if ok:
                _db_ok += 1
                _db_ms += ms
                _db_n += 1
            else:
                _db_fail += 1
        time.sleep(DB_INTERVAL)


def render():
    with _lock:
        out = [
            "# HELP loadgen_target_rps Requests per second the generator is currently aiming for",
            "# TYPE loadgen_target_rps gauge",
            f'loadgen_target_rps{{az="{AZ}"}} {_current_rps:.3f}',
            "# HELP loadgen_requests_total Requests issued by the generator",
            "# TYPE loadgen_requests_total counter",
        ]
        for ep, n in _req.items():
            out.append(f'loadgen_requests_total{{az="{AZ}",endpoint="{ep}"}} {n}')
        out += ["# HELP loadgen_errors_total Requests that failed or returned >=400",
                "# TYPE loadgen_errors_total counter"]
        for ep in _req:
            out.append(f'loadgen_errors_total{{az="{AZ}",endpoint="{ep}"}} {_err.get(ep, 0)}')

        out += ["# HELP loadgen_duration_ms End-to-end latency as the client sees it",
                "# TYPE loadgen_duration_ms histogram"]
        for ep, b in _buckets.items():
            cum = 0
            for i, edge in enumerate(BUCKETS):
                cum += b[i]
                out.append(f'loadgen_duration_ms_bucket{{az="{AZ}",endpoint="{ep}",le="{edge}"}} {cum}')
            cum += b[-1]
            out.append(f'loadgen_duration_ms_bucket{{az="{AZ}",endpoint="{ep}",le="+Inf"}} {cum}')
            out.append(f'loadgen_duration_ms_sum{{az="{AZ}",endpoint="{ep}"}} {_sum.get(ep, 0.0):.1f}')
            out.append(f'loadgen_duration_ms_count{{az="{AZ}",endpoint="{ep}"}} {cum}')

        out += [
            "# HELP loadgen_db_probe_total Cross-AZ database probes over the management plane",
            "# TYPE loadgen_db_probe_total counter",
            f'loadgen_db_probe_total{{az="{AZ}",result="ok"}} {_db_ok}',
            f'loadgen_db_probe_total{{az="{AZ}",result="fail"}} {_db_fail}',
            "# HELP loadgen_db_probe_ms_avg Mean cross-AZ database connect time (mgmt plane)",
            "# TYPE loadgen_db_probe_ms_avg gauge",
            f'loadgen_db_probe_ms_avg{{az="{AZ}"}} {(_db_ms / _db_n) if _db_n else 0:.2f}',
        ]
    return "\n".join(out) + "\n"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        if self.path.rstrip("/") != "/metrics":
            self.send_error(404)
            return
        body = render().encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    threading.Thread(target=driver, daemon=True).start()
    if DB_HOST:
        threading.Thread(target=db_driver, daemon=True).start()
    HTTPServer((BIND, PORT), Handler).serve_forever()
