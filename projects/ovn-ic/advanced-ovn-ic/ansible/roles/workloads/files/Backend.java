/*
 * Backend tier for the advanced-ovn-ic lab. Runs on app-vm-2 (AZ2).
 *
 * Where it sits in the architecture:
 *
 *   React frontend (AZ1, client plane)
 *        |  HTTP, through AZ2's OVN load-balancer VIP  -- CROSS-AZ over ts-client
 *        v
 *   THIS SERVICE (AZ2, client plane, bound to its client NIC)
 *        |  JDBC                                       -- management plane only
 *        v
 *   PostgreSQL on db-vm (10.20.2.10, AZ2, mgmt plane)
 *
 * It is dual-homed on purpose: it is *reached* on the client plane and it
 * *reaches* the database on the management plane. The two never mix.
 *
 * Deliberately dependency-free (JDK HttpServer + JDBC only, no Maven, no Spring).
 * The workloads are 2 vCPU containers behind a double NAT; pulling a dependency
 * tree through that is slow and fragile, and nothing here needs a framework.
 *
 * Endpoints (all JSON):
 *   GET /api/health  - liveness, tier identity
 *   GET /api/status  - live self-probe: identity, both plane addresses, DB latency
 *   GET /api/infra   - infrastructure state, read from the DB (published by Ansible)
 *   GET /api/flow    - the request path with measured per-hop timings
 *   GET /api/test    - run the live infrastructure checks behind the dashboard's
 *                      "Testar" buttons; ?group=<name> runs one group only
 */
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class Backend {

    private static final String AZ        = env("APP_AZ", "unknown");
    private static final String HOST      = env("APP_HOST", "unknown");
    private static final String CLIENT_IP = env("APP_CLIENT_IP", "unknown");
    private static final String MGMT_IP   = env("APP_MGMT_IP", "unknown");
    private static final String DB_HOST   = env("DB_HOST", "unknown");
    private static final int    DB_PORT   = Integer.parseInt(env("DB_PORT", "5432"));
    private static final String DB_NAME   = env("DB_NAME", "appdb");
    private static final String DB_USER   = env("DB_USER", "appuser");
    private static final String DB_PASS   = env("DB_PASSWORD", "");
    private static final int    PORT      = Integer.parseInt(env("BACKEND_PORT", "8080"));
    private static final String FRONT_IP  = env("APP_FRONTEND_IP", "");
    private static final int    FRONT_PORT= Integer.parseInt(env("APP_FRONTEND_PORT", "80"));
    private static final String FRONT_MGMT= env("APP_FRONTEND_MGMT_IP", "");
    private static final String FRONT_AZ  = env("APP_FRONTEND_AZ", "az1");
    // Read-only probe agents on the AZ hosts: "az1=172.18.3.175,az2=172.18.33.126"
    private static final String PROBES    = env("APP_PROBE_AGENTS", "");
    private static final int    PROBE_PORT= Integer.parseInt(env("APP_PROBE_PORT", "9101"));
    private static final int    METRICS_PORT = Integer.parseInt(env("APP_METRICS_PORT", "9102"));

    // ---- metrics state (plain counters; no client library, same as the rest) --
    private static final java.util.concurrent.atomic.AtomicLong REQ_TOTAL =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong REQ_ERRORS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> REQ_BY_PATH =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong DB_QUERIES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong DB_ERRORS =
            new java.util.concurrent.atomic.AtomicLong();
    // Latency histogram buckets in milliseconds, cumulative Prometheus style.
    private static final double[] BUCKETS = {1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500};
    private static final java.util.concurrent.atomic.AtomicLong[] REQ_BUCKETS =
            new java.util.concurrent.atomic.AtomicLong[BUCKETS.length + 1];
    private static final java.util.concurrent.atomic.AtomicLong REQ_SUM_MS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong[] DB_BUCKETS =
            new java.util.concurrent.atomic.AtomicLong[BUCKETS.length + 1];
    private static final java.util.concurrent.atomic.AtomicLong DB_SUM_MS =
            new java.util.concurrent.atomic.AtomicLong();
    static {
        for (int i = 0; i < REQ_BUCKETS.length; i++) {
            REQ_BUCKETS[i] = new java.util.concurrent.atomic.AtomicLong();
            DB_BUCKETS[i] = new java.util.concurrent.atomic.AtomicLong();
        }
    }

    private static final String JDBC_URL =
        "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME
        + "?connectTimeout=5&socketTimeout=15";

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/health", timed("/api/health", ex -> health()));
        server.createContext("/api/status", timed("/api/status", ex -> status()));
        server.createContext("/api/infra",  timed("/api/infra",  ex -> infra()));
        server.createContext("/api/flow",   timed("/api/flow",   ex -> flow()));
        server.createContext("/api/test",   timed("/api/test",   ex -> test(query(ex, "group"))));
        server.createContext("/api/orders", timed("/api/orders", ex -> orders()));
        server.createContext("/api/report", timed("/api/report", ex -> report()));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        /*
         * Metrics are served on the MANAGEMENT nic, on their own port. The service
         * itself is offered to tenants on the client plane; its instrumentation is
         * an operations concern and belongs on the management plane, where
         * Prometheus lives. Same process, two planes, deliberately separated.
         */
        HttpServer metrics = HttpServer.create(
                new InetSocketAddress(MGMT_IP, METRICS_PORT), 0);
        metrics.createContext("/metrics", ex -> respondText(ex, 200, metrics()));
        metrics.setExecutor(Executors.newFixedThreadPool(2));
        metrics.start();
        System.out.println("metrics on " + MGMT_IP + ":" + METRICS_PORT + " (mgmt plane)");
        System.out.println("backend tier listening on :" + PORT
                           + " (az=" + AZ + ", db=" + DB_HOST + " over the mgmt plane)");
    }

    // ---------------------------------------------------------------- endpoints

    private static String health() {
        return "{\"status\":\"ok\",\"tier\":\"backend\",\"az\":\"" + esc(AZ)
             + "\",\"served_by\":\"" + esc(HOST) + "\"}";
    }

    private static String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"tier\":\"backend\",\"served_by\":\"").append(esc(HOST))
          .append("\",\"az\":\"").append(esc(AZ))
          .append("\",\"client_ip\":\"").append(esc(CLIENT_IP))
          .append("\",\"mgmt_ip\":\"").append(esc(MGMT_IP))
          .append("\",\"db_host\":\"").append(esc(DB_HOST))
          .append("\",\"db_plane\":\"mgmt\",\"nics\":").append(nics());

        long t0 = System.nanoTime();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT now()::text")) {
                rs.next();
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                sb.append(",\"db_ok\":true,\"db_time\":\"").append(esc(rs.getString(1)))
                  .append("\",\"db_latency_ms\":").append(round(ms));
            }
        } catch (Exception e) {
            sb.append(",\"db_ok\":false,\"db_error\":\"").append(esc(e.getMessage())).append("\"");
        }
        return sb.append("}").toString();
    }

    /*
     * Infrastructure state, published into the database by Ansible on each AZ (see
     * the publish_infra_state task). The dashboard renders this, which means the
     * infra view itself travels: AZ host -> DB (mgmt plane) -> here -> frontend
     * (cross-AZ) -> browser.
     */
    private static String infra() {
        StringBuilder rows = new StringBuilder("[");
        String sql = "SELECT az, category, item, value, status, "
                   + "to_char(updated_at,'YYYY-MM-DD HH24:MI:SS') "
                   + "FROM infra_state ORDER BY az, category, item";
        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            boolean first = true;
            while (rs.next()) {
                if (!first) rows.append(",");
                first = false;
                rows.append("{\"az\":\"").append(esc(rs.getString(1)))
                    .append("\",\"category\":\"").append(esc(rs.getString(2)))
                    .append("\",\"item\":\"").append(esc(rs.getString(3)))
                    .append("\",\"value\":\"").append(esc(rs.getString(4)))
                    .append("\",\"status\":\"").append(esc(rs.getString(5)))
                    .append("\",\"updated_at\":\"").append(esc(rs.getString(6)))
                    .append("\"}");
            }
            rows.append("]");
            return "{\"ok\":true,\"served_by\":\"" + esc(HOST) + "\",\"rows\":" + rows + "}";
        } catch (Exception e) {
            return "{\"ok\":false,\"served_by\":\"" + esc(HOST)
                 + "\",\"error\":\"" + esc(e.getMessage()) + "\",\"rows\":[]}";
        }
    }

    /*
     * Measures the hops this tier can see for itself, so the dashboard can show
     * where the time in a request actually goes.
     */
    private static String flow() {
        StringBuilder sb = new StringBuilder("{\"served_by\":\"" + esc(HOST) + "\",\"hops\":[");

        sb.append("{\"from\":\"backend (").append(esc(AZ)).append(", client plane)\"")
          .append(",\"to\":\"db-vm ").append(esc(DB_HOST)).append(" (mgmt plane)\"")
          .append(",\"plane\":\"mgmt\",\"scope\":\"intra-AZ\"");
        Probe db = tcpProbe(DB_HOST, DB_PORT);
        sb.append(",\"ok\":").append(db.ok).append(",\"ms\":").append(round(db.ms)).append("}");

        sb.append("]}");
        return sb.toString();
    }


    // ------------------------------------------------------------ live tests
    /*
     * The checks behind the dashboard's "Testar" buttons. Each press runs these
     * for real — nothing here is cached.
     *
     * They are grouped by what they prove, and split by WHO can prove it:
     *   - this service can prove anything reachable from the container: the
     *     database, the cross-AZ path to the frontend, north-south, the MTU
     *     ceiling, and (by binding a source address) that plane isolation holds;
     *   - only the AZ hosts can see OVN itself, so the "control plane" group is
     *     delegated to the read-only probe agents over the management plane.
     */
    private static String test(String group) {
        List<String> out = new ArrayList<>();
        boolean all = (group == null || group.isEmpty() || "all".equals(group));

        if (all || "database".equals(group)) {
            Probe p = tcpProbe(DB_HOST, DB_PORT);
            out.add(check("database", "TCP to " + DB_HOST + ":" + DB_PORT + " (mgmt plane)",
                          p.ok ? "reachable" : "unreachable", p.ok ? "ok" : "error", p.ms));
            long t0 = System.nanoTime();
            try (Connection c = connect(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM lab_info")) {
                rs.next();
                out.add(check("database", "SELECT over the mgmt plane",
                              rs.getInt(1) + " rows in lab_info", "ok",
                              (System.nanoTime() - t0) / 1_000_000.0));
            } catch (Exception e) {
                out.add(check("database", "SELECT over the mgmt plane",
                              e.getMessage(), "error", (System.nanoTime() - t0) / 1_000_000.0));
            }
        }

        if (all || "crossaz".equals(group)) {
            // The client-plane path to the other AZ: this is the hop the whole
            // application depends on, over ts-client.
            Probe c = tcpProbe(FRONT_IP, FRONT_PORT);
            out.add(check("cross-AZ", "client plane -> " + FRONT_AZ + " frontend "
                          + FRONT_IP + ":" + FRONT_PORT,
                          c.ok ? "reachable over ts-client" : "unreachable",
                          c.ok ? "ok" : "error", c.ms));
            // And the management-plane path to the same AZ, over ts-mgmt.
            Probe m = tcpProbe(FRONT_MGMT, 22);
            out.add(check("cross-AZ", "mgmt plane -> " + FRONT_AZ + " " + FRONT_MGMT + " (ICMP)",
                          m.ms < 4000 ? "reachable over ts-mgmt" : "no answer",
                          m.ms < 4000 ? "ok" : "warn", m.ms));
        }

        if (all || "isolation".equals(group)) {
            // Bind the source to the CLIENT-plane address and try to reach the
            // database. It must fail: OVN port security on the mgmt port only
            // permits the mgmt address, and no route carries client -> mgmt.
            // A SUCCESS here is a real defect, so success is reported as error.
            long t0 = System.nanoTime();
            boolean leaked;
            try (Socket s = new Socket()) {
                s.bind(new InetSocketAddress(CLIENT_IP, 0));
                s.connect(new InetSocketAddress(DB_HOST, DB_PORT), 3000);
                leaked = true;
            } catch (Exception e) {
                leaked = false;
            }
            out.add(check("isolation", "client plane -> database (must FAIL)",
                          leaked ? "LEAKED: the database answered the client plane"
                                 : "refused, as designed",
                          leaked ? "error" : "ok", (System.nanoTime() - t0) / 1_000_000.0));
        }

        if (all || "northsouth".equals(group)) {
            Probe p = tcpProbe("1.1.1.1", 53);
            out.add(check("north-south", "internet through this plane's SNAT",
                          p.ok ? "egress works" : "no egress", p.ok ? "ok" : "error", p.ms));
        }

        if (all || "mtu".equals(group)) {
            int fit = overlayMtu() - 28, over = fit + 1;
            long t0 = System.nanoTime();
            boolean okFit  = ping(DB_HOST, fit);
            boolean okOver = ping(DB_HOST, over);
            // The ceiling is only proven if the small one passes AND the big one
            // is rejected; either half alone means nothing.
            boolean good = okFit && !okOver;
            out.add(check("mtu", "GENEVE ceiling (" + fit + "B ok, " + over + "B rejected)",
                          okFit ? (okOver ? "both sizes passed - MTU is NOT clamped"
                                          : fit + "B passes, " + over + "B rejected")
                                : fit + "B already fails",
                          good ? "ok" : "error", (System.nanoTime() - t0) / 1_000_000.0));
        }

        if (all || "control".equals(group)) {
            out.addAll(probeAgents());
        }

        return "{\"served_by\":\"" + esc(HOST) + "\",\"group\":\"" + esc(all ? "all" : group)
             + "\",\"checks\":[" + String.join(",", out) + "]}";
    }

    /* Ask each AZ host's read-only agent for the OVN state only it can see. */
    private static List<String> probeAgents() {
        List<String> out = new ArrayList<>();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4)).build();
        for (String entry : PROBES.split(",")) {
            if (entry.isBlank()) continue;
            String[] kv = entry.split("=", 2);
            String az = kv[0], ip = kv.length > 1 ? kv[1] : kv[0];
            long t0 = System.nanoTime();
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + ip + ":" + PROBE_PORT + "/probe"))
                        .timeout(Duration.ofSeconds(10)).GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                if (res.statusCode() != 200) {
                    out.add(check("control plane", az + " probe agent",
                                  "HTTP " + res.statusCode(), "error", ms));
                    continue;
                }
                // The agent already returns objects in our shape; splice its
                // checks straight in rather than re-parsing with a JSON library
                // we deliberately do not depend on.
                String body = res.body();
                // Locate the array without assuming how the producer spaces its
                // JSON — the agent is Python, whose default separators include a
                // space that a literal "checks":[ match would miss.
                int k = body.indexOf("\"checks\"");
                int i = k < 0 ? -1 : body.indexOf('[', k);
                int j = body.lastIndexOf(']');
                if (i < 0 || j <= i) { out.add(check("control plane", az + " probe agent",
                                                     "malformed answer", "error", ms)); continue; }
                String arr = body.substring(i + 1, j);
                for (String obj : splitObjects(arr)) out.add(withMs(obj, ms));
            } catch (Exception e) {
                out.add(check("control plane", az + " probe agent",
                              "unreachable: " + e.getMessage(), "error",
                              (System.nanoTime() - t0) / 1_000_000.0));
            }
        }
        return out;
    }

    /* Split a flat JSON array body into its top-level {...} objects. */
    private static List<String> splitObjects(String arr) {
        List<String> objs = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false, escNext = false;
        for (int i = 0; i < arr.length(); i++) {
            char ch = arr.charAt(i);
            if (escNext) { escNext = false; continue; }
            if (ch == '\\') { escNext = true; continue; }
            if (ch == '"') inStr = !inStr;
            if (inStr) continue;
            if (ch == '{') { if (depth++ == 0) start = i; }
            else if (ch == '}') { if (--depth == 0 && start >= 0) objs.add(arr.substring(start, i + 1)); }
        }
        return objs;
    }

    private static String withMs(String obj, double ms) {
        return obj.substring(0, obj.length() - 1) + ",\"ms\":" + round(ms) + "}";
    }

    private static String check(String cat, String item, String value, String status, double ms) {
        return "{\"category\":\"" + esc(cat) + "\",\"item\":\"" + esc(item)
             + "\",\"value\":\"" + esc(value) + "\",\"status\":\"" + esc(status)
             + "\",\"az\":\"" + esc(AZ) + "\",\"ms\":" + round(ms) + "}";
    }

    /* ping with don't-fragment: the only reliable way to prove the MTU ceiling. */
    private static boolean ping(String host, int size) {
        try {
            Process p = new ProcessBuilder("ping", "-c1", "-W2", "-M", "do",
                                           "-s", String.valueOf(size), host)
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int overlayMtu() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isLoopback() && ni.isUp()) return ni.getMTU();
            }
        } catch (Exception ignored) { }
        return 1442;
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) {
            String[] p = kv.split("=", 2);
            if (p[0].equals(key)) return p.length > 1 ? p[1] : "";
        }
        return null;
    }


    // ------------------------------------------------- instrumentation + work

    /** Wraps a handler so every request is counted and timed. */
    private static com.sun.net.httpserver.HttpHandler timed(
            String path, java.util.function.Function<HttpExchange, String> fn) {
        return ex -> {
            long t0 = System.nanoTime();
            String body;
            boolean failed = false;
            try {
                body = fn.apply(ex);
            } catch (Exception e) {
                failed = true;
                body = "{\"error\":\"" + esc(e.getMessage()) + "\"}";
            }
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            REQ_TOTAL.incrementAndGet();
            if (failed) REQ_ERRORS.incrementAndGet();
            REQ_BY_PATH.computeIfAbsent(path,
                    k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
            observe(REQ_BUCKETS, REQ_SUM_MS, ms);
            respond(ex, failed ? 500 : 200, body);
        };
    }

    private static void observe(java.util.concurrent.atomic.AtomicLong[] buckets,
                                java.util.concurrent.atomic.AtomicLong sum, double ms) {
        sum.addAndGet(Math.round(ms));
        for (int i = 0; i < BUCKETS.length; i++) {
            if (ms <= BUCKETS[i]) { buckets[i].incrementAndGet(); return; }
        }
        buckets[BUCKETS.length].incrementAndGet();   // +Inf
    }

    /*
     * Real work for the traffic generator to drive, so the database sees a mix of
     * writes and heavy reads instead of one trivial SELECT. Without this the
     * dashboards have nothing interesting to show.
     */
    private static String orders() {
        long t0 = System.nanoTime();
        try (Connection c = connect()) {
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO orders(item, amount, az) VALUES (?, ?, ?)")) {
                ps.setString(1, "item-" + (System.nanoTime() % 50));
                ps.setInt(2, (int) (System.nanoTime() % 500) + 1);
                ps.setString(3, AZ);
                ps.executeUpdate();
            }
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            DB_QUERIES.incrementAndGet();
            observe(DB_BUCKETS, DB_SUM_MS, ms);
            return "{\"ok\":true,\"op\":\"insert\",\"db_latency_ms\":" + round(ms) + "}";
        } catch (Exception e) {
            DB_ERRORS.incrementAndGet();
            return "{\"ok\":false,\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /** A deliberately heavier read: aggregation over the whole orders table. */
    private static String report() {
        long t0 = System.nanoTime();
        String sql = "SELECT count(*), coalesce(sum(amount),0), coalesce(round(avg(amount),2),0) "
                   + "FROM orders";
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            long n = rs.getLong(1), total = rs.getLong(2);
            String avg = rs.getString(3);
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            DB_QUERIES.incrementAndGet();
            observe(DB_BUCKETS, DB_SUM_MS, ms);
            return "{\"ok\":true,\"orders\":" + n + ",\"total\":" + total
                 + ",\"avg\":" + avg + ",\"db_latency_ms\":" + round(ms) + "}";
        } catch (Exception e) {
            DB_ERRORS.incrementAndGet();
            return "{\"ok\":false,\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /** Prometheus text exposition. Hand-rolled, like the rest of this service. */
    private static String metrics() {
        StringBuilder m = new StringBuilder();
        String L = "az=\"" + AZ + "\",tier=\"backend\",instance=\"" + HOST + "\"";

        m.append("# HELP backend_requests_total HTTP requests served by the backend tier\n");
        m.append("# TYPE backend_requests_total counter\n");
        for (var e : REQ_BY_PATH.entrySet()) {
            m.append("backend_requests_total{").append(L).append(",path=\"")
             .append(e.getKey()).append("\"} ").append(e.getValue().get()).append("\n");
        }
        m.append("# HELP backend_request_errors_total Requests that raised\n");
        m.append("# TYPE backend_request_errors_total counter\n");
        m.append("backend_request_errors_total{").append(L).append("} ")
         .append(REQ_ERRORS.get()).append("\n");

        histogram(m, "backend_request_duration_ms", "Backend request latency",
                  L, REQ_BUCKETS, REQ_SUM_MS, REQ_TOTAL.get());

        m.append("# HELP backend_db_queries_total Queries issued over the management plane\n");
        m.append("# TYPE backend_db_queries_total counter\n");
        m.append("backend_db_queries_total{").append(L).append("} ")
         .append(DB_QUERIES.get()).append("\n");
        m.append("# HELP backend_db_errors_total Failed database queries\n");
        m.append("# TYPE backend_db_errors_total counter\n");
        m.append("backend_db_errors_total{").append(L).append("} ")
         .append(DB_ERRORS.get()).append("\n");
        histogram(m, "backend_db_duration_ms", "Database query latency (mgmt plane)",
                  L, DB_BUCKETS, DB_SUM_MS, DB_QUERIES.get());

        // A liveness gauge that costs one TCP connect, so Grafana can show the
        // database's reachability from the backend as a time series.
        Probe p = tcpProbe(DB_HOST, DB_PORT);
        m.append("# HELP backend_db_up Database reachable from the backend over the mgmt plane\n");
        m.append("# TYPE backend_db_up gauge\n");
        m.append("backend_db_up{").append(L).append("} ").append(p.ok ? 1 : 0).append("\n");
        return m.toString();
    }

    private static void histogram(StringBuilder m, String name, String help, String labels,
                                  java.util.concurrent.atomic.AtomicLong[] buckets,
                                  java.util.concurrent.atomic.AtomicLong sum, long count) {
        m.append("# HELP ").append(name).append(' ').append(help).append('\n');
        m.append("# TYPE ").append(name).append(" histogram\n");
        long cum = 0;
        for (int i = 0; i < BUCKETS.length; i++) {
            cum += buckets[i].get();
            m.append(name).append("_bucket{").append(labels).append(",le=\"")
             .append((long) BUCKETS[i]).append("\"} ").append(cum).append('\n');
        }
        cum += buckets[BUCKETS.length].get();
        m.append(name).append("_bucket{").append(labels).append(",le=\"+Inf\"} ").append(cum).append('\n');
        m.append(name).append("_sum{").append(labels).append("} ").append(sum.get()).append('\n');
        m.append(name).append("_count{").append(labels).append("} ").append(cum).append('\n');
    }

    private static void respondText(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    // ---------------------------------------------------------------- helpers

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS);
    }

    private static final class Probe { boolean ok; double ms; }

    private static Probe tcpProbe(String host, int port) {
        Probe p = new Probe();
        long t0 = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 4000);
            p.ok = true;
        } catch (Exception e) {
            p.ok = false;
        }
        p.ms = (System.nanoTime() - t0) / 1_000_000.0;
        return p;
    }

    /* Shows that this container really is dual-homed, straight from the kernel. */
    private static String nics() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.getHostAddress().contains(":")) continue;
                    String plane = a.getHostAddress().startsWith("10.20.") ? "mgmt"
                                 : a.getHostAddress().startsWith("10.10.") ? "client" : "other";
                    out.add("{\"iface\":\"" + esc(ni.getName())
                          + "\",\"ip\":\"" + esc(a.getHostAddress())
                          + "\",\"mtu\":" + ni.getMTU()
                          + ",\"plane\":\"" + plane + "\"}");
                }
            }
        } catch (Exception ignored) { }
        return "[" + String.join(",", out) + "]";
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return (v == null || v.isEmpty()) ? d : v;
    }

    private static double round(double v) { return Math.round(v * 10.0) / 10.0; }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
