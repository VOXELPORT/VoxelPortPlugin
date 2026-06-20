# VoxelPort Setup Guide and FAQ

VoxelPort lets you host a Paper Minecraft server publicly without port
forwarding. Your server connects outward to the VoxelPort relay over WebSocket,
and players join through a public VoxelPort address using a normal vanilla
Minecraft client.

This guide covers the full setup, including creating a Paper server from
scratch.

## What VoxelPort Does

Without VoxelPort, hosting a Minecraft server from home usually requires router
access, port forwarding, firewall changes, and sharing your home IP address.

With VoxelPort:

1. You run a normal Paper server on your machine.
2. You install the VoxelPort plugin.
3. The plugin opens an outbound connection to the VoxelPort relay.
4. The relay gives your server a public join address.
5. Players join that address with vanilla Minecraft.

Players do not install anything. Only the server owner installs the plugin.

## Requirements

You need:

- A computer, VPS, or hosting panel that can run a Paper Minecraft server.
- Paper 1.21 or newer.
- Java/JDK 21 or newer for Paper 1.21.x.
- Outbound HTTPS/WebSocket access to `voxelport.in` on port `443`.
- A VoxelPort token from the Discord bot.
- The VoxelPort plugin JAR.

Recommended starting hardware for a small friend server:

- 2 CPU cores.
- 4 GB RAM available to the Minecraft server.
- SSD storage.
- Stable upload speed.

VoxelPort removes port forwarding. It does not remove the normal CPU, RAM, disk,
and internet requirements of running a Minecraft server.

## Java Version

For VoxelPort's current Paper 1.21 target, install a 64-bit JDK 21 build.
Good options:

- Eclipse Temurin: https://adoptium.net/
- Amazon Corretto: https://aws.amazon.com/corretto/
- Microsoft Build of OpenJDK: https://www.microsoft.com/openjdk

Check Java after installing:

```bash
java -version
```

For Paper 1.21.x, the output should show Java 21 or newer and a 64-bit runtime.
If you intentionally use a newer Minecraft/Paper version, follow Paper's current
Java requirements:

- Paper getting started: https://docs.papermc.io/paper/getting-started/
- Paper Java install guide: https://docs.papermc.io/misc/java-install/

Do not use old Java 8 or Java 17 for a modern Paper 1.21 server.

## Step 1: Create a Paper Server

Skip this section if you already have a working Paper server.

### 1. Make a Server Folder

Create a clean folder for your server, for example:

```text
MinecraftServer/
```

Put all server files inside this folder.

### 2. Download Paper

Download the Paper server JAR from:

https://papermc.io/downloads/paper

Choose the Minecraft version you want to run. For best compatibility with this
plugin, use Paper 1.21 or newer.

Rename the downloaded file to:

```text
paper.jar
```

Move `paper.jar` into your server folder.

### 3. Create a Start Script

Windows: create `start.bat` in the server folder:

```bat
@echo off
java -Xms2G -Xmx4G -jar paper.jar --nogui
pause
```

Linux/macOS: create `start.sh` in the server folder:

```bash
#!/usr/bin/env bash
java -Xms2G -Xmx4G -jar paper.jar --nogui
```

Then make it executable on Linux/macOS:

```bash
chmod +x start.sh
```

What the memory flags mean:

- `-Xms2G` starts the server with 2 GB RAM.
- `-Xmx4G` allows the server to use up to 4 GB RAM.
- `--nogui` disables the old graphical server window.

Adjust the RAM values based on your machine. Do not allocate all system RAM to
Minecraft.

### 4. First Run and EULA

Run the start script once.

The server will stop and create `eula.txt`. Open it and change:

```properties
eula=false
```

to:

```properties
eula=true
```

Only do this if you agree to the Minecraft EULA.

Run the server again. It should generate folders such as:

```text
plugins/
world/
server.properties
```

### 5. Basic server.properties Checks

Open `server.properties` and check:

```properties
server-port=25565
online-mode=true
```

You can change `server-port`, but if you do, you must also set the same port in
VoxelPort's `server-port` config.

## Step 2: Install VoxelPort

### 1. Download the Plugin

Download the VoxelPort plugin from one of the official plugin pages:

- Hangar: https://hangar.papermc.io/voxelportt/VoxelPort
- Modrinth: https://modrinth.com/plugin/voxelportplugin

Put the plugin JAR into your server's `plugins/` folder.

Example:

```text
MinecraftServer/
  paper.jar
  plugins/
    voxelport-plugin.jar
```

### 2. Start Once to Generate Config

Start the Paper server once, then stop it.

VoxelPort should create:

```text
plugins/VoxelPort/config.yml
```

### 3. Get a VoxelPort Token

Join the VoxelPort Discord and run:

https://discord.gg/Fbqx76j5US

```text
/gettoken
```

Copy the token the bot gives you.

Treat the token like a password. Do not post it in screenshots, GitHub issues,
Discord messages, or public config files.

### 4. Configure VoxelPort

Open:

```text
plugins/VoxelPort/config.yml
```

Set at least:

```yaml
relay-ws: wss://voxelport.in
server-token: YOUR_TOKEN_HERE
public-host: play.voxelport.in
server-host: localhost
server-port: 25565
allow-insecure-ws: false
max-connections: 200
```

Replace `YOUR_TOKEN_HERE` with your real token.

If your Paper server runs on a different local port, update `server-port` to
match `server.properties`.

Most users should leave:

```yaml
server-host: localhost
server-port: 25565
allow-insecure-ws: false
```

### 5. Start the Server

Start Paper again.

In the console, look for a VoxelPort ready message. Then run this in the server
console or in-game as an operator:

```text
/voxelport status
```

It should show:

- Connected status.
- Relay latency.
- The public address players can use.

Share the public address with players.

## Commands

| Command | Permission | Purpose |
| --- | --- | --- |
| `/voxelport status` | `voxelport.bypass` or `voxelport.admin` | Shows relay connection state and public join address. |
| `/voxelport reload` | `voxelport.admin` | Reloads `config.yml` and reconnects. |
| `/voxelport reconnect` | `voxelport.admin` | Forces a relay reconnect. |
| `/voxelport stats` | `voxelport.admin` | Shows active connections, total connects, uptime, and traffic counters. |

Operators have `voxelport.admin` by default.

## Config Reference

```yaml
# Secure WebSocket address of the VoxelPort relay.
relay-ws: wss://voxelport.in

# Your private server token from /gettoken.
server-token: YOUR_TOKEN_HERE

# Public host shown by /voxelport status.
public-host: play.voxelport.in

# Local Minecraft server target.
server-host: localhost
server-port: 25565

# Local testing only. Keep false for real servers.
allow-insecure-ws: false

# Maximum proxied player connections for this plugin session.
max-connections: 200

# Reconnect timing.
reconnect-base-delay-seconds: 10
reconnect-max-delay-seconds: 300
reconnect-jitter-seconds: 5

# Kick players if the relay disconnects unexpectedly.
kick-players-on-disconnect: true
kick-message: "&cServer relay disconnected - please reconnect."

# Optional Discord webhook for relay connect/disconnect events.
discord-webhook-url: ""
```

## How to Verify Everything Works

1. Start the Paper server.
2. Run `/voxelport status`.
3. Confirm it says connected.
4. Copy the public address.
5. Open Minecraft on another computer or network if possible.
6. Go to Multiplayer, then Add Server.
7. Paste the VoxelPort address.
8. Join.

If testing from the same computer fails, ask a friend to test from another
network. Some local network setups behave differently when connecting back
through a public relay.

## Troubleshooting

### The plugin says `server-token` is not set

You did not replace `YOUR_TOKEN_HERE` in `plugins/VoxelPort/config.yml`.

Fix:

1. Get a token with `/gettoken` in Discord.
2. Paste it into `server-token`.
3. Run `/voxelport reload` or restart the server.

### Token rejected by relay

Possible causes:

- The token was copied incorrectly.
- The token was revoked.
- You generated a new token and left the old one in config.
- Extra spaces or quotes were added around the token.

Fix:

1. Run `/gettoken` again in Discord.
2. Copy the full new token.
3. Replace the old token in `config.yml`.
4. Run `/voxelport reload`.

### Status says disconnected

Check:

- Your internet connection is working.
- The server can reach `voxelport.in` on port `443`.
- `relay-ws` is set to `wss://voxelport.in`.
- `allow-insecure-ws` is `false`.
- The VoxelPort relay is online.

Run:

```text
/voxelport reconnect
```

Then check the server console for the actual error message.

### Players see the server but cannot join

Check:

- The Paper server is fully started.
- `server-port` in VoxelPort matches `server-port` in `server.properties`.
- No other plugin is blocking logins.
- The server is not full.
- The Minecraft client version is compatible with your Paper server version.

### Players get high ping

VoxelPort relays traffic through the current relay network. If the relay is far
from the player or the server, latency will be higher.

VoxelPort is designed to make hosting simple and private. It cannot beat the
latency of a nearby dedicated hosting provider in every region.

### The public address changed

VoxelPort tokens are intended to keep a stable assigned port. If your address
changes unexpectedly:

- Make sure you are using the same Discord account/token owner.
- Make sure you did not switch to a different token source.
- Run `/voxelport status` and share the latest address.
- Ask in Discord if you believe the stable port was lost.

### The server works locally but not through VoxelPort

If `localhost:25565` works but VoxelPort does not:

- Confirm the plugin is connected with `/voxelport status`.
- Confirm `server-host: localhost`.
- Confirm `server-port` matches your actual Paper port.
- Check for firewall or antivirus software blocking local Java sockets.
- Check the console for VoxelPort errors.

### `java` is not recognized

Java is not installed correctly or is not on your PATH.

Fix:

1. Install a 64-bit JDK.
2. Close and reopen your terminal.
3. Run `java -version`.
4. If it still fails, reinstall Java and enable any installer option that adds
   Java to PATH.

### The server closes instantly

Common causes:

- You did not accept `eula.txt`.
- You are using the wrong Java version.
- The Paper JAR filename in your start script does not match the real filename.
- Another server is already using the same port.

Read the console output from top to bottom. The real error is usually printed
before the window closes.

## FAQ

### Do players need the VoxelPort plugin?

No. Players use vanilla Minecraft and join with the public VoxelPort address.

### Do I need to port forward?

No. Your server opens an outbound connection to the VoxelPort relay. You do not
need to log into your router or expose port `25565`.

### Does VoxelPort hide my home IP?

Yes. Players connect to the VoxelPort relay address, not directly to your home
IP.

### Is my token secret?

Yes. Treat it like a password for your relay slot. If it leaks, rotate it with
the Discord bot and update `config.yml`.

### Can I use an existing world?

Yes. VoxelPort only changes how players connect. It does not change your world
files.

### Can I use VoxelPort on a paid Minecraft host?

Usually yes, if the host allows custom Paper plugins and outbound WebSocket
connections to `voxelport.in:443`.

If your host blocks outbound connections, VoxelPort cannot connect.

### Can I use Spigot or Bukkit?

VoxelPort is built for Paper. Use Paper 1.21 or newer.

Paper is generally a drop-in replacement for Spigot servers, but always back up
your server before switching software.

### Can I use Fabric, Forge, or vanilla server jars?

This plugin is for Paper. It will not load on vanilla, Fabric, or Forge server
jars.

### Can Bedrock players join?

VoxelPort relays Minecraft Java server traffic. Bedrock support depends on your
own server setup, such as Geyser/Floodgate. VoxelPort does not replace or
configure those plugins.

### Does VoxelPort make my server faster?

No. VoxelPort makes your server reachable without port forwarding. Performance
still depends on your server hardware, upload speed, player count, plugins, and
distance to the relay.

### Can I run multiple servers?

Each public server should have its own token/relay slot. Do not reuse one token
for multiple servers at the same time.

### Can I publish my config.yml?

Do not publish a real `server-token`. If you share config examples, replace the
token with `YOUR_TOKEN_HERE`.

### Does `/reload` work?

Avoid Minecraft's global `/reload`. It can break plugins. Use:

```text
/voxelport reload
```

or restart the server cleanly.

### Where do I get help?

Use the VoxelPort Discord for tokens, support, updates, and bug reports:

https://discord.gg/Fbqx76j5US

When asking for help, include:

- Paper version.
- Java version from `java -version`.
- VoxelPort plugin version.
- The output of `/voxelport status`.
- Relevant console errors.

Do not include your token.

## Quick Checklist

- Java 21+ installed.
- Paper 1.21+ starts successfully.
- `eula=true`.
- VoxelPort JAR is inside `plugins/`.
- `plugins/VoxelPort/config.yml` exists.
- `server-token` is set.
- `server-port` matches `server.properties`.
- Server can reach `wss://voxelport.in`.
- `/voxelport status` says connected.
- Players use the address shown by `/voxelport status`.
