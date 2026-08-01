package dev.jlo.kitsune.session;

import java.time.Duration;

/**
 * Schedules a delayed lifecycle action and exposes its handle.
 */
public interface SessionScheduler {

    SessionTask schedule(Duration delay, Runnable action);
}
