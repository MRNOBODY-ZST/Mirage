package xyz.tofumc.mirage.sync;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class SyncState {
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final AtomicLong lastSyncTime = new AtomicLong(0);
    private static final long COOLDOWN_MS = 30000; // 30 seconds

    public boolean tryStartSync() {
        long now = System.currentTimeMillis();
        if (now - lastSyncTime.get() < COOLDOWN_MS) {
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
        long remaining = COOLDOWN_MS - elapsed;
        return Math.max(0, remaining);
    }
}
