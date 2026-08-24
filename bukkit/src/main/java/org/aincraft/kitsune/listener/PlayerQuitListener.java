package org.aincraft.kitsune.listener;

import org.aincraft.kitsune.visualizer.ContainerItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import jakarta.inject.Inject;

/**
 * Cleans up visualizations when players quit the server.
 */
public class PlayerQuitListener implements Listener {

    private final ContainerItemDisplay itemDisplayVisualizer;

    @Inject
    public PlayerQuitListener(ContainerItemDisplay itemDisplayVisualizer) {
        this.itemDisplayVisualizer = itemDisplayVisualizer;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        itemDisplayVisualizer.removeDisplaysForPlayer(event.getPlayer());
    }
}