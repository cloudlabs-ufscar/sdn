import { useCallback, useEffect, useState } from "react";

/*
 * Infrastructure dashboard for the advanced-ovn-ic lab.
 *
 * This bundle is served by nginx on app-vm-1 (AZ1). Every /api/* call it makes is
 * proxied by that nginx to AZ2's load-balancer VIP, so each refresh crosses the
 * client transit switch over GENEVE, and the backend then reads the database over
 * the management plane. The round-trip time shown in the header is that whole
 * path, measured in the browser.
 */

const POLL_MS = 5000;

async function timedFetch(path) {
  const t0 = performance.now();
  const res = await fetch(path, { cache: "no-store" });
  const body = await res.json();
  return { body, ms: Math.round(performance.now() - t0) };
}

function Stat({ label, value, sub, tone = "neutral" }) {
  return (
    <div className={`stat tone-${tone}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  );
}

function PlaneBadge({ plane }) {
  return <span className={`plane plane-${plane}`}>{plane}</span>;
}

/* The point of the whole lab, drawn as the path a request actually takes. */
function FlowDiagram({ rtt, status }) {
  const dbMs = status?.db_latency_ms;
  const steps = [
    { title: "Browser", meta: "your laptop, over the SSH tunnel", plane: null, scope: null },
    { title: "React + nginx", meta: "app-vm-1 · AZ1 · 10.10.1.10", plane: "client", scope: "AZ1" },
    {
      title: "OVN load balancer",
      meta: "VIP 10.10.2.100 · across ts-client over GENEVE",
      plane: "client",
      scope: "cross-AZ",
      highlight: true,
    },
    { title: "Java backend", meta: "app-vm-2 · AZ2 · 10.10.2.20:8080", plane: "client", scope: "AZ2" },
    {
      title: "PostgreSQL",
      meta: `db-vm · 10.20.2.10${dbMs != null ? ` · ${dbMs} ms` : ""}`,
      plane: "mgmt",
      scope: "AZ2",
      highlight: true,
    },
  ];

  return (
    <section className="card">
      <h2>
        Request path <span className="muted">— {rtt != null ? `${rtt} ms round trip` : "measuring…"}</span>
      </h2>
      <p className="hint">
        Each refresh of this page traverses both planes and the interconnect. The
        highlighted hops are the ones that leave the local AZ or change plane.
      </p>
      <ol className="flow">
        {steps.map((s, i) => (
          <li key={i} className={s.highlight ? "flow-step hot" : "flow-step"}>
            <span className="flow-idx">{i + 1}</span>
            <div>
              <div className="flow-title">
                {s.title} {s.plane && <PlaneBadge plane={s.plane} />}
                {s.scope === "cross-AZ" && <span className="tag tag-cross">cross-AZ</span>}
              </div>
              <div className="flow-meta">{s.meta}</div>
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}

function InfraTable({ rows }) {
  if (!rows?.length) {
    return (
      <section className="card">
        <h2>Infrastructure state</h2>
        <p className="hint">
          No rows yet. Run <code>ansible-playbook workloads.yml</code> to publish the
          current OVN state into the database.
        </p>
      </section>
    );
  }

  const categories = [...new Set(rows.map((r) => r.category))];

  return (
    <section className="card">
      <h2>
        Infrastructure state <span className="muted">— collected on the AZ hosts, stored in Postgres</span>
      </h2>
      <p className="hint">
        This travels the long way round: each AZ host writes it to the database on the
        management plane, the Java backend reads it there, and it reaches this page
        across the interconnect.
      </p>
      {categories.map((cat) => (
        <div key={cat} className="cat">
          <h3>{cat}</h3>
          <table>
            <thead>
              <tr>
                <th>AZ</th>
                <th>Item</th>
                <th>Value</th>
                <th>State</th>
              </tr>
            </thead>
            <tbody>
              {rows
                .filter((r) => r.category === cat)
                .map((r, i) => (
                  <tr key={i}>
                    <td className="mono az">{r.az}</td>
                    <td>{r.item}</td>
                    <td className="mono">{r.value}</td>
                    <td>
                      <span className={`pill pill-${r.status}`}>{r.status}</span>
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      ))}
    </section>
  );
}

export default function App() {
  const [status, setStatus] = useState(null);
  const [infra, setInfra] = useState(null);
  const [rtt, setRtt] = useState(null);
  const [error, setError] = useState(null);
  const [updated, setUpdated] = useState(null);

  const load = useCallback(async () => {
    try {
      const s = await timedFetch("/api/status");
      setStatus(s.body);
      setRtt(s.ms);
      const i = await timedFetch("/api/infra");
      setInfra(i.body);
      setError(null);
      setUpdated(new Date().toLocaleTimeString());
    } catch (e) {
      setError(String(e));
    }
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, POLL_MS);
    return () => clearInterval(t);
  }, [load]);

  const dbOk = status?.db_ok;

  return (
    <div className="page">
      <header>
        <div>
          <h1>OVN-IC Multi-AZ Lab</h1>
          <p className="sub">
            React frontend in <b>AZ1</b> · Java backend in <b>AZ2</b> · PostgreSQL on the
            management plane
          </p>
        </div>
        <div className="updated">
          {error ? <span className="pill pill-down">disconnected</span> : <span className="pill pill-up">live</span>}
          {updated && <div className="muted">updated {updated}</div>}
        </div>
      </header>

      {error && <div className="card error">Could not reach the backend across the interconnect: {error}</div>}

      <div className="stats">
        <Stat label="Frontend" value="AZ1" sub="app-vm-1 · client plane" tone="az1" />
        <Stat label="Backend" value={status?.az?.toUpperCase() ?? "—"} sub={`${status?.served_by ?? "—"} · client plane`} tone="az2" />
        <Stat label="Cross-AZ round trip" value={rtt != null ? `${rtt} ms` : "—"} sub="browser → AZ1 → AZ2 → DB" tone="neutral" />
        <Stat
          label="Database"
          value={dbOk == null ? "—" : dbOk ? `${status.db_latency_ms} ms` : "down"}
          sub={`${status?.db_host ?? "—"} · mgmt plane`}
          tone={dbOk ? "ok" : dbOk === false ? "down" : "neutral"}
        />
      </div>

      <FlowDiagram rtt={rtt} status={status} />

      {status?.nics?.length > 0 && (
        <section className="card">
          <h2>
            Backend interfaces <span className="muted">— read from the container's kernel</span>
          </h2>
          <p className="hint">
            The backend is dual-homed: it is reached on the client plane and reaches the
            database on the management plane. The MTU is 1442 because GENEVE takes 58
            bytes off the 1500-byte underlay.
          </p>
          <table>
            <thead>
              <tr>
                <th>Interface</th>
                <th>Address</th>
                <th>Plane</th>
                <th>MTU</th>
              </tr>
            </thead>
            <tbody>
              {status.nics.map((n) => (
                <tr key={n.iface}>
                  <td className="mono">{n.iface}</td>
                  <td className="mono">{n.ip}</td>
                  <td>
                    <PlaneBadge plane={n.plane} />
                  </td>
                  <td className="mono">{n.mtu}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      <InfraTable rows={infra?.rows} />

      <footer className="muted">
        Polling every {POLL_MS / 1000}s · served by {status?.served_by ?? "—"} · the
        database is unreachable from the client plane by design
      </footer>
    </div>
  );
}
