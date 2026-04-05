package xyz.tofumc.mirage.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import xyz.tofumc.Mirage;

import java.util.List;

public class WorldSafetyManager {
    public static void kickAllPlayers(ServerLevel world, String reason) {
        List<ServerPlayer> players = java.util.List.copyOf(world.players());
        Component kickMessage = Component.literal(reason);

        for (ServerPlayer player : players) {
            player.connection.disconnect(kickMessage);
        }

        Mirage.LOGGER.info("Kicked {} players from dimension {}", players.size(), world.dimension());
    }

    public static void forceSaveAndFlush(MinecraftServer server) {
        try {
            server.saveAllChunks(true, true, true);
            Mirage.LOGGER.info("Force saved and flushed all worlds");
        } catch (Exception e) {
            Mirage.LOGGER.error("Failed to force save", e);
        }
    }

    public static void setSaveState(ServerLevel world, boolean enabled) {
        if (world instanceof xyz.tofumc.mixin.ServerWorldAccessor accessor) {
            accessor.setNoSave(!enabled);
            Mirage.LOGGER.info("Set save state for {} to {}", world.dimension(), enabled);
        }
    }
}
