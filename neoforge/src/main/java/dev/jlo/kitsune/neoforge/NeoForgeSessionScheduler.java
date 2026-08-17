package dev.jlo.kitsune.neoforge;

import dev.jlo.kitsune.session.SessionScheduler;
import dev.jlo.kitsune.session.SessionTask;
import net.minecraft.server.MinecraftServer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Schedules session actions for execution on the NeoForge server thread.
 */
public final class NeoForgeSessionScheduler implements SessionScheduler, AutoCloseable {
    private final MinecraftServer server;
    private final ScheduledExecutorService executor;

    /**
     * Creates a scheduler backed by a single background scheduling thread.
     *
     * @param server server on which scheduled actions are executed
     * @throws NullPointerException if {@code server} is {@code null}
     */
    public NeoForgeSessionScheduler(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
        ThreadFactory factory = task -> Thread.ofPlatform().name("kitsune-neoforge-session").unstarted(task);
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /**
     * Schedules an action to be submitted to the server after a delay.
     *
     * @param delay positive delay before submitting the action
     * @param action action to submit to the server
     * @return a task handle that can cancel the scheduled submission
     * @throws NullPointerException if {@code delay} or {@code action} is {@code null}
     * @throws IllegalArgumentException if {@code delay} is zero or negative
     */
    @Override
    public SessionTask schedule(Duration delay, Runnable action) {
        Objects.requireNonNull(delay, "Delay must not be null");
        Objects.requireNonNull(action, "Action must not be null");
        if (delay.isZero() || delay.isNegative()) throw new IllegalArgumentException("Delay must be positive");
        ScheduledFuture<?> future = executor.schedule(() -> server.execute(action), delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    /**
     * Stops scheduling and interrupts pending scheduler work.
     */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
