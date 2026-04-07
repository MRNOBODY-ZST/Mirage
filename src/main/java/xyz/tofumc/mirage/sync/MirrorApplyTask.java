package xyz.tofumc.mirage.sync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import xyz.tofumc.Mirage;
import xyz.tofumc.mirage.hash.DeltaComparator;
import xyz.tofumc.mirage.hash.FileTransferManager;
import xyz.tofumc.mirage.hash.RegionHasher;
import xyz.tofumc.mirage.network.protocol.MessagePayloads;
import xyz.tofumc.mirage.network.protocol.MessageType;
import xyz.tofumc.mirage.util.DimensionPathUtil;
import xyz.tofumc.mirage.util.RegionFileUtil;
import xyz.tofumc.mirage.util.SyncLogger;
import xyz.tofumc.mirage.world.ChunkUnloader;
import xyz.tofumc.mirage.world.WorldSafetyManager;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
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
                SyncLogger.info("Ignoring duplicate hash list while sync is already active for {}", payload.dimension());
                return CompletableFuture.completedFuture(null);
            }
            SyncLogger.warn("Sync already in progress for {}, ignoring {}", current.dimension(), payload.dimension());
            return CompletableFuture.completedFuture(null);
        }

        if (!syncState.tryStartSync()) {
            SyncLogger.warn("Sync already in progress");
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
                SyncLogger.info("Requested {} files for {}", pendingSync.filesToDownload().size(), payload.dimension());
            } catch (Exception e) {
                syncState.endSync();
                SyncLogger.error("Failed to process hash list", e);
            }
        });
    }

    public void handleFileChunk(MessagePayloads.FileChunkPayload payload) {
        PendingSync current = pendingSync;
        if (current == null || !current.dimension().equals(payload.dimension())) {
            SyncLogger.warn("Ignoring file chunk for unexpected dimension {}", payload.dimension());
            return;
        }

        try {
            byte[] data = Base64.getDecoder().decode(payload.data());
            Path tempFile = cacheDir.resolve(payload.file());
            Files.createDirectories(tempFile.getParent());
            FileTransferManager.writeChunk(tempFile, data, payload.offset());
            if (payload.eof()) {
                SyncLogger.info("Received file {} for {}", payload.file(), payload.dimension());
            }
        } catch (Exception e) {
            SyncLogger.error("Failed to write file chunk for {}", payload.file(), e);
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

                SyncLogger.info("Sync completed successfully for {}", current.dimension());
            } catch (Exception e) {
                SyncLogger.error("Failed to finalize sync for {}", current.dimension(), e);
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
                        SyncLogger.error("Failed to move file {}", source, e);
                    }
                });
        }
    }

    private void deleteFiles(Path regionDir, List<String> files) {
        for (String filename : files) {
            try {
                Path file = regionDir.resolve(filename);
                Files.deleteIfExists(file);
                SyncLogger.info("Deleted obsolete file: {}", filename);
            } catch (Exception e) {
                SyncLogger.error("Failed to delete file {}", filename, e);
            }
        }
    }

    public record PendingSync(String dimension, ServerLevel world, Path regionDir, List<String> filesToDownload, List<String> filesToDelete) {
    }

    public void applyChunkSync(MessagePayloads.ChunkSyncResponsePayload payload) {
        server.execute(() -> {
            try {
                ServerLevel world = requireWorld(payload.dimension());
                byte[] rawData = Base64.getDecoder().decode(payload.data());

                // Parse raw MCA chunk data (4-byte length + 1-byte compression type + compressed NBT)
                CompoundTag nbt = parseChunkNbt(rawData);

                // Check if the chunk is currently loaded in memory
                ChunkMap chunkMap = world.getChunkSource().chunkMap;
                long chunkKey = ChunkPos.asLong(payload.chunkX(), payload.chunkZ());
                var chunkHolder = ((xyz.tofumc.mixin.ChunkMapAccessor) chunkMap).invokeGetVisibleChunkIfPresent(chunkKey);
                LevelChunk loadedChunk = chunkHolder != null ? chunkHolder.getTickingChunk() : null;

                if (loadedChunk != null) {
                    // Chunk is loaded — replace block data in memory directly
                    PalettedContainerFactory factory = PalettedContainerFactory.create(server.registryAccess());
                    SerializableChunkData chunkData = SerializableChunkData.parse(world, factory, nbt);
                    ProtoChunk protoChunk = chunkData.read(world, world.getPoiManager(),
                        world.getChunkSource().chunkMap.storageInfo(), new ChunkPos(payload.chunkX(), payload.chunkZ()));

                    // Replace sections in the live chunk
                    LevelChunkSection[] newSections = protoChunk.getSections();
                    LevelChunkSection[] liveSections = loadedChunk.getSections();
                    for (int i = 0; i < liveSections.length && i < newSections.length; i++) {
                        liveSections[i] = newSections[i];
                    }

                    // Clear and re-add block entities
                    loadedChunk.getBlockEntities().keySet().stream().toList()
                        .forEach(loadedChunk::removeBlockEntity);
                    protoChunk.getBlockEntityNbts().forEach((pos, tag) -> {
                        loadedChunk.setBlockEntityNbt(tag);
                    });

                    // Mark chunk as needing save
                    loadedChunk.markUnsaved();

                    // Resend chunk to all tracking players
                    ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                        loadedChunk, world.getLightEngine(), null, null
                    );
                    List<ServerPlayer> players = chunkMap.getPlayers(
                        new ChunkPos(payload.chunkX(), payload.chunkZ()), false
                    );
                    for (ServerPlayer player : players) {
                        player.connection.send(packet);
                    }

                    SyncLogger.info("Applied chunk ({}, {}) in-memory for {}", payload.chunkX(), payload.chunkZ(), payload.dimension());
                } else {
                    // Chunk not loaded — write to disk
                    Path regionDir = DimensionPathUtil.getRegionDir(server, world);
                    String mcaFileName = RegionFileUtil.getMcaFileName(payload.chunkX(), payload.chunkZ());
                    Path mcaFile = regionDir.resolve(mcaFileName);

                    ChunkUnloader.clearRegionCache(world);

                    Files.createDirectories(regionDir);
                    RegionFileUtil.writeChunkData(mcaFile, payload.chunkX(), payload.chunkZ(), rawData);

                    ChunkUnloader.clearRegionCache(world);

                    SyncLogger.info("Applied chunk ({}, {}) to disk for {}", payload.chunkX(), payload.chunkZ(), payload.dimension());
                }
            } catch (Exception e) {
                SyncLogger.error("Failed to apply chunk ({}, {}) for {}", payload.chunkX(), payload.chunkZ(), payload.dimension(), e);
            }
        });
    }

    /**
     * Parse raw MCA chunk data into a CompoundTag.
     * Raw format: 4-byte length (big-endian) + 1-byte compression type + compressed NBT.
     */
    private CompoundTag parseChunkNbt(byte[] rawData) throws Exception {
        // Skip 4-byte length prefix
        int compressionType = rawData[4] & 0xFF;
        byte[] compressedNbt = new byte[rawData.length - 5];
        System.arraycopy(rawData, 5, compressedNbt, 0, compressedNbt.length);

        InputStream is = new ByteArrayInputStream(compressedNbt);
        InputStream decompressed = RegionFileVersion.fromId(compressionType).wrap(is);
        return NbtIo.read(new DataInputStream(decompressed), NbtAccounter.unlimitedHeap());
    }
}
