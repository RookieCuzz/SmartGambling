package me.arthed.smartgambling.economy;

import java.util.Objects;

/**
 * At-most-once lifecycle gate for startup recovery.
 *
 * <p>Readiness failures leave the gate pending. Once recovery starts it is
 * permanently claimed, even if the action throws: an exception may have
 * happened after an external economy call and therefore must never cause an
 * automatic replay.</p>
 */
public final class RecoveryGate {
    private boolean attempted;
    private boolean completed;

    public boolean runIfReady(boolean ready, Runnable recovery) {
        Objects.requireNonNull(recovery, "recovery");
        synchronized (this) {
            if (!ready || attempted) {
                return false;
            }
            attempted = true;
        }
        recovery.run();
        synchronized (this) {
            completed = true;
        }
        return true;
    }

    public synchronized boolean attempted() {
        return attempted;
    }

    public synchronized boolean completed() {
        return completed;
    }
}
