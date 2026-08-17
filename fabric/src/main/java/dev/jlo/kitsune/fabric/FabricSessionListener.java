package dev.jlo.kitsune.fabric;

import dev.jlo.kitsune.session.SearchSessionManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Clears player search sessions when their network connection disconnects.
 *
 * <p>{@link #close()} flips the listener to closed and clears all sessions at
 * once; while closed, disconnect events no longer clear individual sessions.
 */
public final class FabricSessionListener {
    private final SearchSessionManager sessions;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a listener tied to the given session manager.
     *
     * @param sessions session manager whose sessions are cleared
     */
    public FabricSessionListener(SearchSessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions, "Sessions must not be null");
    }

    /**
     * Registers the disconnect listener.
     */
    public void register() {
        ServerPlayConnectionEvents.DISCONNECT.register(this::onDisconnect);
    }

    /**
     * Closes the listener and clears all tracked sessions.
     */
    public void close() {
        closed.set(true);
        sessions.clearAll();
    }

    private void onDisconnect(ServerPlayNetworkHandler handler, MinecraftServer server) {
        if (!closed.get()) sessions.clear(handler.player.getUuid());
    }
}
