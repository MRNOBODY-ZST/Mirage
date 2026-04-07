package xyz.tofumc.mirage.sync;

import xyz.tofumc.Mirage;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class SyncState {
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final AtomicLong lastSyncTime = new AtomicLong(0);

    private long getCooldownMs() {
        return Mirage.getInstance().getConfig().getSyncCooldownSeconds() * 1000L;
    }

    public boolean tryStartSync() {
        long now = System.currentTimeMillis();
        if (now - lastSyncTime.get() < getCooldownMs()) {
            return false;
        }

        if (syncing.compareAndSet(false, true)) {
            lastSyncTime.set(now);
            return true;
        }
        return false;
    }

    public void endSync() {
        syncing.set(false);
    }

    public boolean isSyncing() {
        return syncing.get();
    }

    public long getRemainingCooldown() {
        long elapsed = System.currentTimeMillis() - lastSyncTime.get();
        long remaining = getCooldownMs() - elapsed;
        return Math.max(0, remaining);
    }
}
