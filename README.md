# VoxelPort — Paper Plugin

> Host your Minecraft server without touching your router. Players join with vanilla Minecraft — no mods, no downloads, nothing extra on their end.

---

## What it does

VoxelPort connects your Paper server to the VoxelPort relay network. The relay gives your server a public address like `play.voxelport.in:25312`. Anyone can paste that into Multiplayer → Add Server in vanilla Minecraft and connect — even if your server is behind a home router with no port forwarding.

```
Player (vanilla client)
        │  play.voxelport.in:25312
        ▼
VoxelPort Relay  ←── secure WebSocket (wss) ──→  VoxelPort Plugin
                                              │
                                       localhost:25565
                                              │
                                       Your Paper Server
```

The plugin handles everything. Your server never needs an open port.

---

## Requirements

| | |
|--|--|
| **Server software** | Paper 1.21 or newer |
| **Java** | 21+ |
| **Token** | Free — from the VoxelPort Discord |

---

## Installation

### 1. Get a token

Join the [VoxelPort Discord](https://discord.gg/EuDMWUuGpp) and run `/gettoken` in any channel. Copy the token you receive.

### 2. Install the plugin

Download `voxelport-plugin.jar` from [Hangar](https://hangar.papermc.io/voxelportt/VoxelPort) and drop it into your server's `plugins/` folder.

### 3. Configure

Start the server once to generate the config, then stop it. Open `plugins/VoxelPort/config.yml`:

```yaml
relay-ws: wss://voxelport.in
server-token: YOUR_TOKEN_HERE

public-host: play.voxelport.in

server-host: localhost
server-port: 25565
```

Replace `YOUR_TOKEN_HERE` with your token. Leave everything else as-is unless your Minecraft server runs on a non-standard port.

> **Keep your token secret.** It's effectively a password for your slot on the relay. Don't paste it in screenshots, issues, or public repos.

### 4. Start your server

```
[VoxelPort] ══════════════════════════════════════════
[VoxelPort]   VoxelPort is ready!
[VoxelPort]   Players connect via: play.voxelport.in:25312
[VoxelPort] ══════════════════════════════════════════
```

Share that address. Done.

---

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/voxelport status` | `voxelport.admin` | Shows connection status and current join address |
| `/voxelport reload` | `voxelport.admin` | Reloads config.yml and reconnects to the relay |

Both commands require operator or the `voxelport.admin` permission node.

---

## Configuration reference

```yaml
# Secure WebSocket address of the VoxelPort relay (wss:// = encrypted)
relay-ws: wss://voxelport.in

# Your server token — get one from the VoxelPort Discord with /gettoken
server-token: YOUR_TOKEN_HERE

# Public address players type into Minecraft (shown by /voxelport status)
public-host: play.voxelport.in

# Where the plugin forwards player connections to (your actual Minecraft server)
server-host: localhost
server-port: 25565
```

---

## How it works (technical)

1. On startup the plugin opens a secure WebSocket (`wss://`) connection to the relay and sends a `register` frame with the server token
2. The relay validates the token and responds with an assigned TCP port (e.g. `25312`)
3. When a vanilla client connects to `play.voxelport.in:25312`, the relay sends a `connect` frame to the plugin
4. The plugin opens a TCP socket to `localhost:25565` and bridges raw data between the relay and the local server using Base64-encoded JSON frames
5. The relay closes the TCP connection when the client disconnects, and the plugin tears down the local socket

The plugin auto-reconnects every 10 seconds if the relay connection drops. It caps concurrent proxied connections (200) and rejects oversized frames to protect the local server.

---

## Troubleshooting

**"server-token is not set" — plugin disables itself**
→ You left `YOUR_TOKEN_HERE` in config.yml. Add a real token and run `/voxelport reload`.

**Status shows Disconnected**
→ Your server needs outbound HTTPS/WSS access to `voxelport.in` (port 443). Most home networks and hosts allow this by default — check with your provider if not.

**Players can't connect**
→ Verify your Paper server is running and listening on the port set in `server-port`. Check `server.properties` → `server-port`.

**Token rejected by relay**
→ The token may have been revoked. Run `/revoketoken` then `/gettoken` again in the Discord, update config.yml, and run `/voxelport reload`.

**Address changes every restart**
→ Expected behavior. The relay assigns a port when the plugin connects. Run `/voxelport status` after each restart and share the new address.

---

## Building from source

```bash
git clone https://github.com/VOXELPORT/VoxelPortPlugin
cd VoxelPortPlugin
./gradlew build
```

Output: `build/libs/voxelport-plugin-1.0.0.jar`

Requires Java 21 and an internet connection to download dependencies.

---

## Links

- [Hangar (PaperMC)](https://hangar.papermc.io/voxelportt/VoxelPort) — download
- [Modrinth](https://modrinth.com/plugin/voxelportplugin) — download
- [Discord](https://discord.gg/EuDMWUuGpp) — get your token, support, updates
- [VoxelPort Fabric Mod](https://github.com/trazhub/VoxelPort) — for sharing singleplayer worlds

---

## License

MIT — see [LICENSE](LICENSE)
