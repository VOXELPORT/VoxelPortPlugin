package io.voxelport.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class RelayClient extends WebSocketClient {

    // ---------------------------------------------------------------------------
    // Config

    /** Immutable config bundle passed from VoxelPortPlugin. */
    public static final class Config {
        final String relayWs;
        final String token;
        final String publicHost;
        final String serverHost;
        final int    serverPort;
        final int    maxConnections;
        final int    reconnectBaseDelay; // seconds
        final int    reconnectMaxDelay;  // seconds
        final int    reconnectJitter;    // seconds of random jitter added each backoff step

        public Config(String relayWs, String token, String publicHost,
                      String serverHost, int serverPort, int maxConnections,
                      int reconnectBaseDelay, int reconnectMaxDelay, int reconnectJitter) {
            this.relayWs            = relayWs;
            this.token              = token;
            this.publicHost         = publicHost;
            this.serverHost         = serverHost;
            this.serverPort         = serverPort;
            this.maxConnections     = maxConnections;
            this.reconnectBaseDelay = Math.max(1, reconnectBaseDelay);
            this.reconnectMaxDelay  = Math.max(this.reconnectBaseDelay, reconnectMaxDelay);
            this.reconnectJitter    = Math.max(0, reconnectJitter);
        }
    }

    // ---------------------------------------------------------------------------
    // Constants

    /** Reject frames larger than this before allocating/decoding them. */
    private static final int MAX_FRAME_BASE64_CHARS = 2 * 1024 * 1024;
    private static final int MAX_DECODED_FRAME_BYTES = (MAX_FRAME_BASE64_CHARS / 4) * 3;

    private static final int LOCAL_CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_BUFFER_BYTES        = 65536;

    /**
     * Cap on bytes buffered for a connection still in the CONNECTING state.
     * Must hold at least one maximum-size relay frame so a legal first packet
     * cannot kill a connection that is merely slow to connect locally.
     */
    private static final int MAX_PENDING_BYTES = MAX_DECODED_FRAME_BYTES;

    // ---------------------------------------------------------------------------
    // Fields

    private final VoxelPortPlugin plugin;
    private final Config          cfg;
    private final Logger          log;

    // Stats counters — updated from IO threads, read from the main thread.
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicLong bytesFromServer  = new AtomicLong(); // server -> relay -> client
    private final AtomicLong bytesToServer    = new AtomicLong(); // client -> relay -> server

    private volatile int  assignedPort     = -1;
    private volatile int  currentBackoff;   // seconds; doubles on each failed attempt
    /** When true, onClose will not kick players or fire the disconnect callback.
     *  Set before intentional closes (reload, reconnect command, shutdown). */
    private volatile boolean suppressDisconnectEvent = false;

    // Latency tracking: nanoTime when the last ping was sent; -1 until first pong.
    private volatile long lastPingSentNanos = 0L;
    private volatile long latencyMs        = -1L;

    private final ConcurrentHashMap<String, PlayerConn> connections = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------------------
    // PlayerConn — state for one proxied player connection

    /** State for one proxied player. The local socket is opened asynchronously. */
    private static final class PlayerConn {
        State      state = State.CONNECTING;
        Socket     socket;
        OutputStream output;
        boolean    relayInitiatedClose;
        final java.util.ArrayDeque<byte[]> pending = new java.util.ArrayDeque<>();
        int        pendingBytes;
    }

    private enum State { CONNECTING, OPEN, CLOSED }

    // ---------------------------------------------------------------------------
    // Thread pools

    /** Single-threaded scheduler for heartbeat + reconnect timing. */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(namedFactory("voxelport-sched"));

    /**
     * Per-connection pump threads. Each proxied player holds one thread for the
     * duration of their connection, so this must grow on demand.
     */
    private final ExecutorService ioPool =
            Executors.newCachedThreadPool(namedFactory("voxelport-io"));

    private ScheduledFuture<?> pingTask;

    // ---------------------------------------------------------------------------
    // Constructor

    public RelayClient(VoxelPortPlugin plugin, Config cfg) {
        super(URI.create(cfg.relayWs));
        this.plugin          = plugin;
        this.cfg             = cfg;
        this.log             = plugin.getLogger();
        this.currentBackoff  = cfg.reconnectBaseDelay;
        setConnectionLostTimeout(30);
    }

    // ---------------------------------------------------------------------------
    // WebSocket lifecycle

    @Override
    public void onOpen(ServerHandshake handshake) {
        assignedPort           = -1;
        suppressDisconnectEvent = false; // reset so future unexpected drops are reported
        currentBackoff         = cfg.reconnectBaseDelay; // reset exponential backoff

        JsonObject msg = new JsonObject();
        msg.addProperty("type",  "register");
        msg.addProperty("token", cfg.token);
        send(msg.toString());

        if (pingTask != null && !pingTask.isDone()) pingTask.cancel(false);
        pingTask = scheduler.scheduleAtFixedRate(() -> {
            if (isOpen()) {
                lastPingSentNanos = System.nanoTime();
                send("{\"type\":\"ping\"}");
            }
        }, 25, 25, TimeUnit.SECONDS);
    }

    @Override
    public void onMessage(String raw) {
        // Never let a malformed frame tear down the WebSocket connection.
        try {
            JsonObject msg = JsonParser.parseString(raw).getAsJsonObject();
            String type = optString(msg, "type");
            if (type == null) return;

            switch (type) {
                case "port" -> {
                    int port = optInt(msg, "port", -1);
                    if (port < 0) return;
                    assignedPort = port;
                    log.info("==========================================");
                    log.info("  VoxelPort is ready!");
                    log.info("  Players connect via: " + getPublicAddress());
                    log.info("==========================================");
                    plugin.onRelayReady(port);
                }
                case "connect" -> {
                    String conn = optString(msg, "conn");
                    if (conn != null) openPlayerConnection(conn);
                }
                case "data" -> {
                    String conn = optString(msg, "conn");
                    String data = optString(msg, "data");
                    if (conn != null && data != null) forwardToServer(conn, data);
                }
                case "close" -> {
                    String conn = optString(msg, "conn");
                    if (conn != null) closePlayerConnection(conn);
                }
                case "pong" -> {
                    long sent = lastPingSentNanos;
                    if (sent != 0L) latencyMs = (System.nanoTime() - sent) / 1_000_000L;
                }
                case "error" -> {
                    String message = optString(msg, "message");
                    log.warning("Relay error: " + (message != null ? message : "unknown"));
                }
                // Unknown frame types are silently ignored for forward-compatibility.
            }
        } catch (Exception e) {
            log.warning("Ignoring malformed relay frame: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
        // Not used: relay sends text JSON only.
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        assignedPort = -1;
        if (pingTask != null) pingTask.cancel(false);
        closeAllConnections();

        String safeReason = (reason == null || reason.isEmpty()) ? "unknown" : reason;

        if (!suppressDisconnectEvent && plugin.isEnabled()) {
            plugin.onRelayDisconnected(safeReason);
        }

        log.warning("Disconnected from relay (" + safeReason + "). Reconnecting in " + currentBackoff + "s...");

        int delay = currentBackoff;

        // Exponential backoff with jitter: delay doubles each attempt, caps at max.
        int jitter = cfg.reconnectJitter > 0
                ? ThreadLocalRandom.current().nextInt(cfg.reconnectJitter + 1)
                : 0;
        currentBackoff = (int) Math.min((long) currentBackoff * 2 + jitter, cfg.reconnectMaxDelay);

        if (!scheduler.isShutdown()) {
            try {
                scheduler.schedule(() -> {
                    try { reconnect(); } catch (Exception e) {
                        log.warning("Reconnect attempt failed: " + e.getMessage());
                    }
                }, delay, TimeUnit.SECONDS);
            } catch (RejectedExecutionException ignored) {
                // scheduler was shut down between the isShutdown() check and schedule() —
                // this is fine; it means disconnect() was called intentionally.
            }
        }
    }

    @Override
    public void onError(Exception ex) {
        log.warning("Relay connection error: " + ex.getMessage());
    }

    // ---------------------------------------------------------------------------
    // Player connection management

    private void openPlayerConnection(String connId) {
        PlayerConn old = connections.get(connId);
        if (old == null && connections.size() >= cfg.maxConnections) {
            log.warning("Connection cap (" + cfg.maxConnections + ") reached: refusing " + connId);
            sendClose(connId);
            return;
        }

        // Register the slot immediately so relay data arriving before the local
        // socket finishes connecting is buffered rather than dropped.
        final PlayerConn pc = new PlayerConn();
        old = connections.put(connId, pc);
        if (old != null) teardown(old, true);

        totalConnections.incrementAndGet();

        try {
            ioPool.execute(() -> runPlayerConnection(connId, pc));
        } catch (RuntimeException e) {
            connections.remove(connId, pc);
            teardown(pc, false);
            sendClose(connId);
            log.warning("Unable to open local connection for " + connId + ": " + e.getMessage());
        }
    }

    private void runPlayerConnection(String connId, PlayerConn pc) {
        Socket socket = new Socket();
        try {
            synchronized (pc) {
                if (pc.state == State.CLOSED) {
                    closeQuietly(socket); // FIX: don't leak an unconnected socket
                    return;
                }
                pc.socket = socket;
            }

            socket.connect(new InetSocketAddress(cfg.serverHost, cfg.serverPort), LOCAL_CONNECT_TIMEOUT_MS);
            if (!isOpen()) {
                connections.remove(connId, pc);
                teardown(pc, false);
                if (!pc.relayInitiatedClose) sendClose(connId);
                return;
            }

            // Attach the output stream and flush any bytes that arrived while we
            // were connecting. Repeat until no bytes remain (relay may keep pushing
            // while we drain), then flip state to OPEN.
            OutputStream out = socket.getOutputStream();
            while (true) {
                byte[][] buffered;
                synchronized (pc) {
                    if (pc.state == State.CLOSED) return;
                    pc.output = out;
                    buffered = pc.pending.toArray(new byte[0][]);
                    pc.pending.clear();
                    pc.pendingBytes = 0;
                    if (buffered.length == 0) {
                        pc.state = State.OPEN;
                        break;
                    }
                }
                for (byte[] b : buffered) {
                    out.write(b);
                    bytesToServer.addAndGet(b.length);
                }
                out.flush();
            }

            // Local server → relay → client
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[READ_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (!isOpen()) break;
                bytesFromServer.addAndGet(n);
                String b64 = Base64.getEncoder().encodeToString(Arrays.copyOf(buf, n));
                JsonObject frame = new JsonObject();
                frame.addProperty("type", "data");
                frame.addProperty("conn", connId);
                frame.addProperty("data", b64);
                try {
                    send(frame.toString());
                } catch (RuntimeException e) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // Normal close or local server unreachable.
        } finally {
            connections.remove(connId, pc);
            teardown(pc, false);
            if (isOpen() && !pc.relayInitiatedClose) sendClose(connId);
        }
    }

    /** Client→relay data arriving from the relay: write to the local Minecraft server. */
    private void forwardToServer(String connId, String base64Data) {
        if (base64Data.length() > MAX_FRAME_BASE64_CHARS) {
            log.warning("Oversized data frame for " + connId + " (" + base64Data.length() + " chars): dropping");
            closePlayerConnection(connId, false);
            sendClose(connId);
            return;
        }

        PlayerConn pc = connections.get(connId);
        if (pc == null) return;

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            return; // malformed base64: ignore this frame
        }

        PlayerConn overflow = null;
        OutputStream out;
        synchronized (pc) {
            if (pc.state == State.CLOSED) return;
            if (pc.state == State.CONNECTING) {
                if (pc.pendingBytes + decoded.length > MAX_PENDING_BYTES) {
                    overflow = pc; // pending buffer full — drop this connection
                } else {
                    pc.pending.add(decoded);
                    pc.pendingBytes += decoded.length;
                    return;
                }
            }
            out = overflow == null ? pc.output : null;
        }

        if (overflow != null) {
            closePlayerConnection(connId, overflow, false);
            sendClose(connId);
            return;
        }

        if (out == null) {
            closePlayerConnection(connId, pc, false);
            sendClose(connId);
            return;
        }

        try {
            out.write(decoded);
            out.flush();
            bytesToServer.addAndGet(decoded.length);
        } catch (IOException e) {
            closePlayerConnection(connId, pc, false);
            sendClose(connId);
        }
    }

    private void closePlayerConnection(String connId) {
        closePlayerConnection(connId, true);
    }

    private void closePlayerConnection(String connId, boolean relayInitiated) {
        PlayerConn pc = connections.get(connId);
        if (pc == null) return;
        closePlayerConnection(connId, pc, relayInitiated);
    }

    private void closePlayerConnection(String connId, PlayerConn pc, boolean relayInitiated) {
        if (!connections.remove(connId, pc)) return;
        teardown(pc, relayInitiated);
    }

    private static void teardown(PlayerConn pc, boolean relayInitiated) {
        synchronized (pc) {
            if (relayInitiated) pc.relayInitiatedClose = true;
            pc.state = State.CLOSED;
            pc.pending.clear();
            pc.pendingBytes = 0;
            closeQuietly(pc.socket);
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }

    private void sendClose(String connId) {
        if (!isOpen()) return;
        JsonObject close = new JsonObject();
        close.addProperty("type", "close");
        close.addProperty("conn", connId);
        send(close.toString());
    }

    // ---------------------------------------------------------------------------
    // Public API

    /** Force an immediate reconnect, resetting the backoff counter. */
    public void reconnectNow() {
        currentBackoff          = cfg.reconnectBaseDelay;
        suppressDisconnectEvent = true; // don't kick players for a manual reconnect
        if (isOpen()) {
            close();
            // onClose will schedule the reconnect attempt
        } else {
            suppressDisconnectEvent = false;
            try { reconnect(); } catch (Exception e) {
                log.warning("Reconnect failed: " + e.getMessage());
            }
        }
    }

    /** Permanent shutdown — stops all threads and closes the WebSocket. */
    public void disconnect() {
        suppressDisconnectEvent = true;
        if (pingTask != null) pingTask.cancel(false);
        scheduler.shutdownNow();
        ioPool.shutdownNow();
        closeAllConnections();
        close();
    }

    private void closeAllConnections() {
        for (PlayerConn pc : connections.values()) teardown(pc, false);
        connections.clear();
    }

    public int     getAssignedPort()      { return assignedPort; }
    public boolean isReady()              { return assignedPort != -1; }
    public boolean isConnected()          { return isOpen(); }
    public String  getPublicAddress()     { return cfg.publicHost + ":" + assignedPort; }
    public int     getActiveConnections() { return connections.size(); }
    public long    getTotalConnections()  { return totalConnections.get(); }
    public long    getBytesFromServer()   { return bytesFromServer.get(); }
    public long    getBytesToServer()     { return bytesToServer.get(); }
    public long    getLatencyMs()         { return latencyMs; }

    // ---------------------------------------------------------------------------
    // Helpers

    private static String optString(JsonObject o, String key) {
        return (o.has(key) && o.get(key).isJsonPrimitive()) ? o.get(key).getAsString() : null;
    }

    private static int optInt(JsonObject o, String key, int def) {
        try {
            return (o.has(key) && o.get(key).isJsonPrimitive()) ? o.get(key).getAsInt() : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
