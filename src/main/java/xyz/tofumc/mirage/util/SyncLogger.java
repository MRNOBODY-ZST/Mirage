package xyz.tofumc.mirage.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import xyz.tofumc.Mirage;

/**
 * Broadcasts sync-related messages to both the server console (SLF4J)
 * and in-game chat for Admin/OP players.
 */
public final class SyncLogger {
    private static final String PREFIX = "[Mirage] ";
    private static final int COLOR_INFO = 0x55FF55;    // green
    private static final int COLOR_WARN = 0xFFFF55;    // yellow
    private static final int COLOR_ERROR = 0xFF5555;    // red
    private static final int COLOR_PREFIX = 0x55FFFF;   // aqua

    private SyncLogger() {}

    public static void info(String message, Object... args) {
        String formatted = format(message, args);
        Mirage.LOGGER.info(formatted);
        broadcastToOps(formatted, COLOR_INFO);
    }

    public static void warn(String message, Object... args) {
        String formatted = format(message, args);
        Mirage.LOGGER.warn(formatted);
        broadcastToOps(formatted, COLOR_WARN);
    }

    public static void error(String message, Object... args) {
        Object lastArg = args.length > 0 ? args[args.length - 1] : null;
        String formatted;
        if (lastArg instanceof Throwable t) {
            Object[] messageArgs = new Object[args.length - 1];
            System.arraycopy(args, 0, messageArgs, 0, messageArgs.length);
            formatted = format(message, messageArgs);
            Mirage.LOGGER.error(formatted, t);
        } else {
            formatted = format(message, args);
            Mirage.LOGGER.error(formatted);
        }
        broadcastToOps(formatted, COLOR_ERROR);
    }

    private static String format(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int i = 0;
        while (i < message.length()) {
            if (i + 1 < message.length() && message.charAt(i) == '{' && message.charAt(i + 1) == '}' && argIndex < args.length) {
                sb.append(args[argIndex++]);
                i += 2;
            } else {
                sb.append(message.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    private static void broadcastToOps(String message, int color) {
        MinecraftServer server = Mirage.getInstance().getServer();
        if (server == null) {
            return;
        }

        MutableComponent prefix = Component.literal(PREFIX)
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_PREFIX)).withBold(true));
        MutableComponent body = Component.literal(message)
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
        MutableComponent fullMessage = prefix.append(body);

        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (isOp(player)) {
                    player.sendSystemMessage(fullMessage);
                }
            }
        });
    }

    private static boolean isOp(ServerPlayer player) {
        var permissions = player.createCommandSourceStack().permissions();
        if (permissions instanceof LevelBasedPermissionSet levelBased) {
            return levelBased.level().isEqualOrHigherThan(PermissionLevel.ADMINS);
        }
        return false;
    }
}
