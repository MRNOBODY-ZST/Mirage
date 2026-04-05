package xyz.tofumc.mixin;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerChunkCache.class)
public interface ServerChunkManagerAccessor {
    @Accessor("chunkMap")
    ChunkMap getChunkMap();
}
