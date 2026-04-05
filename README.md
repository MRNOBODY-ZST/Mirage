# Mirage — Minecraft Dimension Sync Mod

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-green)
![Fabric](https://img.shields.io/badge/Fabric_Mod-blue)
![Java](https://img.shields.io/badge/Java-21+-orange)

> Incremental dimension region sync between a main Minecraft server and one or more mirror servers.

---

## Requirements

| Component | Version |
|-----------|---------|
| Minecraft | 1.21.11 |
| Fabric Loader | ≥ 0.18.4 |
| Fabric API | 0.141.3+1.21.11 |
| Java | ≥ 21 |

---

## How It Works

Mirage establishes a persistent Netty TCP connection between the main server and mirror servers, using SHA-256 delta comparison to transfer only changed `.mca` region files.

```
Main Server                              Mirror Server
─────────────────────  TCP  ─────────────────────────────
1. Force-save and flush chunks
2. Compute region file SHA-256
3. Send hash list ──────────────────→
                                     4. Compare hashes, compute diff
5. Send .mca file chunks ────────→
6. Send sync-done signal ────────→
                                     7. Kick players → save chunks

Then: replace .mca files → clear region cache → resume world saving
```

---

## Installation

1. Place `mirage-1.0.0.jar` in the `mods/` folder of both the main and mirror servers
2. Start the server once — `config/mirage.json` is generated automatically
3. Configure each server (see below)
4. Restart the server

---

## Configuration

Config file: `config/mirage.json`, auto-generated on first launch.

**Main server** (`config/mirage.json`)

```json
{
  "mode": "main",
  "mainServer": {
    "port": 25566,
    "authToken": "your-secret-token"
  }
}
```

**Mirror server** (`config/mirage.json`)

```json
{
  "mode": "mirror",
  "mirrorServer": {
    "mainIp": "main-server-ip",
    "mainPort": 25566,
    "authToken": "your-secret-token",
    "targetDimensions": [
      "minecraft:overworld"
    ]
  }
}
```

### Fields

| Field | Description |
|-------|-------------|
| `mode` | `main` or `mirror` — sets the server role |
| `mainServer.port` | Sync listen port (separate from the MC port) |
| `mainServer.authToken` | Auth token — must match on both sides |
| `mirrorServer.mainIp` | Main server IP address |
| `mirrorServer.mainPort` | Main server sync port |
| `mirrorServer.authToken` | Auth token |
| `mirrorServer.targetDimensions` | List of dimensions to sync |

---

## Commands

All commands require `ADMINS` permission level.

| Command | Mode | Description |
|---------|------|-------------|
| `/mirage sync <dimension>` | main | Push hash list for a dimension to all connected mirrors |
| `/mirage pull` | mirror | Pull all configured target dimensions from the main server |
| `/mirage status` | both | Show current mode, connection state, and sync status |
| `/mirage reload` | both | Reload config (network changes require restart) |

---

## Sync Flow

### Mirror pull (recommended)

```
Mirror runs /mirage pull
  → Sends HASH_LIST_REQ to main
  → Main flushes saves, computes hashes, replies HASH_LIST_RESP
  → Mirror diffs hashes, sends FILE_SYNC_START for changed files
  → Main sends FILE_CHUNK segments
  → Main sends SYNC_DONE
  → Mirror kicks players → flushes → unloads chunks → replaces files → clears cache → resumes
```

### Main push

```
Main runs /mirage sync <dimension>
  → Main flushes saves, computes hashes
  → Broadcasts HASH_LIST_RESP to all mirrors
  → Flow continues as above
```

---

## Project Structure

```
src/main/java/xyz/tofumc/
├── Mirage.java                          # Mod entrypoint, lifecycle management
├── mixin/
│   ├── ServerWorldAccessor.java         # Access ServerLevel.noSave
│   ├── ServerChunkManagerAccessor.java  # Access ServerChunkCache.chunkMap
│   ├── ThreadedAnvilChunkStorageAccessor.java
│   ├── SimpleRegionStorageAccessor.java # Access SimpleRegionStorage.worker
│   ├── IOWorkerAccessor.java            # Access IOWorker.storage
│   └── RegionFileStorageAccessor.java   # Access RegionFileStorage.regionCache
└── mirage/
    ├── command/
    │   └── MirageCommand.java           # /mirage command registration
    ├── config/
    │   ├── ConfigManager.java           # Config file read/write
    │   └── MirageConfig.java            # Config data model
    ├── hash/
    │   ├── RegionHasher.java            # SHA-256 hashing for .mca files
    │   ├── DeltaComparator.java         # Hash diff computation
    │   └── FileTransferManager.java     # Chunked file transfer and atomic replace
    ├── network/
    │   ├── protocol/
    │   │   ├── MessageType.java         # Message type enum
    │   │   ├── MirageProtocol.java      # Binary protocol codec
    │   │   ├── MessagePayloads.java     # JSON message payload definitions
    │   │   └── MirageFrameDecoder.java  # TCP length-frame decoder
    │   ├── server/
    │   │   ├── MirageSyncServer.java    # Netty TCP server
    │   │   └── ServerHandler.java       # Server-side message handler
    │   └── client/
    │       ├── MirageSyncClient.java    # Netty TCP client (with reconnect)
    │       └── ClientHandler.java       # Client-side message handler
    ├── sync/
    │   ├── SyncState.java               # Sync state and cooldown control
    │   ├── MainServerTask.java          # Main server sync task
    │   └── MirrorApplyTask.java         # Mirror receive-and-apply task
    ├── util/
    │   ├── HashUtil.java                # SHA-256 utility
    │   └── DimensionPathUtil.java       # Dimension region path resolver
    └── world/
        ├── WorldSafetyManager.java      # Player kick, force-save, save state control
        └── ChunkUnloader.java           # Chunk unload and region cache clearing
```

---

## Build

```bash
./gradlew build
```

Output: `build/libs/mirage-1.0.0.jar`

---

## Notes

> [!WARNING]
> The `authToken` must be identical on both the main and mirror servers — connections will be rejected otherwise. Change the default value `change-me` to a secure random string before use.

> [!NOTE]
> Players in the target dimension on the mirror server will be kicked during sync. A **30-second cooldown** is enforced between syncs.

> [!NOTE]
> Changes to network port or IP require a server restart to take effect.

---

## License

**All Rights Reserved**

---

*xyz.tofumc · Mirage 1.0.0 · Minecraft 1.21.11*
