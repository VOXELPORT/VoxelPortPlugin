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
import java.util.logging.Logger;

public class RelayClient extends WebSocketClient {

    /** Hard cap on simultaneous proxied connections. Protects the local server
     *  from resource exhaustion if the relay misbehaves or is compromised. */
    private static final int MAX_CONNECTIONS = 200;

    /** Reject oversized data frames before allocating/decoding them.
     *  Legitimate Minecraft packet chunks fit comfortably under this. */
    private static final int MAX_FRAME_BASE64_CHARS = 2 * 1024 * 1024; // ~1.5 MB decoded
    private static final int MAX_DECODED_FRAME_BYTES = (MAX_FRAME_BASE64_CHARS / 4) * 3;

    private static final int LOCAL_CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_BUFFER_BYTES = 65536;

    /** Cap on client bytes buffered before the local socket finishes connecting.
     *  Must hold at least one relay-accepted frame so a legal frame cannot kill
     *  a connection merely because the local socket is still connecting. */
    private static final int MAX_PENDING_BYTES = MAX_DECODED_FRAME_BYTES;

    private final VoxelPortPlugin plugin;
    private final String token;
    private final String publicHost;
    private final String serverHost;
    private final int serverPort;
    private final Logger log;

    private volatile int assignedPort = -1;
    private final ConcurrentHashMap<String, PlayerConn> connections = new ConcurrentHashMap<>();

    /** State for one proxied player. The local socket is opened asynchronously,
     *  so data may arrive from the relay before it is ready; that data is buffered
     *  in {@code pending} and flushed once the socket is ready. */
    private static final class PlayerConn {
        State state = State.CONNECTING;
        Socket socket;
        OutputStream output;
        boolean relayInitiatedClose;
        final java.util.ArrayDeque<byte[]> pending = new java.util.ArrayDeque<>();
        int pendingBytes;
    }

    private enum State {
        CONNECTING,
        OPEN,
        CLOSED
    }

    // Single-threaded scheduler purely for heartbeat + reconnect timing.
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(namedFactory("voxelport-sched"));
    // Per-connection pump threads. Each proxied player holds one thread for the
    // lifetime of its connection, so this must grow on demand, never a fixed pool.
    private final ExecutorService ioPool =
            Executors.newCachedThreadPool(namedFactory("voxelport-io"));

    private ScheduledFuture<?> pingTask;

    public RelayClient(VoxelPortPlugin plugin, String relayWs, String token,
                       String publicHost, String serverHost, int serverPort) {
        super(URI.create(relayWs));
        this.plugin     = plugin;
        this.token      = token;
        this.publicHost = publicHost;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.log        = plugin.getLogger();
        setConnectionLostTimeout(30);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        assignedPort = -1;

        JsonObject msg = new JsonObject();
        msg.addProperty("type",  "register");
        msg.addProperty("token", token);
        send(msg.toString());

        // Cancel any leftover ping task from a previous connection
        if (pingTask != null && !pingTask.isDone()) pingTask.cancel(false);

        pingTask = scheduler.scheduleAtFixedRate(() -> {
            if (isOpen()) send("{\"type\":\"ping\"}");
        }, 25, 25, TimeUnit.SECONDS);
    }

    @Override
    public void onMessage(String raw) {
        // Never let a malformed frame escape and tear down the socket.
        try {
            JsonObject msg = JsonParser.parseString(raw).getAsJsonObject();
            String type = optString(msg, "type");
            if (type == null) return;

            switch (type) {
                case "port": {
                    int port = optInt(msg, "port", -1);
                    if (port < 0) return;
                    assignedPort = port;
                    log.info("==========================================");
                    log.info("  VoxelPort is ready!");
                    log.info("  Players connect via: " + getPublicAddress());
                    log.info("==========================================");
                    break;
                }
                case "connect": {
                    String conn = optString(msg, "conn");
                    if (conn != null) openPlayerConnection(conn);
                    break;
                }
                case "data": {
                    String conn = optString(msg, "conn");
                    String data = optString(msg, "data");
                    if (conn != null && data != null) forwardToServer(conn, data);
                    break;
                }
                case "close": {
                    String conn = optString(msg, "conn");
                    if (conn != null) closePlayerConnection(conn);
                    break;
                }
                case "error": {
                    String message = optString(msg, "message");
                    log.warning("Relay error: " + (message != null ? message : "unknown"));
                    break;
                }
                default:
                    // Unknown frame type: ignore for forward compatibility.
            }
        } catch (Exception e) {
            log.warning("Ignoring malformed relay frame: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
        // Not used: relay sends text JSON only.
    }

    // A vanilla Minecraft client connected to the relay port opens a local TCP socket.
    private void openPlayerConnection(String connId) {
        // Enforce the connection cap before spending a thread. A reused connId is
        // allowed to replace its stale local connection without consuming a new slot.
        PlayerConn old = connections.get(connId);
        if (old == null && connections.size() >= MAX_CONNECTIONS) {
            log.warning("Connection cap (" + MAX_CONNECTIONS + ") reached: refusing " + connId);
            sendClose(connId);
            return;
        }

        // Register the connection slot immediately so that client data arriving
        // before the local socket finishes connecting is buffered, not dropped.
        final PlayerConn pc = new PlayerConn();
        old = connections.put(connId, pc);
        if (old != null) {
            teardown(old, true);
        }

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
                if (pc.state == State.CLOSED) return;
                pc.socket = socket;
            }

            socket.connect(new InetSocketAddress(serverHost, serverPort), LOCAL_CONNECT_TIMEOUT_MS);
            if (!isOpen()) {
                connections.remove(connId, pc);
                teardown(pc, false);
                if (!pc.relayInitiatedClose) sendClose(connId);
                return;
            }

            // Attach the socket and flush buffered bytes outside pc's monitor.
            // Later relay frames keep buffering until the backlog is fully drained.
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

                for (byte[] b : buffered) out.write(b);
                out.flush();
            }

            // Local server -> relay -> client
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[READ_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (!isOpen()) break;
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
            // normal close or local server unreachable
        } finally {
            connections.remove(connId, pc);
            teardown(pc, false);
            if (isOpen() && !pc.relayInitiatedClose) sendClose(connId);
        }
    }

    // Relay received data from vanilla client: write to local Minecraft server.
    private void forwardToServer(String connId, String base64Data) {
        if (base64Data.length() > MAX_FRAME_BASE64_CHARS) {
            log.warning("Oversized data frame for " + connId + ": dropping connection");
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
                    overflow = pc;
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

    @Override
    public void onClose(int code, String reason, boolean remote) {
        assignedPort = -1;
        if (pingTask != null) pingTask.cancel(false);

        // Tear down any sockets left over from this session.
        closeAllConnections();

        log.warning("Disconnected from relay (" + reason + "). Reconnecting in 10s...");

        if (!scheduler.isShutdown()) {
            scheduler.schedule(() -> {
                try { reconnect(); } catch (Exception e) {
                    log.warning("Reconnect failed: " + e.getMessage());
                }
            }, 10, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onError(Exception ex) {
        log.warning("Relay connection error: " + ex.getMessage());
    }

    public int     getAssignedPort()  { return assignedPort; }
    public boolean isReady()          { return assignedPort != -1; }
    public boolean isConnected()      { return isOpen(); }
    public String  getPublicAddress() { return publicHost + ":" + assignedPort; }

    public void disconnect() {
        if (pingTask != null) pingTask.cancel(false);
        scheduler.shutdownNow();
        ioPool.shutdownNow();
        closeAllConnections();
        close();
    }

    private void closeAllConnections() {
        for (PlayerConn pc : connections.values()) {
            teardown(pc, false);
        }
        connections.clear();
    }

    // --- helpers -----------------------------------------------------------

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
