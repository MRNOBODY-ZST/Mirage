package xyz.tofumc;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tofumc.mirage.command.MirageCommand;
import xyz.tofumc.mirage.config.ConfigManager;
import xyz.tofumc.mirage.config.MirageConfig;
import xyz.tofumc.mirage.network.client.MirageSyncClient;
import xyz.tofumc.mirage.network.protocol.MessagePayloads;
import xyz.tofumc.mirage.network.protocol.MessageType;
import xyz.tofumc.mirage.network.server.MirageSyncServer;
import xyz.tofumc.mirage.sync.MainServerTask;
import xyz.tofumc.mirage.sync.MirrorApplyTask;
import xyz.tofumc.mirage.sync.SyncState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Mirage implements ModInitializer {
    public static final String MOD_ID = "mirage";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static Mirage instance;

    private ConfigManager configManager;
    private MirageConfig config;
    private final SyncState syncState = new SyncState();
    private MinecraftServer server;
    private MirageSyncServer syncServer;
    private MirageSyncClient syncClient;
    private MainServerTask mainServerTask;
    private MirrorApplyTask mirrorApplyTask;

    public Mirage() {
        instance = this;
    }

    public static Mirage getInstance() {
        return instance;
    }

    @Override
    public void onInitialize() {
        configManager = new ConfigManager(FabricLoader.getInstance().getConfigDir());
        reloadConfig();

        CommandRegistrationCallback.EVENT.register(MirageCommand::register);
        ServerLifecycleEvents.SERVER_STARTED.register(this::handleServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::handleServerStopping);

        LOGGER.info("Mirage initialized in {} mode", getMode());
    }

    private void handleServerStarted(MinecraftServer server) {
        this.server = server;
        this.mainServerTask = new MainServerTask(server, syncState);
        Path cacheDir = server.getServerDirectory().resolve("mirage_cache");
        this.mirrorApplyTask = new MirrorApplyTask(server, syncState, cacheDir);

        if (isMainMode()) {
            startSyncServer();
        } else if (isMirrorMode()) {
            startSyncClient();
        }
    }

    private void handleServerStopping(MinecraftServer server) {
        if (syncClient != null) {
            syncClient.disconnect();
            syncClient = null;
        }
        if (syncServer != null) {
            syncServer.shutdown();
            syncServer = null;
        }
        mainServerTask = null;
        mirrorApplyTask = null;
        this.server = null;
    }

    private void startSyncServer() {
        MirageConfig.MainServerConfig mainConfig = config.getMainServer();
        syncServer = new MirageSyncServer(this, mainConfig.getPort(), mainConfig.getAuthToken());
        try {
            syncServer.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted while starting sync server", e);
        } catch (Exception e) {
            LOGGER.error("Failed to start sync server", e);
        }
    }

    private void startSyncClient() {
        MirageConfig.MirrorServerConfig mirrorConfig = config.getMirrorServer();
        syncClient = new MirageSyncClient(this, mirrorConfig.getMainIp(), mirrorConfig.getMainPort(), mirrorConfig.getAuthToken());
        syncClient.connect();
    }

    public boolean reloadConfig() {
        try {
            config = configManager.load();
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to load Mirage config", e);
            if (config == null) {
                config = new MirageConfig();
            }
            return false;
        }
    }

    public String getMode() {
        return config != null ? config.getMode() : "unknown";
    }

    public boolean isMainMode() {
        return "main".equalsIgnoreCase(getMode());
    }

    public boolean isMirrorMode() {
        return "mirror".equalsIgnoreCase(getMode());
    }

    public SyncState getSyncState() {
        return syncState;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public MirageConfig getConfig() {
        return config;
    }

    public MirageSyncServer getSyncServer() {
        return syncServer;
    }

    public MirageSyncClient getSyncClient() {
        return syncClient;
    }

    public MainServerTask getMainServerTask() {
        return mainServerTask;
    }

    public MirrorApplyTask getMirrorApplyTask() {
        return mirrorApplyTask;
    }

    public Optional<ServerLevel> getLevel(String dimensionId) {
        if (server == null) {
            return Optional.empty();
        }
        try {
            Identifier id = Identifier.parse(dimensionId);
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
            return Optional.ofNullable(server.getLevel(key));
        } catch (Exception e) {
            LOGGER.warn("Invalid dimension id: {}", dimensionId, e);
            return Optional.empty();
        }
    }

    public String requestPullForConfiguredDimensions() {
        if (!isMirrorMode()) {
            return "Mirage 当前不是 mirror 模式";
        }
        if (syncClient == null || !syncClient.isConnected()) {
            return "镜像服尚未连接到主服";
        }

        List<String> requested = new ArrayList<>();
        for (String dimensionId : config.getMirrorServer().getTargetDimensions()) {
            syncClient.send(MessageType.HASH_LIST_REQ, MessagePayloads.toBytes(new MessagePayloads.HashListRequestPayload(dimensionId)));
            requested.add(dimensionId);
        }
        return requested.isEmpty() ? "未配置任何目标维度" : "已请求拉取: " + String.join(", ", requested);
    }
}
