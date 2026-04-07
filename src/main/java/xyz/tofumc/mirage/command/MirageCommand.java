package xyz.tofumc.mirage.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import xyz.tofumc.Mirage;

public class MirageCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("mirage")
            .then(Commands.literal("sync")
                .requires(source -> hasPermission(source, PermissionLevel.ADMINS))
                .then(Commands.literal("all")
                    .executes(MirageCommand::executeSyncAll)
                )
                .then(Commands.literal("chunk")
                    .then(Commands.argument("dimension", DimensionArgument.dimension())
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(MirageCommand::executeSyncChunk)
                            )
                        )
                    )
                )
                .then(Commands.argument("dimension", DimensionArgument.dimension())
                    .executes(MirageCommand::executeSync)
                )
            )
            .then(Commands.literal("pull")
                .requires(source -> hasPermission(source, PermissionLevel.ADMINS))
                .then(Commands.literal("all")
                    .executes(MirageCommand::executePullAll)
                )
                .then(Commands.literal("chunk")
                    .then(Commands.argument("dimension", DimensionArgument.dimension())
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(MirageCommand::executePullChunk)
                            )
                        )
                    )
                )
                .then(Commands.argument("dimension", DimensionArgument.dimension())
                    .executes(MirageCommand::executePull)
                )
            )
            .then(Commands.literal("status")
                .executes(MirageCommand::executeStatus)
            )
            .then(Commands.literal("reload")
                .requires(source -> hasPermission(source, PermissionLevel.ADMINS))
                .executes(MirageCommand::executeReload)
            )
        );
    }

    private static boolean hasPermission(CommandSourceStack source, PermissionLevel level) {
        if (source.permissions() instanceof LevelBasedPermissionSet levelBasedPermissionSet) {
            return levelBasedPermissionSet.level().isEqualOrHigherThan(level);
        }
        return false;
    }

    private static int executeSync(CommandContext<CommandSourceStack> context) {
        Mirage mirage = Mirage.getInstance();
        if (!mirage.isMainMode()) {
            context.getSource().sendFailure(Component.literal("当前不是 main 模式"));
            return 0;
        }
        if (mirage.getMainServerTask() == null) {
            context.getSource().sendFailure(Component.literal("主服同步任务尚未初始化"));
            return 0;
        }

        try {
            ServerLevel world = DimensionArgument.getDimension(context, "dimension");
            mirage.getMainServerTask().syncDimension(world);
            context.getSource().sendSuccess(() -> Component.literal("开始同步维度: " + world.dimension().identifier()), true);
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("同步失败: " + e.getMessage()));
            return 0;
        }
    }

    private static int executeSyncAll(CommandContext<CommandSourceStack> context) {
        Mirage mirage = Mirage.getInstance();
        if (!mirage.isMainMode()) {
            context.getSource().sendFailure(Component.literal("当前不是 main 模式"));
            return 0;
        }
        if (mirage.getMainServerTask() == null) {
            context.getSource().sendFailure(Component.literal("主服同步任务尚未初始化"));
            return 0;
        }

        try {
            int count = 0;
            for (ServerLevel world : mirage.getServer().getAllLevels()) {
                mirage.getMainServerTask().syncDimension(world);
                count++;
            }
            int finalCount = count;
            context.getSource().sendSuccess(() -> Component.literal("开始同步所有维度，共 " + finalCount + " 个"), true);
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("同步失败: " + e.getMessage()));
            return 0;
        }
    }

    private static int executePull(CommandContext<CommandSourceStack> context) {
        Mirage mirage = Mirage.getInstance();
        if (!mirage.isMirrorMode()) {
            context.getSource().sendFailure(Component.literal("当前不是 mirror 模式"));
            return 0;
        }

        try {
            ServerLevel world = DimensionArgument.getDimension(context, "dimension");
            String dimensionId = world.dimension().identifier().toString();
            String result = mirage.requestPullForDimension(dimensionId);
            context.getSource().sendSuccess(() -> Component.literal(result), true);
            return result.startsWith("已请求") ? 1 : 0;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("拉取失败: " + e.getMessage()));
            return 0;
        }
    }

    private static int executePullAll(CommandContext<CommandSourceStack> context) {
        Mirage mirage = Mirage.getInstance();
        String result = mirage.requestPullForAllDimensions();
        context.getSource().sendSuccess(() -> Component.literal(result), true);
        return result.startsWith("已请求") ? 1 : 0;
    }

    private static int executeSyncChunk(CommandContext<CommandSourceStack> context) {
        Mirage mirage = Mirage.getInstance();
        if (!mirage.isMainMode()) {
            context.getSource().sendFailure(Component.literal("当前不是 main 模式"));
            return 0;
        }
        if (mirage.getMainServerTask() == null) {
            context.getSource().sendFailure(Component.literal("主服同步任务尚未初始化"));
            return 0;
        }

        try {
            ServerLevel world = DimensionArgument.getDimension(context, "dimension");
            int blockX = IntegerArgumentType.getInteger(context, "x");
            int blockZ = IntegerArgumentType.getInteger(context, "z");
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            mirage.getMainServerTask().syncChunk(world, chunkX, chunkZ);
            context.getSource().sendSuccess(() -> Component.literal("开始同步区块: (" + chunkX + ", " + chunkZ + ") [坐标 " + blockX + ", " + blockZ + "] @ " + world.dimension().identifier()), true);
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("区块同步失败: " + e.getMessage()));
            return 0;
        }
    }

    private static int executePullChunk(CommandContext<CommandSourceStack> context) {
        Mirage mirage = Mirage.getInstance();
        if (!mirage.isMirrorMode()) {
            context.getSource().sendFailure(Component.literal("当前不是 mirror 模式"));
            return 0;
        }

        try {
            ServerLevel world = DimensionArgument.getDimension(context, "dimension");
            int blockX = IntegerArgumentType.getInteger(context, "x");
            int blockZ = IntegerArgumentType.getInteger(context, "z");
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            String dimensionId = world.dimension().identifier().toString();
            String result = mirage.requestPullChunk(dimensionId, chunkX, chunkZ);
            context.getSource().sendSuccess(() -> Component.literal(result + " [坐标 " + blockX + ", " + blockZ + "]"), true);
            return result.startsWith("已请求") ? 1 : 0;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("区块拉取失败: " + e.getMessage()));
            return 0;
        }
    }

    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        Mirage mirage = Mirage.getInstance();
        String status;
        if (mirage.isMainMode()) {
            int clients = mirage.getSyncServer() != null ? mirage.getSyncServer().getConnectedClients().size() : 0;
            status = "Mirage mode=main, clients=" + clients + ", syncing=" + mirage.getSyncState().isSyncing();
        } else if (mirage.isMirrorMode()) {
            boolean connected = mirage.getSyncClient() != null && mirage.getSyncClient().isConnected();
            status = "Mirage mode=mirror, connected=" + connected + ", syncing=" + mirage.getSyncState().isSyncing();
        } else {
            status = "Mirage mode=" + mirage.getMode();
        }
        context.getSource().sendSuccess(() -> Component.literal(status), false);
        return 1;
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        Mirage mirage = Mirage.getInstance();
        boolean success = mirage.reloadConfig();
        if (success) {
            context.getSource().sendSuccess(() -> Component.literal("Mirage 配置已重新加载；网络参数变更需重启生效"), true);
            return 1;
        }
        context.getSource().sendFailure(Component.literal("Mirage 配置重载失败，请检查日志"));
        return 0;
    }
}
