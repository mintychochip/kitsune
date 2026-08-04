package dev.jlo.kitsune.paper;

import dev.jlo.kitsune.bukkit.BukkitRuntime;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperPlugin extends JavaPlugin {
    private BukkitRuntime runtime;

    @Override
    public void onEnable() {
        runtime = new BukkitRuntime(this, getServer(), getDataFolder().toPath(), true);
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
