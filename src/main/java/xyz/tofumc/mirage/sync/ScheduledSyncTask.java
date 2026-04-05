package xyz.tofumc.mirage.sync;

import xyz.tofumc.Mirage;
import xyz.tofumc.mirage.config.MirageConfig;
import xyz.tofumc.mirage.network.client.MirageSyncClient;
import xyz.tofumc.mirage.network.protocol.MessagePayloads;
import xyz.tofumc.mirage.network.protocol.MessageType;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ScheduledSyncTask {
    private final Mirage mirage;
    private ScheduledExecutorService scheduler;

    public ScheduledSyncTask(Mirage mirage) {
        this.mirage = mirage;
    }

    public void start() {
        stop();
        MirageConfig.MirrorServerConfig mirrorConfig = mirage.getConfig().getMirrorServer();
        int intervalMinutes = mirrorConfig.getAutoSyncIntervalMinutes();
        if (intervalMinutes <= 0) {
            Mirage.LOGGER.warn("Auto-sync interval must be positive, got {}. Disabling.", intervalMinutes);
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Mirage-ScheduledSync");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::runPull, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        Mirage.LOGGER.info("Auto-sync started: pulling every {} minutes", intervalMinutes);
    }

    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
            Mirage.LOGGER.info("Auto-sync stopped");
        }
    }

    private void runPull() {
        try {
            MirageSyncClient syncClient = mirage.getSyncClient();
            if (syncClient == null || !syncClient.isConnected()) {
                Mirage.LOGGER.warn("[Auto-sync] Not connected to main server, skipping");
                return;
            }

            List<String> dimensions = mirage.getConfig().getMirrorServer().getTargetDimensions();
            if (dimensions.isEmpty()) {
                Mirage.LOGGER.warn("[Auto-sync] No target dimensions configured, skipping");
                return;
            }

            Mirage.LOGGER.info("[Auto-sync] Requesting pull for {} dimension(s)", dimensions.size());

            for (String dimensionId : dimensions) {
                syncClient.send(MessageType.HASH_LIST_REQ, MessagePayloads.toBytes(new MessagePayloads.HashListRequestPayload(dimensionId)));
            }

            Mirage.LOGGER.info("[Auto-sync] Pull requests sent for: {}", String.join(", ", dimensions));
        } catch (Exception e) {
            Mirage.LOGGER.error("[Auto-sync] Unexpected error", e);
        }
    }
}
