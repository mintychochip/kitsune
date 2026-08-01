package dev.jlo.kitsune.session;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.Objects;

public final class SessionListener implements Listener {
    private final SearchSessionManager sessionManager;

    public SessionListener(SearchSessionManager sessionManager) {
        this.sessionManager = Objects.requireNonNull(
            sessionManager,
            "Session manager must not be null"
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessionManager.clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        sessionManager.clear(event.getPlayer().getUniqueId());
    }
}
