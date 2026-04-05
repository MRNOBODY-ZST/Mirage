# Mirage — Minecraft 维度同步模组

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-green)
![Fabric](https://img.shields.io/badge/Fabric_Mod-blue)
![Java](https://img.shields.io/badge/Java-21+-orange)

> Minecraft 维度地图增量同步 · 主服与镜像服之间的实时区块传输

---

## ⚙ 运行环境

| 组件 | 版本要求 |
|------|---------|
| Minecraft | 1.21.11 |
| Fabric Loader | ≥ 0.18.4 |
| Fabric API | 0.141.3+1.21.11 |
| Java | ≥ 21 |

---

## ⟳ 工作原理

Mirage 通过 Netty TCP 长连接在主服与镜像服之间建立持久通道，使用 SHA-256 增量对比来只传输发生变化的 `.mca` 区域文件。

```
主服 (main)                              镜像服 (mirror)
─────────────────────  TCP  ─────────────────────────────
1. 强制保存并 flush 区块
2. 计算 region 文件 SHA-256
3. 发送 hash 列表 ──────────────────→
                                     4. 对比本地 hash，计算差异
5. 分片发送 .mca 文件 ───────────→
6. 发送同步完成信号 ─────────────→
                                     7. 踢出玩家 → 保存区块

后续：替换 .mca 文件 → 清空 region 缓存 → 恢复世界保存
```

---

## ↓ 安装

1. 将构建产物 `mirage-1.0.0.jar` 放入主服和镜像服的 `mods/` 目录
2. 启动一次服务器，自动生成配置文件 `config/mirage.json`
3. 分别配置主服和镜像服（见下方配置说明）
4. 重启服务器

---

## ≡ 配置

配置文件位于 `config/mirage.json`，首次启动自动生成。

**主服配置** (`config/mirage.json · main`)

```json
{
  "mode": "main",
  "mainServer": {
    "port": 25566,
    "authToken": "your-secret-token"
  }
}
```

**镜像服配置** (`config/mirage.json · mirror`)

```json
{
  "mode": "mirror",
  "mirrorServer": {
    "mainIp":   "主服IP地址",
    "mainPort": 25566,
    "authToken": "your-secret-token",
    "targetDimensions": [
      "minecraft:overworld"
    ]
  }
}
```

### 配置字段说明

| 字段 | 说明 |
|------|------|
| `mode` | `main` 或 `mirror`，决定当前服务器角色 |
| `mainServer.port` | 主服同步监听端口（独立于 MC 端口） |
| `mainServer.authToken` | 身份验证令牌，主服和镜像服必须一致 |
| `mirrorServer.mainIp` | 主服 IP 地址 |
| `mirrorServer.mainPort` | 主服同步端口 |
| `mirrorServer.authToken` | 身份验证令牌 |
| `mirrorServer.targetDimensions` | 要同步的维度列表 |

---

## / 命令

所有命令以 `/mirage` 开头，需要 `ADMINS` 级别权限。

| 命令 | 模式 | 说明 |
|------|------|------|
| `/mirage sync <dimension>` | main | 推送指定维度的 hash 列表到所有已连接的镜像服 |
| `/mirage pull` | mirror | 从主服拉取配置中所有目标维度的数据 |
| `/mirage status` | 通用 | 显示当前模式、连接状态、同步状态 |
| `/mirage reload` | 通用 | 重新加载配置文件（网络参数变更需重启） |

---

## ↕ 同步流程

### 镜像服拉取（推荐）

```
镜像服执行 /mirage pull
  → 向主服发送 HASH_LIST_REQ
  → 主服 flush 存档，计算 hash，回复 HASH_LIST_RESP
  → 镜像服对比 hash，发送 FILE_SYNC_START 请求差异文件
  → 主服分片发送 FILE_CHUNK
  → 主服发送 SYNC_DONE
  → 镜像服踢出玩家 → flush → 释放区块 → 替换文件 → 清空缓存 → 恢复
```

### 主服推送

```
主服执行 /mirage sync <dimension>
  → 主服 flush 存档，计算 hash
  → 广播 HASH_LIST_RESP 到所有镜像服
  → 后续流程同上
```

---

## ⊞ 项目结构

```
src/main/java/xyz/tofumc/
├── Mirage.java                          # 模组入口，生命周期管理
├── mixin/
│   ├── ServerWorldAccessor.java         # 访问 ServerLevel.noSave
│   ├── ServerChunkManagerAccessor.java  # 访问 ServerChunkCache.chunkMap
│   ├── ThreadedAnvilChunkStorageAccessor.java
│   ├── SimpleRegionStorageAccessor.java # 访问 SimpleRegionStorage.worker
│   ├── IOWorkerAccessor.java            # 访问 IOWorker.storage
│   └── RegionFileStorageAccessor.java   # 访问 RegionFileStorage.regionCache
└── mirage/
    ├── command/
    │   └── MirageCommand.java           # /mirage 命令注册与执行
    ├── config/
    │   ├── ConfigManager.java           # 配置文件读写
    │   └── MirageConfig.java            # 配置数据模型
    ├── hash/
    │   ├── RegionHasher.java            # .mca 文件 SHA-256 计算
    │   ├── DeltaComparator.java         # hash 差异对比
    │   └── FileTransferManager.java     # 文件分片读写与原子替换
    ├── network/
    │   ├── protocol/
    │   │   ├── MessageType.java         # 消息类型枚举
    │   │   ├── MirageProtocol.java      # 二进制协议编解码
    │   │   ├── MessagePayloads.java     # JSON 消息体定义
    │   │   └── MirageFrameDecoder.java  # TCP 长度帧解码器
    │   ├── server/
    │   │   ├── MirageSyncServer.java    # Netty TCP 服务端
    │   │   └── ServerHandler.java       # 服务端消息处理
    │   └── client/
    │       ├── MirageSyncClient.java    # Netty TCP 客户端（含断线重连）
    │       └── ClientHandler.java       # 客户端消息处理
    ├── sync/
    │   ├── SyncState.java               # 同步状态与冷却控制
    │   ├── MainServerTask.java          # 主服同步任务
    │   └── MirrorApplyTask.java         # 镜像服接收与应用任务
    ├── util/
    │   ├── HashUtil.java                # SHA-256 工具
    │   └── DimensionPathUtil.java       # 维度 region 路径解析
    └── world/
        ├── WorldSafetyManager.java      # 踢人、强制保存、保存状态控制
        └── ChunkUnloader.java           # 区块卸载与 region 缓存清理
```

---

## ▶ 构建

```bash
./gradlew build
```

构建产物：`build/libs/mirage-1.0.0.jar`

---

## ! 注意事项

> [!WARNING]
> 主服和镜像服的 `authToken` 必须一致，否则连接会被拒绝。首次使用请务必将默认值 `change-me` 改为安全的随机字符串。

> [!NOTE]
> 同步期间镜像服维度内的玩家会被踢出，请提前告知。同步设有 **30 秒冷却时间**，防止频繁操作。

> [!NOTE]
> 网络端口/IP 等参数修改后需要重启服务器才能生效。

---

## © 许可证

**All Rights Reserved**

---

*xyz.tofumc · Mirage 1.0.0 · Minecraft 1.21.11*