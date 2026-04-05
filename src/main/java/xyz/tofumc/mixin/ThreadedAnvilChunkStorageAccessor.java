package xyz.tofumc.mixin;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ThreadedAnvilChunkStorageAccessor {
    @Invoker("saveAllChunks")
    void invokeSaveAllChunks(boolean flush);
}
