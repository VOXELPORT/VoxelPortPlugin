package io.voxelport.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class RelayClient extends WebSocketClient {

    private final VoxelPortPlugin plugin;
    private final String token;
    private final String serverHost;
    private final int serverPort;
    private final Logger log;

    private volatile int assignedPort = -1;
    private final ConcurrentHashMap<String, Socket> connections = new ConcurrentHashMap<>();
    private ScheduledFuture<?> pingTask;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    public RelayClient(VoxelPortPlugin plugin, String relayWs, String token,
                       String serverHost, int serverPort) {
        super(URI.create(relayWs));
        this.plugin     = plugin;
        this.token      = token;
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
        JsonObject msg;
        try { msg = JsonParser.parseString(raw).getAsJsonObject(); } catch (Exception e) { return; }

        if (!msg.has("type")) return;
        String type = msg.get("type").getAsString();

        switch (type) {
            case "port":
                assignedPort = msg.get("port").getAsInt();
                log.info("══════════════════════════════════════════");
                log.info("  VoxelPort is ready!");
                log.info("  Players connect via: voxelportrelay.qzz.io:" + assignedPort);
                log.info("══════════════════════════════════════════");
                break;
            case "connect":
                openPlayerConnection(msg.get("conn").getAsString());
                break;
            case "data":
                forwardToServer(msg.get("conn").getAsString(), msg.get("data").getAsString());
                break;
            case "close":
                closePlayerConnection(msg.get("conn").getAsString());
                break;
            case "error":
                log.warning("Relay error: " + msg.get("message").getAsString());
                break;
        }
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
        // Not used — relay sends text JSON only
    }

    // A vanilla Minecraft client connected to the relay port → open local TCP socket
    private void openPlayerConnection(String connId) {
        scheduler.execute(() -> {
            try {
                Socket socket = new Socket(serverHost, serverPort);
                connections.put(connId, socket);

                // Local server → relay → client
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[65536];
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
                // normal close
            } finally {
                connections.remove(connId);
                if (isOpen()) {
                    JsonObject close = new JsonObject();
                    close.addProperty("type", "close");
                    close.addProperty("conn", connId);
                    send(close.toString());
                }
            }
        });
    }

    // Relay received data from vanilla client → write to local Minecraft server
    private void forwardToServer(String connId, String base64Data) {
        Socket socket = connections.get(connId);
        if (socket == null || socket.isClosed()) return;
        try {
            OutputStream out = socket.getOutputStream();
            out.write(Base64.getDecoder().decode(base64Data));
            out.flush();
        } catch (IOException e) {
            connections.remove(connId);
        }
    }

    private void closePlayerConnection(String connId) {
        Socket socket = connections.remove(connId);
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        assignedPort = -1;
        if (pingTask != null) pingTask.cancel(false);
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

    public int     getAssignedPort() { return assignedPort; }
    public boolean isReady()         { return assignedPort != -1; }
    public boolean isConnected()     { return isOpen(); }

    public void disconnect() {
        if (pingTask != null) pingTask.cancel(false);
        scheduler.shutdownNow();
        for (Socket s : connections.values()) try { s.close(); } catch (IOException ignored) {}
        connections.clear();
        close();
    }
}
