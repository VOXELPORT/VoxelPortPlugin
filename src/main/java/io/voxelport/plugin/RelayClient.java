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

    private static final int LOCAL_CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_BUFFER_BYTES = 65536;

    private final VoxelPortPlugin plugin;
    private final String token;
    private final String publicHost;
    private final String serverHost;
    private final int serverPort;
    private final Logger log;

    private volatile int assignedPort = -1;
    private final ConcurrentHashMap<String, Socket> connections = new ConcurrentHashMap<>();

    // Single-threaded scheduler purely for heartbeat + reconnect timing.
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(namedFactory("voxelport-sched"));
    // Per-connection pump threads. Each proxied player holds one thread for the
    // lifetime of its connection, so this must grow on demand — never a fixed pool.
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
                    log.info("══════════════════════════════════════════");
                    log.info("  VoxelPort is ready!");
                    log.info("  Players connect via: " + getPublicAddress());
                    log.info("══════════════════════════════════════════");
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
                    // Unknown frame type — ignore for forward compatibility.
            }
        } catch (Exception e) {
            log.warning("Ignoring malformed relay frame: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
        // Not used — relay sends text JSON only
    }

    // A vanilla Minecraft client connected to the relay port → open local TCP socket
    private void openPlayerConnection(String connId) {
        // Reject duplicates and enforce the connection cap before spending a thread.
        if (connections.containsKey(connId)) return;
        if (connections.size() >= MAX_CONNECTIONS) {
            log.warning("Connection cap (" + MAX_CONNECTIONS + ") reached — refusing " + connId);
            sendClose(connId);
            return;
        }

        ioPool.execute(() -> {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(serverHost, serverPort), LOCAL_CONNECT_TIMEOUT_MS);

                // Register only after a successful connect, and bail if we somehow
                // raced past the cap or the relay dropped meanwhile.
                if (connections.size() >= MAX_CONNECTIONS || !isOpen()) {
                    socket.close();
                    sendClose(connId);
                    return;
                }
                connections.put(connId, socket);

                // Local server → relay → client
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[READ_BUFFER_BYTES];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (!isOpen()) break;
                    String b64 = Base64.getEncoder().encodeToString(Arrays.copyOf(buf, n));
                    JsonObject out = new JsonObject();
                    out.addProperty("type", "data");
                    out.addProperty("conn", connId);
                    out.addProperty("data", b64);
                    send(out.toString());
                }
            } catch (IOException ignored) {
                // normal close or local server unreachable
            } finally {
                connections.remove(connId);
                try { socket.close(); } catch (IOException ignored) {}
                if (isOpen()) sendClose(connId);
            }
        });
    }

    // Relay received data from vanilla client → write to local Minecraft server
    private void forwardToServer(String connId, String base64Data) {
        if (base64Data.length() > MAX_FRAME_BASE64_CHARS) {
            log.warning("Oversized data frame for " + connId + " — dropping connection");
            closePlayerConnection(connId);
            return;
        }

        Socket socket = connections.get(connId);
        if (socket == null || socket.isClosed()) return;

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            return; // malformed base64 — ignore this frame
        }

        try {
            OutputStream out = socket.getOutputStream();
            out.write(decoded);
            out.flush();
        } catch (IOException e) {
            closePlayerConnection(connId);
        }
    }

    private void closePlayerConnection(String connId) {
        Socket socket = connections.remove(connId);
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
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
        for (Socket s : connections.values()) try { s.close(); } catch (IOException ignored) {}
        connections.clear();

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
        for (Socket s : connections.values()) try { s.close(); } catch (IOException ignored) {}
        connections.clear();
        close();
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
