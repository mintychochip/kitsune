package dev.jlo.kitsune.forge;

import dev.jlo.kitsune.session.SearchSessionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Clears player search sessions on logout until {@link #close() closed}.
 */
public final class ForgeSessionListener implements AutoCloseable {
    private final SearchSessionManager sessions;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a listener that clears sessions owned by {@code sessions}.
     *
     * @param sessions manager whose sessions are cleared on logout
     */
    public ForgeSessionListener(SearchSessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions, "Sessions must not be null");
    }

    /**
     * Registers this listener with the Forge event bus.
     */
    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * Marks the listener closed and clears all tracked sessions.
     */
    @Override
    public void close() {
        closed.set(true);
        sessions.clearAll();
    }

    /**
     * Clears the leaving player's sessions.
     *
     * @param event the player logout event
     */
    @SubscribeEvent
    public void onDisconnect(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!closed.get()) sessions.clear(event.getEntity().getUUID());
    }
}
