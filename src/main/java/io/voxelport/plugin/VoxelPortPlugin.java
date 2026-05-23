package io.voxelport.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class VoxelPortPlugin extends JavaPlugin {

    private RelayClient relayClient;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String relayWs    = getConfig().getString("relay-ws",     "ws://voxelportrelay.qzz.io:2526");
        String token      = getConfig().getString("server-token", "");
        String serverHost = getConfig().getString("server-host",  "localhost");
        int    serverPort = getConfig().getInt   ("server-port",  25565);

        if (token.isEmpty() || token.equals("YOUR_TOKEN_HERE")) {
            getLogger().severe("No server-token set in config.yml — get one from the VoxelPort Discord bot with /gettoken");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        relayClient = new RelayClient(this, relayWs, token, serverHost, serverPort);
        relayClient.connect();
        getLogger().info("Connecting to VoxelPort relay...");
    }

    @Override
    public void onDisable() {
        if (relayClient != null) relayClient.disconnect();
        getLogger().info("VoxelPort disconnected.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("voxelport")) return false;

        String sub = args.length > 0 ? args[0].toLowerCase() : "status";

        if (sub.equals("status")) {
            boolean connected = relayClient != null && relayClient.isConnected();
            boolean ready     = relayClient != null && relayClient.isReady();
            int     port      = relayClient != null ? relayClient.getAssignedPort() : -1;

            sender.sendMessage("§a[VoxelPort] §fStatus: " + (connected ? "§aConnected" : "§cDisconnected"));
            if (ready) {
                sender.sendMessage("§a[VoxelPort] §fPlayers connect via: §bvoxelportrelay.qzz.io:" + port);
            } else if (connected) {
                sender.sendMessage("§a[VoxelPort] §fWaiting for port assignment...");
            }
            return true;
        }

        if (sub.equals("reload")) {
            if (relayClient != null) relayClient.disconnect();
            reloadConfig();

            String relayWs    = getConfig().getString("relay-ws",     "ws://voxelportrelay.qzz.io:2526");
            String token      = getConfig().getString("server-token", "");
            String serverHost = getConfig().getString("server-host",  "localhost");
            int    serverPort = getConfig().getInt   ("server-port",  25565);

            if (token.isEmpty() || token.equals("YOUR_TOKEN_HERE")) {
                sender.sendMessage("§c[VoxelPort] No server-token in config — cannot reconnect.");
                return true;
            }

            relayClient = new RelayClient(this, relayWs, token, serverHost, serverPort);
            relayClient.connect();
            sender.sendMessage("§a[VoxelPort] §fReloaded and reconnecting...");
            return true;
        }

        return false;
    }
}
