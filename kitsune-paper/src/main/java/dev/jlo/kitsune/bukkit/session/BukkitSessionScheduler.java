package dev.jlo.kitsune.session;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Objects;

/**
 * Schedules session callbacks on the Bukkit scheduler as delayed tasks.
 */
public final class BukkitSessionScheduler implements SessionScheduler {
    private final Plugin plugin;

    /**
     * Creates a scheduler backed by a plugin's Bukkit scheduler.
     *
     * @param plugin plugin whose scheduler is used
     */
    public BukkitSessionScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin must not be null");
    }

    /**
     * Schedules an action to run after a positive delay, in whole ticks.
     *
     * @param delay positive delay before execution
     * @param action action to run
     * @return a handle whose cancellation stops the pending execution
     */
    @Override
    public SessionTask schedule(Duration delay, Runnable action) {
        Objects.requireNonNull(delay, "Delay must not be null");
        Objects.requireNonNull(action, "Action must not be null");
        if (delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("Delay must be positive");
        }

        long wholeSecondTicks = Math.multiplyExact(delay.getSeconds(), 20L);
        long partialTicks = (delay.getNano() + 49_999_999L) / 50_000_000L;
        long ticks = Math.max(1L, Math.addExact(wholeSecondTicks, partialTicks));
        BukkitTask task = Objects.requireNonNull(
            plugin.getServer().getScheduler().runTaskLater(plugin, action, ticks),
            "Scheduler returned a null task"
        );
        return task::cancel;
    }
}
