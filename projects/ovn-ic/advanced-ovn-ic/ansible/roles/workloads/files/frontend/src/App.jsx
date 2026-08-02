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

/*
 * The "Testar" groups. Each button asks the backend to run those checks FOR REAL
 * at that moment — nothing is cached, so the ok/warn/error you get back is the
 * state of the fabric right now.
 */
/* The backend labels every check with a category; this maps it back to the
   button that owns it, so one "Testar tudo" fills every group. */
const CATEGORY_TO_GROUP = {
  "control plane": "control",
  "cross-AZ": "crossaz",
  database: "database",
  isolation: "isolation",
  "north-south": "northsouth",
  mtu: "mtu",
};

const TEST_GROUPS = [
  { id: "control",    label: "Plano de controle", hint: "transit switches, gateways, RAFT, túneis e rotas aprendidas (lido nos hosts das AZs)" },
  { id: "crossaz",    label: "Cross-AZ",          hint: "os dois planos atravessando o interconnect" },
  { id: "database",   label: "Banco de dados",    hint: "TCP e consulta real pelo plano de gerência" },
  { id: "isolation",  label: "Isolamento",        hint: "o plano client NÃO pode alcançar o banco — falhar é o resultado correto" },
  { id: "northsouth", label: "Saída externa",     hint: "internet via SNAT do plano" },
  { id: "mtu",        label: "MTU / GENEVE",      hint: "1414B passa, 1415B é rejeitado" },
];

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

/*
 * On-demand test panel. Results replace the previous ones per group, so pressing
 * a button twice shows whether something changed in between.
 */
function TestPanel({ results, running, onRun }) {
  const groups = TEST_GROUPS;
  const counts = (id) => {
    const rows = results[id] || [];
    return {
      ok: rows.filter((r) => r.status === "ok").length,
      bad: rows.filter((r) => r.status === "error").length,
      warn: rows.filter((r) => r.status === "warn").length,
      total: rows.length,
    };
  };

  return (
    <section className="card">
      <h2>
        Testes ao vivo <span className="muted">— cada botão executa as verificações no momento do clique</span>
      </h2>
      <p className="hint">
        Nada aqui é cache. O grupo <b>Isolamento</b> é o único em que <i>falhar é o
        resultado esperado</i>: se o banco responder ao plano client, isso é um defeito
        e aparece em vermelho.
      </p>

      <div className="test-actions">
        <button className="btn btn-primary" disabled={!!running} onClick={() => onRun("all")}>
          {running === "all" ? "Testando…" : "Testar tudo"}
        </button>
      </div>

      {groups.map((g) => {
        const c = counts(g.id);
        const rows = results[g.id] || [];
        const state = c.total === 0 ? "none" : c.bad ? "error" : c.warn ? "warn" : "ok";
        return (
          <div key={g.id} className="test-group">
            <div className="test-head">
              <div>
                <div className="test-title">
                  {g.label}{" "}
                  {c.total > 0 && (
                    <span className={`pill pill-${state}`}>
                      {c.bad ? `${c.bad} com erro` : c.warn ? `${c.warn} atenção` : `${c.ok} ok`}
                    </span>
                  )}
                </div>
                <div className="test-hint">{g.hint}</div>
              </div>
              <button className="btn" disabled={!!running} onClick={() => onRun(g.id)}>
                {running === g.id ? "Testando…" : "Testar"}
              </button>
            </div>
            {rows.length > 0 && (
              <table>
                <tbody>
                  {rows.map((r, i) => (
                    <tr key={i}>
                      <td className="mono az">{r.az}</td>
                      <td>{r.item}</td>
                      <td className="mono">{r.value}</td>
                      <td className="mono num">{r.ms != null ? `${r.ms} ms` : ""}</td>
                      <td>
                        <span className={`pill pill-${r.status}`}>{r.status}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        );
      })}
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
  const [testResults, setTestResults] = useState({});
  const [running, setRunning] = useState(null);

  /* Runs one group, or every group when id === "all". */
  const runTests = useCallback(async (id) => {
    setRunning(id);
    try {
      const res = await fetch(`/api/test?group=${encodeURIComponent(id)}`, { cache: "no-store" });
      const body = await res.json();
      const byGroup = {};
      // Start every requested group empty, so a group that returns nothing is
      // shown as "0 ok" rather than silently keeping its previous results.
      for (const g of TEST_GROUPS) if (id === "all" || id === g.id) byGroup[g.id] = [];
      for (const c of body.checks || []) {
        const key = CATEGORY_TO_GROUP[c.category] || id;
        (byGroup[key] = byGroup[key] || []).push(c);
      }
      setTestResults((prev) => ({ ...prev, ...byGroup }));
    } catch (e) {
      setTestResults((prev) => ({
        ...prev,
        [id]: [{ az: "-", item: "chamada ao backend", value: String(e), status: "error" }],
      }));
    } finally {
      setRunning(null);
    }
  }, []);

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

      <TestPanel results={testResults} running={running} onRun={runTests} />

      <InfraTable rows={infra?.rows} />

      <footer className="muted">
        Polling every {POLL_MS / 1000}s · served by {status?.served_by ?? "—"} · the
        database is unreachable from the client plane by design
      </footer>
    </div>
  );
}
