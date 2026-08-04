package dev.jlo.kitsune.fabric;

import dev.jlo.kitsune.session.SearchSessionManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FabricSessionListener {
    private final SearchSessionManager sessions;
    private final AtomicBoolean closed = new AtomicBoolean();

    public FabricSessionListener(SearchSessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions, "Sessions must not be null");
    }

    public void register() {
        ServerPlayConnectionEvents.DISCONNECT.register(this::onDisconnect);
    }

    public void close() {
        closed.set(true);
        sessions.clearAll();
    }

    private void onDisconnect(ServerPlayNetworkHandler handler, MinecraftServer server) {
        if (!closed.get()) sessions.clear(handler.player.getUuid());
    }
}
