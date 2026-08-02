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

    private static final String JDBC_URL =
        "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME
        + "?connectTimeout=5&socketTimeout=15";

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/health", ex -> respond(ex, 200, health()));
        server.createContext("/api/status", ex -> respond(ex, 200, status()));
        server.createContext("/api/infra",  ex -> respond(ex, 200, infra()));
        server.createContext("/api/flow",   ex -> respond(ex, 200, flow()));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
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
