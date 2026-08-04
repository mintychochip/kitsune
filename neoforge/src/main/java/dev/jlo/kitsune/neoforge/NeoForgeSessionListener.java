package dev.jlo.kitsune.neoforge;

import dev.jlo.kitsune.session.SearchSessionManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NeoForgeSessionListener implements AutoCloseable {
    private final SearchSessionManager sessions;
    private final AtomicBoolean closed = new AtomicBoolean();

    public NeoForgeSessionListener(SearchSessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions, "Sessions must not be null");
    }

    public void register() {
        NeoForge.EVENT_BUS.register(this);
    }

    @Override
    public void close() {
        closed.set(true);
        sessions.clearAll();
    }

    @SubscribeEvent
    public void onDisconnect(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!closed.get()) sessions.clear(event.getEntity().getUUID());
    }
}
