package xyz.tofumc.mirage.sync;

import com.google.gson.Gson;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import xyz.tofumc.Mirage;
import xyz.tofumc.mirage.hash.RegionHasher;
import xyz.tofumc.mirage.network.protocol.MessagePayloads;
import xyz.tofumc.mirage.network.protocol.MessageType;
import xyz.tofumc.mirage.network.server.MirageSyncServer;
import xyz.tofumc.mirage.util.DimensionPathUtil;
import xyz.tofumc.mirage.util.RegionFileUtil;
import xyz.tofumc.mirage.world.WorldSafetyManager;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class MainServerTask {
    private static final Gson GSON = new Gson();
    private final MinecraftServer server;
    private final SyncState syncState;

    public MainServerTask(MinecraftServer server, SyncState syncState) {
        this.server = server;
        this.syncState = syncState;
    }

    public CompletableFuture<Void> syncDimension(ServerLevel world) {
        MirageSyncServer syncServer = Mirage.getInstance().getSyncServer();
        if (syncServer == null) {
            Mirage.LOGGER.warn("Sync server is not running");
            return CompletableFuture.completedFuture(null);
        }
        if (!syncState.tryStartSync()) {
            long remaining = syncState.getRemainingCooldown() / 1000;
            Mirage.LOGGER.warn("Sync already in progress or cooldown active ({} seconds remaining)", remaining);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                MessagePayloads.HashListResponsePayload payload = buildHashListPayload(world);
                syncServer.broadcast(syncServer.encode(MessageType.HASH_LIST_RESP, MessagePayloads.toBytes(payload)));
                Mirage.LOGGER.info("Broadcasted {} file hashes for {}", payload.hashes().size(), payload.dimension());
            } catch (Exception e) {
                Mirage.LOGGER.error("Sync failed", e);
            } finally {
                syncState.endSync();
            }
        });
    }

    public MessagePayloads.HashListResponsePayload buildHashListPayload(ServerLevel world) throws Exception {
        flushWorldStateBeforeHash();
        Map<String, String> hashes = RegionHasher.hashRegionFiles(DimensionPathUtil.getRegionDir(server, world));
        return new MessagePayloads.HashListResponsePayload(world.dimension().identifier().toString(), hashes);
    }

    private void flushWorldStateBeforeHash() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                WorldSafetyManager.forceSaveAndFlush(server);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        future.join();
    }

    public String buildHashListPayloadJson(ServerLevel world) throws Exception {
        return GSON.toJson(buildHashListPayload(world));
    }

    public CompletableFuture<Void> syncChunk(ServerLevel world, int chunkX, int chunkZ) {
        MirageSyncServer syncServer = Mirage.getInstance().getSyncServer();
        if (syncServer == null) {
            Mirage.LOGGER.warn("Sync server is not running");
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                flushWorldStateBeforeHash();
                Path regionDir = DimensionPathUtil.getRegionDir(server, world);
                String mcaFileName = RegionFileUtil.getMcaFileName(chunkX, chunkZ);
                Path mcaFile = regionDir.resolve(mcaFileName);

                byte[] chunkData = RegionFileUtil.readChunkData(mcaFile, chunkX, chunkZ);
                if (chunkData == null) {
                    Mirage.LOGGER.warn("Chunk ({}, {}) not found in {}", chunkX, chunkZ, mcaFileName);
                    return;
                }

                String encoded = Base64.getEncoder().encodeToString(chunkData);
                MessagePayloads.ChunkSyncResponsePayload payload = new MessagePayloads.ChunkSyncResponsePayload(
                    world.dimension().identifier().toString(), chunkX, chunkZ, encoded
                );
                syncServer.broadcast(syncServer.encode(MessageType.CHUNK_SYNC_RESP, MessagePayloads.toBytes(payload)));
                Mirage.LOGGER.info("Broadcasted chunk ({}, {}) from {}", chunkX, chunkZ, world.dimension().identifier());
            } catch (Exception e) {
                Mirage.LOGGER.error("Chunk sync failed for ({}, {})", chunkX, chunkZ, e);
            }
        });
    }
}
