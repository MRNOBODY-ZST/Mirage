package xyz.tofumc.mirage.sync;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import xyz.tofumc.Mirage;
import xyz.tofumc.mirage.hash.DeltaComparator;
import xyz.tofumc.mirage.hash.FileTransferManager;
import xyz.tofumc.mirage.hash.RegionHasher;
import xyz.tofumc.mirage.network.protocol.MessagePayloads;
import xyz.tofumc.mirage.network.protocol.MessageType;
import xyz.tofumc.mirage.util.DimensionPathUtil;
import xyz.tofumc.mirage.util.RegionFileUtil;
import xyz.tofumc.mirage.world.ChunkUnloader;
import xyz.tofumc.mirage.world.WorldSafetyManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class MirrorApplyTask {
    private final MinecraftServer server;
    private final SyncState syncState;
    private final Path cacheDir;
    private PendingSync pendingSync;

    public MirrorApplyTask(MinecraftServer server, SyncState syncState, Path cacheDir) {
        this.server = server;
        this.syncState = syncState;
        this.cacheDir = cacheDir;
    }

    public CompletableFuture<Void> applyHashList(MessagePayloads.HashListResponsePayload payload) {
        PendingSync current = pendingSync;
        if (current != null) {
            if (current.dimension().equals(payload.dimension())) {
                Mirage.LOGGER.info("Ignoring duplicate hash list while sync is already active for {}", payload.dimension());
                return CompletableFuture.completedFuture(null);
            }
            Mirage.LOGGER.warn("Sync already in progress for {}, ignoring {}", current.dimension(), payload.dimension());
            return CompletableFuture.completedFuture(null);
        }

        if (!syncState.tryStartSync()) {
            Mirage.LOGGER.warn("Sync already in progress");
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                ServerLevel world = requireWorld(payload.dimension());
                Path regionDir = DimensionPathUtil.getRegionDir(server, world);
                Map<String, String> mirrorHashes = RegionHasher.hashRegionFiles(regionDir);
                DeltaComparator.Delta delta = DeltaComparator.compare(payload.hashes(), mirrorHashes);

                pendingSync = new PendingSync(payload.dimension(), world, regionDir, delta.getFilesToDownload(), delta.getFilesToDelete());
                prepareCacheDir();

                if (pendingSync.filesToDownload().isEmpty()) {
                    handleSyncDone(new MessagePayloads.SyncDonePayload(payload.dimension(), true));
                    return;
                }

                MessagePayloads.FileSyncRequestPayload request = new MessagePayloads.FileSyncRequestPayload(
                    payload.dimension(),
                    pendingSync.filesToDownload(),
                    pendingSync.filesToDelete()
                );
                Mirage.getInstance().getSyncClient().send(MessageType.FILE_SYNC_START, MessagePayloads.toBytes(request));
                Mirage.LOGGER.info("Requested {} files for {}", pendingSync.filesToDownload().size(), payload.dimension());
            } catch (Exception e) {
                syncState.endSync();
                Mirage.LOGGER.error("Failed to process hash list", e);
            }
        });
    }

    public void handleFileChunk(MessagePayloads.FileChunkPayload payload) {
        PendingSync current = pendingSync;
        if (current == null || !current.dimension().equals(payload.dimension())) {
            Mirage.LOGGER.warn("Ignoring file chunk for unexpected dimension {}", payload.dimension());
            return;
        }

        try {
            byte[] data = Base64.getDecoder().decode(payload.data());
            Path tempFile = cacheDir.resolve(payload.file());
            Files.createDirectories(tempFile.getParent());
            FileTransferManager.writeChunk(tempFile, data, payload.offset());
            if (payload.eof()) {
                Mirage.LOGGER.info("Received file {} for {}", payload.file(), payload.dimension());
            }
        } catch (Exception e) {
            Mirage.LOGGER.error("Failed to write file chunk for {}", payload.file(), e);
        }
    }

    public void handleSyncDone(MessagePayloads.SyncDonePayload payload) {
        PendingSync current = pendingSync;
        if (current == null || !current.dimension().equals(payload.dimension())) {
            syncState.endSync();
            return;
        }

        server.execute(() -> {
            try {
                WorldSafetyManager.kickAllPlayers(current.world(), "Server syncing, reconnect in 30 seconds");
                WorldSafetyManager.forceSaveAndFlush(server);
                WorldSafetyManager.setSaveState(current.world(), false);

                ChunkUnloader.forceUnloadAll(current.world());

                ChunkUnloader.clearRegionCache(current.world());

                applyFiles(current.regionDir());
                deleteFiles(current.regionDir(), current.filesToDelete());

                ChunkUnloader.clearRegionCache(current.world());

                Mirage.LOGGER.info("Sync completed successfully for {}", current.dimension());
            } catch (Exception e) {
                Mirage.LOGGER.error("Failed to finalize sync for {}", current.dimension(), e);
            } finally {
                WorldSafetyManager.setSaveState(current.world(), true);
                pendingSync = null;
                syncState.endSync();
            }
        });
    }

    private ServerLevel requireWorld(String dimensionId) {
        return Mirage.getInstance().getLevel(dimensionId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown dimension: " + dimensionId));
    }

    private void prepareCacheDir() throws Exception {
        if (Files.exists(cacheDir)) {
            try (var stream = Files.list(cacheDir)) {
                stream.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        Files.createDirectories(cacheDir);
    }

    private void applyFiles(Path regionDir) throws Exception {
        if (!Files.exists(cacheDir)) {
            return;
        }

        try (var stream = Files.list(cacheDir)) {
            stream.filter(path -> path.toString().endsWith(".mca"))
                .forEach(source -> {
                    try {
                        Path target = regionDir.resolve(source.getFileName());
                        FileTransferManager.atomicMove(source, target);
                    } catch (Exception e) {
                        Mirage.LOGGER.error("Failed to move file {}", source, e);
                    }
                });
        }
    }

    private void deleteFiles(Path regionDir, List<String> files) {
        for (String filename : files) {
            try {
                Path file = regionDir.resolve(filename);
                Files.deleteIfExists(file);
                Mirage.LOGGER.info("Deleted obsolete file: {}", filename);
            } catch (Exception e) {
                Mirage.LOGGER.error("Failed to delete file {}", filename, e);
            }
        }
    }

    public record PendingSync(String dimension, ServerLevel world, Path regionDir, List<String> filesToDownload, List<String> filesToDelete) {
    }

    public void applyChunkSync(MessagePayloads.ChunkSyncResponsePayload payload) {
        server.execute(() -> {
            try {
                ServerLevel world = requireWorld(payload.dimension());
                Path regionDir = DimensionPathUtil.getRegionDir(server, world);
                String mcaFileName = RegionFileUtil.getMcaFileName(payload.chunkX(), payload.chunkZ());
                Path mcaFile = regionDir.resolve(mcaFileName);

                byte[] chunkData = Base64.getDecoder().decode(payload.data());

                WorldSafetyManager.forceSaveAndFlush(server);
                ChunkUnloader.clearRegionCache(world);

                Files.createDirectories(regionDir);
                RegionFileUtil.writeChunkData(mcaFile, payload.chunkX(), payload.chunkZ(), chunkData);

                ChunkUnloader.clearRegionCache(world);

                Mirage.LOGGER.info("Applied chunk ({}, {}) for {}", payload.chunkX(), payload.chunkZ(), payload.dimension());
            } catch (Exception e) {
                Mirage.LOGGER.error("Failed to apply chunk ({}, {}) for {}", payload.chunkX(), payload.chunkZ(), payload.dimension(), e);
            }
        });
    }
}
