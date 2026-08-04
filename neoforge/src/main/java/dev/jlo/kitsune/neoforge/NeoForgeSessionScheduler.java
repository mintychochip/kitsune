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

public final class NeoForgeSessionScheduler implements SessionScheduler, AutoCloseable {
    private final MinecraftServer server;
    private final ScheduledExecutorService executor;

    public NeoForgeSessionScheduler(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
        ThreadFactory factory = task -> Thread.ofPlatform().name("kitsune-neoforge-session").unstarted(task);
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    @Override
    public SessionTask schedule(Duration delay, Runnable action) {
        Objects.requireNonNull(delay, "Delay must not be null");
        Objects.requireNonNull(action, "Action must not be null");
        if (delay.isZero() || delay.isNegative()) throw new IllegalArgumentException("Delay must be positive");
        ScheduledFuture<?> future = executor.schedule(() -> server.execute(action), delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
