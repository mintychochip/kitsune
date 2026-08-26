package dev.jlo.kitsune.session;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

/**
 * Clears player search sessions when players leave or change worlds.
 */
public final class SessionListener implements Listener {
    private final SearchSessionManager sessionManager;

    /**
     * Creates a listener for the supplied session manager.
     *
     * @param sessionManager manager whose sessions are invalidated
     */
    public SessionListener(SearchSessionManager sessionManager) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "Session manager must not be null");
    }

    /**
     * Clears a player's sessions when they quit.
     *
     * @param event player quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessionManager.clear(event.getPlayer().getUniqueId());
    }

    /**
     * Clears a player's sessions when they change worlds.
     *
     * @param event player changed-world event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        sessionManager.clear(event.getPlayer().getUniqueId());
    }
}
