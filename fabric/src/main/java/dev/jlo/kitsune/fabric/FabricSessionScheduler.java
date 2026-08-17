package dev.jlo.kitsune.fabric;

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
 * {@link SessionScheduler} that runs delayed session tasks on the Minecraft
 * server thread using a dedicated single-thread scheduled executor.
 *
 * <p>Scheduling a task requires a strictly positive delay; the returned
 * {@link SessionTask} cancels the pending run. {@link #close()} shuts down
 * the executor.
 */
public final class FabricSessionScheduler implements SessionScheduler, AutoCloseable {
    private final MinecraftServer server;
    private final ScheduledExecutorService executor;

    /**
     * Creates a scheduler bound to the given server.
     *
     * @param server server thread on which scheduled actions run
     */
    public FabricSessionScheduler(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
        ThreadFactory factory = task -> Thread.ofPlatform().name("kitsune-fabric-session").unstarted(task);
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /**
     * Schedules an action to run after a positive delay on the server thread.
     *
     * @param delay delay before the action runs; must be positive
     * @param action action to run on the server thread
     * @return a task whose cancellation prevents the pending run
     */
    @Override
    public SessionTask schedule(Duration delay, Runnable action) {
        Objects.requireNonNull(delay, "Delay must not be null");
        Objects.requireNonNull(action, "Action must not be null");
        if (delay.isZero() || delay.isNegative()) throw new IllegalArgumentException("Delay must be positive");
        long nanos = delay.toNanos();
        ScheduledFuture<?> future = executor.schedule(() -> server.execute(action), nanos, TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    /**
     * Shuts down the scheduler executor, canceling pending tasks.
     */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
