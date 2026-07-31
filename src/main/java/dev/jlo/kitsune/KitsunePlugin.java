package dev.jlo.kitsune;

import org.bukkit.plugin.java.JavaPlugin;

public final class KitsunePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("Kitsune enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("Kitsune disabled");
    }
}
