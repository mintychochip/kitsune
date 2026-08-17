package dev.jlo.kitsune.neoforge;

import dev.jlo.kitsune.session.SearchSessionManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Clears player search sessions on log-out, and clears all sessions when closed.
 */
public final class NeoForgeSessionListener implements AutoCloseable {
    private final SearchSessionManager sessions;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a session listener over the given session manager.
     *
     * @param sessions session manager whose sessions are cleared
     */
    public NeoForgeSessionListener(SearchSessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions, "Sessions must not be null");
    }

    /**
     * Registers this listener on the NeoForge event bus.
     */
    public void register() {
        NeoForge.EVENT_BUS.register(this);
    }

    /**
     * Marks the listener closed and clears all sessions.
     */
    @Override
    public void close() {
        closed.set(true);
        sessions.clearAll();
    }

    /**
     * Clears a player's sessions when they log out, unless the listener is closed.
     *
     * @param event player log-out event
     */
    @SubscribeEvent
    public void onDisconnect(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!closed.get()) sessions.clear(event.getEntity().getUUID());
    }
}
