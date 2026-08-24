package dev.jlo.kitsune.session;

import java.time.Duration;

/**
 * Schedules a delayed lifecycle action and exposes its handle.
 */
public interface SessionScheduler {

    /**
     * Schedules an action to run after the given delay.
     *
     * @param delay  delay before the action runs
     * @param action action to schedule
     * @return a handle for the scheduled task, or {@code null} if scheduling
     *         was not possible
     */
    SessionTask schedule(Duration delay, Runnable action);
}
