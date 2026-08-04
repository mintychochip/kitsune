package dev.jlo.kitsune.forge;

import dev.jlo.kitsune.session.SearchSessionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ForgeSessionListener implements AutoCloseable {
    private final SearchSessionManager sessions;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ForgeSessionListener(SearchSessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions, "Sessions must not be null");
    }

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
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
