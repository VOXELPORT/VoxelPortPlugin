<div align="center">

<img src="https://pr.opop.eu.org/Frame_2.png" width="100" alt="VoxelPort" />

# VoxelPort Paper Plugin

**Host your Paper server without port forwarding.**  
Install the plugin, add your Discord-issued token, and share a normal join address with vanilla players.

[![Discord](https://img.shields.io/badge/Discord-Join-5865f2?style=flat-square&logo=discord&logoColor=white)](https://discord.gg/Fbqx76j5US)
[![Website](https://img.shields.io/badge/Website-voxelport.in-00FFB2?style=flat-square)](https://www.voxelport.in)
[![Status](https://img.shields.io/badge/Status-Page-00FFB2?style=flat-square)](https://www.voxelport.in/#/status)
[![License: MIT](https://img.shields.io/badge/License-MIT-00FFB2?style=flat-square)](https://github.com/VOXELPORT)

</div>

---

## Overview

VoxelPort connects your Paper server to VoxelPort-operated relay infrastructure. Your server opens an outbound encrypted WebSocket connection, the relay assigns a public TCP port, and players join that address from vanilla Minecraft.

Players do not need to install any mod or client.

<p align="center">
  <img src="https://traz.arge.in/how-it-works.gif" alt="Animated VoxelPort relay flow" width="900" />
</p>

---

## Requirements

| Requirement | Version |
|---|---|
| Server software | Paper 1.21+ |
| Java | 21+ |
| Token | Free from the VoxelPort Discord |

---

## Quick Start

1. Join the [VoxelPort Discord](https://discord.gg/Fbqx76j5US).
2. Run `/gettoken` with the VoxelPort bot.
3. Download the VoxelPort plugin JAR from Hangar or Modrinth.
4. Put the JAR in your Paper server's `plugins/` folder.
5. Start the server once to generate the config.
6. Add your token to `plugins/VoxelPort/config.yml`.
7. Restart or run `/voxelport reload`.
8. Share the address shown by `/voxelport status`.

Keep your token secret. Treat it like a password for your relay slot.

---

## Configuration

The plugin config is created at:

```text
plugins/VoxelPort/config.yml
```

Example config:

```yaml
relay-ws: wss://voxelport.in
server-token: YOUR_TOKEN_HERE

public-host: play.voxelport.in

server-host: localhost
server-port: 25565
max-connections: 200
```

Replace `YOUR_TOKEN_HERE` with the token from Discord. Leave the relay and public host alone unless VoxelPort support tells you otherwise.

---

## Commands

| Command | Permission | Description |
|---|---|---|
| `/voxelport status` | `voxelport.bypass` or `voxelport.admin` | Shows relay state and current join address |
| `/voxelport reload` | `voxelport.admin` | Reloads config and reconnects |
| `/voxelport reconnect` | `voxelport.admin` | Forces a relay reconnect |
| `/voxelport stats` | `voxelport.admin` | Shows connection and traffic counters |

Operators have `voxelport.admin` by default.

---

## How It Works

1. The plugin opens an outbound WebSocket to the relay.
2. It registers using your Discord-issued server token.
3. The relay validates the token and assigns a stable TCP port.
4. Players join `play.voxelport.in:<assigned-port>` from vanilla Minecraft.
5. Minecraft traffic is bridged through the relay to your Paper server.

The relay is a bridge, not a game server. Your world, plugins, and player data stay on your own server.

The relay service and Discord bot are VoxelPort-operated infrastructure. Their internal implementation details are not documented as public source components here.

---

## Troubleshooting

**`server-token` is not set**  
Run `/gettoken` in Discord, add the token to `plugins/VoxelPort/config.yml`, then run `/voxelport reload`.

**Status shows disconnected**  
Make sure your host allows outbound HTTPS/WSS traffic to `voxelport.in` on port `443`.

**Players cannot join**  
Check that your Paper server is running and listening on the configured `server-port`, then share the exact address shown by `/voxelport status`.

**Token rejected**  
Run `/revoketoken` and `/gettoken` again in Discord, update the token in `config.yml`, then run `/voxelport reload`.

---

## Links

- [Discord](https://discord.gg/Fbqx76j5US)
- [Website](https://www.voxelport.in)
- [Status Page](https://www.voxelport.in/#/status)
- [Join Us](https://www.voxelport.in/#/join)
- [Paper Plugin on Hangar](https://hangar.papermc.io/voxelportt/VoxelPort)
- [Paper Plugin on Modrinth](https://modrinth.com/plugin/voxelportplugin)
- [VoxelPort GitHub](https://github.com/VOXELPORT)

---

## License

MIT. See the included license file.

VoxelPort is not affiliated with Mojang, Microsoft, PaperMC, Hangar, Modrinth, or Discord.
