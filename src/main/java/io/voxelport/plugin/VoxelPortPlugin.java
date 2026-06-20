package io.voxelport.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

public class VoxelPortPlugin extends JavaPlugin {

    private RelayClient relayClient;
    private HttpClient  httpClient;
    private Instant     pluginStartTime;

    // ---------------------------------------------------------------------------
    // Lifecycle

    @Override
    public void onEnable() {
        pluginStartTime = Instant.now();
        httpClient = HttpClient.newHttpClient();
        saveDefaultConfig();
        startRelay();
    }

    @Override
    public void onDisable() {
        if (relayClient != null) {
            relayClient.disconnect();
            relayClient = null;
        }
        if (httpClient != null) {
            httpClient.close();
        }
        getLogger().info("VoxelPort disconnected.");
    }

    private boolean startRelay() {
        String relayWs        = getConfig().getString ("relay-ws",                    "wss://voxelport.in");
        String token          = getConfig().getString ("server-token",                "");
        String publicHost     = getConfig().getString ("public-host",                 "play.voxelport.in");
        String serverHost     = getConfig().getString ("server-host",                 "localhost");
        int    serverPort     = getConfig().getInt    ("server-port",                 25565);
        int    maxConns       = getConfig().getInt    ("max-connections",              200);
        boolean allowInsecure = getConfig().getBoolean("allow-insecure-ws",           false);
        int    baseDelay      = getConfig().getInt    ("reconnect-base-delay-seconds", 10);
        int    maxDelay       = getConfig().getInt    ("reconnect-max-delay-seconds",  300);
        int    jitter         = getConfig().getInt    ("reconnect-jitter-seconds",     5);

        if (token.isEmpty() || token.equals("YOUR_TOKEN_HERE")) {
            getLogger().severe("No server-token set in config.yml — get one from the VoxelPort Discord bot with /gettoken");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        if (relayWs.startsWith("ws://") && !allowInsecure) {
            getLogger().severe(
                    "relay-ws uses ws:// (unencrypted) but allow-insecure-ws is false. " +
                    "Set allow-insecure-ws: true in config.yml for local testing ONLY — never in production.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        if (relayWs.startsWith("ws://")) {
            getLogger().warning("relay-ws uses ws:// — token travels in plaintext. NEVER use in production.");
        }

        RelayClient.Config cfg = new RelayClient.Config(
                relayWs, token, publicHost, serverHost, serverPort,
                maxConns, baseDelay, maxDelay, jitter);

        relayClient = new RelayClient(this, cfg);
        relayClient.connect();
        getLogger().info("Connecting to VoxelPort relay...");
        return true;
    }

    // ---------------------------------------------------------------------------
    // Relay event callbacks (called from RelayClient's WebSocket threads)

    void onRelayReady(int port) {
        String webhook = getConfig().getString("discord-webhook-url", "");
        if (!webhook.isEmpty()) {
            RelayClient rc = relayClient;
            String address = (rc != null) ? rc.getPublicAddress() : "unknown";
            sendWebhook(webhook,
                    "VoxelPort Connected",
                    "Server is now reachable via `" + address + "`",
                    0x00AA00);
        }
    }

    void onRelayDisconnected(String reason) {
        if (getConfig().getBoolean("kick-players-on-disconnect", true)) {
            String raw = getConfig().getString("kick-message",
                    "&cServer relay disconnected — please reconnect.");
            Component msg = LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
            getServer().getScheduler().runTask(this, () ->
                    getServer().getOnlinePlayers().forEach(p -> p.kick(msg)));
        }

        String webhook = getConfig().getString("discord-webhook-url", "");
        if (!webhook.isEmpty()) {
            sendWebhook(webhook,
                    "VoxelPort Disconnected",
                    "Relay disconnected: " + reason + ". Auto-reconnecting...",
                    0xAA0000);
        }
    }

    // ---------------------------------------------------------------------------
    // Commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("voxelport")) return false;

        String sub = (args.length > 0) ? args[0].toLowerCase() : "status";

        boolean isAdmin  = sender.hasPermission("voxelport.admin");
        boolean isBypass = sender.hasPermission("voxelport.bypass");

        // bypass grants status only; everything else requires admin
        if (!isAdmin && !(isBypass && sub.equals("status"))) {
            sender.sendMessage("§c[VoxelPort] You don't have permission to use this command.");
            return true;
        }

        switch (sub) {
            case "status" -> {
                boolean connected = relayClient != null && relayClient.isConnected();
                boolean ready     = relayClient != null && relayClient.isReady();
                sender.sendMessage("§a[VoxelPort] §fStatus: " + (connected ? "§aConnected" : "§cDisconnected"));
                if (ready) {
                    sender.sendMessage("§a[VoxelPort] §fPlayers connect via: §b" + relayClient.getPublicAddress());
                    long latency = relayClient.getLatencyMs();
                    sender.sendMessage("§a[VoxelPort] §fRelay latency: §e"
                            + (latency < 0 ? "measuring..." : latency + " ms"));
                } else if (connected) {
                    sender.sendMessage("§a[VoxelPort] §fWaiting for port assignment...");
                }
            }

            case "reload" -> {
                if (relayClient != null) {
                    relayClient.disconnect();
                    relayClient = null;
                }
                reloadConfig();
                if (startRelay()) {
                    sender.sendMessage("§a[VoxelPort] §fConfig reloaded and reconnecting...");
                } else {
                    sender.sendMessage("§c[VoxelPort] Config error — check the server log.");
                }
            }

            case "reconnect" -> {
                if (relayClient == null) {
                    sender.sendMessage("§c[VoxelPort] Plugin is disabled. Use §f/voxelport reload §cto re-enable.");
                    return true;
                }
                relayClient.reconnectNow();
                sender.sendMessage("§a[VoxelPort] §fForcing reconnect...");
            }

            case "stats" -> {
                if (relayClient == null) {
                    sender.sendMessage("§c[VoxelPort] Not running.");
                    return true;
                }
                Duration uptime  = Duration.between(pluginStartTime, Instant.now());
                long     latency = relayClient.getLatencyMs();
                sender.sendMessage("§a[VoxelPort] §6=== Stats ===");
                sender.sendMessage("§a[VoxelPort] §fStatus:         "
                        + (relayClient.isConnected() ? "§aConnected" : "§cDisconnected"));
                sender.sendMessage("§a[VoxelPort] §fLatency:        §e"
                        + (latency < 0 ? "measuring..." : latency + " ms"));
                sender.sendMessage("§a[VoxelPort] §fActive players: §e" + relayClient.getActiveConnections());
                sender.sendMessage("§a[VoxelPort] §fTotal connects: §e" + relayClient.getTotalConnections());
                sender.sendMessage("§a[VoxelPort] §fUptime:         §e"
                        + uptime.toHours() + "h "
                        + uptime.toMinutesPart() + "m "
                        + uptime.toSecondsPart() + "s");
                sender.sendMessage("§a[VoxelPort] §fBytes in:       §e" + formatBytes(relayClient.getBytesFromServer()));
                sender.sendMessage("§a[VoxelPort] §fBytes out:      §e" + formatBytes(relayClient.getBytesToServer()));
            }

            default -> sender.sendMessage("§a[VoxelPort] §fUsage: /voxelport [status|reload|reconnect|stats]");
        }
        return true;
    }

    // ---------------------------------------------------------------------------
    // Discord webhook

    private void sendWebhook(String url, String title, String description, int color) {
        String payload = String.format(
                "{\"username\":\"VoxelPort\",\"embeds\":[{\"title\":\"%s\",\"description\":\"%s\",\"color\":%d}]}",
                escapeJson(title), escapeJson(description), color);

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .timeout(Duration.ofSeconds(10))
                        .build();
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                getLogger().warning("Discord webhook failed: " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------------------
    // Helpers

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1_024L)             return bytes + " B";
        if (bytes < 1_048_576L)         return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824L)     return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%.2f GB", bytes / 1_073_741_824.0);
    }
}
