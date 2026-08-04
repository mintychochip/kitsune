package dev.jlo.kitsune.session;

/**
 * Represents a scheduled lifecycle task that can be canceled.
 */
public interface SessionTask {

    void cancel();
}
