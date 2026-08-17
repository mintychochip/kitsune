package dev.jlo.kitsune.spigot;

import dev.jlo.kitsune.bukkit.BukkitRuntime;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Spigot plugin entrypoint for the shared Bukkit runtime.
 */
public final class SpigotPlugin extends JavaPlugin {
    private BukkitRuntime runtime;

    @Override
    public void onEnable() {
        runtime = new BukkitRuntime(this, getServer(), getDataFolder().toPath(), false);
        runtime.start();
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }
}
