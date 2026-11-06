package xyz.tofumc.mirage.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import xyz.tofumc.Mirage;
import xyz.tofumc.mixin.IOWorkerAccessor;
import xyz.tofumc.mixin.RegionFileStorageAccessor;
import xyz.tofumc.mixin.SimpleRegionStorageAccessor;
import xyz.tofumc.mixin.ThreadedAnvilChunkStorageAccessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ChunkUnloader {
    public static void forceUnloadAll(ServerLevel world) throws IOException {
        ServerChunkCache chunkSource = world.getChunkSource();
        int loadedBefore = chunkSource.getLoadedChunksCount();

        chunkSource.save(true);

        ChunkMap chunkMap = chunkSource.chunkMap;
        ((ThreadedAnvilChunkStorageAccessor) chunkMap).invokeSaveAllChunks(true);

        chunkMap.synchronize(true).join();

        Mirage.LOGGER.info(
            "Saved and synchronized chunks for dimension {} (loaded chunks: {})",
            world.dimension().identifier(),
            loadedBefore
        );
    }

    public static void clearRegionCache(ServerLevel world) {
        try {
            ServerChunkCache chunkSource = world.getChunkSource();
            ChunkMap chunkMap = chunkSource.chunkMap;
            IOWorker worker = ((SimpleRegionStorageAccessor) chunkMap).getWorker();

            worker.synchronize(true).join();

            RegionFileStorage storage = ((IOWorkerAccessor) worker).getStorage();

            @SuppressWarnings("unchecked")
            Long2ObjectLinkedOpenHashMap<Optional<RegionFile>> regionCache =
                ((RegionFileStorageAccessor) (Object) storage).getRegionCache();

            // 26.2 stores RegionFile directly; 26.3+ stores Optional<RegionFile> (erased to the same raw map)
            List<RegionFile> toClose = new ArrayList<>();
            for (Object entry : regionCache.values()) {
                if (entry instanceof Optional<?> optional) {
                    Object value = optional.orElse(null);
                    if (value instanceof RegionFile regionFile) {
                        toClose.add(regionFile);
                    }
                } else if (entry instanceof RegionFile regionFile) {
                    toClose.add(regionFile);
                }
            }
            regionCache.clear();

            int count = 0;
            for (RegionFile regionFile : toClose) {
                try {
                    regionFile.close();
                    count++;
                } catch (Exception e) {
                    Mirage.LOGGER.warn("Failed to close a region file handle", e);
                }
            }

            Mirage.LOGGER.info("Cleared and closed {} cached region file handles", count);
        } catch (Exception e) {
            Mirage.LOGGER.error("Failed to clear region cache for {}", world.dimension().identifier(), e);
        }
    }
}
