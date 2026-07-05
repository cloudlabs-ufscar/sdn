"""
FastAPI backend + tiny frontend for the advanced-ovn-ic lab.

Identical code on app-vm-1 (AZ1) and app-vm-2 (AZ2); behaviour is driven by the
environment set in the systemd unit. Every request opens a real PostgreSQL
connection to db-vm (10.10.2.10) — cross-AZ from app-vm-1 (over the GENEVE
tunnel) and intra-AZ from app-vm-2.

Endpoints:
  GET /        JSON  -> {served_by, az, db_message, db_latency_ms}  (used by the LB test)
  GET /health  JSON  -> {status, served_by, az}
  GET /ui      HTML  -> a one-page dashboard; refresh it and watch the OVN load
                        balancer flip you between AZ1 and AZ2.
"""
import os
import time

import psycopg
from fastapi import FastAPI
from fastapi.responses import HTMLResponse

app = FastAPI()

AZ = os.environ.get("APP_AZ", "unknown")
HOST = os.environ.get("APP_HOST", "unknown")
DB_AZ = os.environ.get("DB_AZ", "az2")          # which AZ the database lives in

CONNINFO = (
    f"host={os.environ.get('DB_HOST')} "
    f"port={os.environ.get('DB_PORT')} "
    f"dbname={os.environ.get('DB_NAME')} "
    f"user={os.environ.get('DB_USER')} "
    f"password={os.environ.get('DB_PASSWORD')} "
    f"connect_timeout=5"
)


def query_db():
    """Return (message, latency_ms); raises on failure."""
    started = time.perf_counter()
    with psycopg.connect(CONNINFO) as conn, conn.cursor() as cur:
        cur.execute("SELECT message FROM lab_info ORDER BY id LIMIT 1")
        message = cur.fetchone()[0]
    return message, round((time.perf_counter() - started) * 1000, 1)


@app.get("/health")
def health():
    return {"status": "ok", "served_by": HOST, "az": AZ}


@app.get("/")
def root():
    try:
        message, latency = query_db()
        return {"served_by": HOST, "az": AZ, "db_message": message, "db_latency_ms": latency}
    except Exception as exc:  # keep 200 so the LB test still shows the backend
        return {"served_by": HOST, "az": AZ, "db_error": str(exc)}


@app.get("/ui", response_class=HTMLResponse)
def ui():
    path = "intra-AZ (local)" if AZ == DB_AZ else "cross-AZ (over the GENEVE tunnel)"
    accent = "#1f9d55" if AZ == "az1" else "#2779bd"
    try:
        message, latency = query_db()
        db_block = (
            f'<p>DB row: <b>&ldquo;{message}&rdquo;</b></p>'
            f'<p>Query latency: <b>{latency} ms</b> &mdash; {path}</p>'
        )
    except Exception as exc:
        db_block = f'<p style="color:#cc1f1a">DB error: {exc}</p>'

    html = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta http-equiv="refresh" content="3">
  <title>OVN-IC Multi-AZ Lab</title>
  <style>
    body {{ font-family: system-ui, sans-serif; background:#f7fafc; color:#1a202c;
           display:flex; min-height:100vh; align-items:center; justify-content:center; margin:0; }}
    .card {{ background:#fff; border-radius:14px; box-shadow:0 8px 30px rgba(0,0,0,.08);
             padding:32px 40px; max-width:560px; width:90%; }}
    .badge {{ display:inline-block; background:{accent}; color:#fff; font-weight:700;
              padding:6px 14px; border-radius:999px; font-size:14px; letter-spacing:.5px; }}
    h1 {{ font-size:22px; margin:14px 0 4px; }}
    .sub {{ color:#718096; font-size:14px; margin-top:0; }}
    .panel {{ background:#f7fafc; border:1px solid #e2e8f0; border-radius:10px;
              padding:14px 18px; margin-top:18px; }}
    code {{ background:#edf2f7; padding:2px 6px; border-radius:6px; }}
    .foot {{ color:#a0aec0; font-size:12px; margin-top:18px; }}
  </style>
</head>
<body>
  <div class="card">
    <span class="badge">served by {HOST} &nbsp;&middot;&nbsp; {AZ.upper()}</span>
    <h1>OVN-IC Multi-AZ Lab</h1>
    <p class="sub">One VPC federated across two Incus clouds through a transit switch.</p>
    <div class="panel">{db_block}</div>
    <p class="foot">Reload (this page auto-refreshes every 3s) and the native OVN load
       balancer may route you to the other AZ &mdash; watch the badge and the latency change.
       VIP <code>10.10.1.100:80</code> &rarr; app-vm-1 (AZ1) &amp; app-vm-2 (AZ2).</p>
  </div>
</body>
</html>"""
    # Connection: close -> the browser reconnects on each refresh, so the L4 load
    # balancer re-picks a backend and you actually see the AZ flip.
    return HTMLResponse(content=html, headers={"Connection": "close"})
