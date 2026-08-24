package dev.jlo.kitsune.session;

/**
 * Represents a scheduled lifecycle task that can be canceled.
 */
public interface SessionTask {

    /**
     * Cancels the scheduled task if it has not yet run.
     */
    void cancel();
}
