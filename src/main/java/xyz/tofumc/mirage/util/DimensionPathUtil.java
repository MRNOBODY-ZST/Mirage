package xyz.tofumc.mirage.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public final class DimensionPathUtil {
    private DimensionPathUtil() {
    }

    public static Path getRegionDir(MinecraftServer server, ServerLevel world) {
        return getRegionDir(server, world.dimension());
    }

    public static Path getRegionDir(MinecraftServer server, ResourceKey<Level> dimensionKey) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        if (dimensionKey == Level.OVERWORLD) {
            return worldRoot.resolve("region");
        }
        if (dimensionKey == Level.NETHER) {
            return worldRoot.resolve("DIM-1").resolve("region");
        }
        if (dimensionKey == Level.END) {
            return worldRoot.resolve("DIM1").resolve("region");
        }

        String namespace = dimensionKey.identifier().getNamespace();
        String path = dimensionKey.identifier().getPath();
        return worldRoot.resolve("dimensions").resolve(namespace).resolve(path).resolve("region");
    }
}
