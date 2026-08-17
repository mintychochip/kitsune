package dev.jlo.kitsune.fabric;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges Minecraft world-loading lifecycle events to a rescan request.
 *
 * <p>Registers chunk and block-entity load/unload listeners that invoke the
 * supplied rescan callback while the listener has not been closed. Close must
 * be called before the runtime shuts down; the listeners themselves are never
 * unregistered.
 */
public final class FabricChangeListener {
    private final Runnable rescan;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a listener that invokes the given rescan callback on world
     * change events.
     *
     * @param rescan callback invoked when a relevant world change occurs
     */
    public FabricChangeListener(Runnable rescan) {
        this.rescan = Objects.requireNonNull(rescan, "Rescan callback must not be null");
    }

    /**
     * Registers the chunk and block-entity lifecycle listeners.
     */
    public void register() {
        ServerChunkEvents.CHUNK_LOAD.register(this::onChunkLoad);
        ServerChunkEvents.CHUNK_UNLOAD.register(this::onChunkUnload);
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register(this::onBlockEntityLoad);
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register(this::onBlockEntityUnload);
    }

    /**
     * Marks the listener closed so future world events no longer trigger the
     * rescan callback.
     */
    public void close() {
        closed.set(true);
    }

    private void onChunkLoad(ServerWorld world, WorldChunk chunk) {
        if (!closed.get()) rescan.run();
    }

    private void onChunkUnload(ServerWorld world, WorldChunk chunk) {
        if (!closed.get()) rescan.run();
    }

    private void onBlockEntityLoad(BlockEntity blockEntity, ServerWorld world) {
        if (!closed.get()) rescan.run();
    }

    private void onBlockEntityUnload(BlockEntity blockEntity, ServerWorld world) {
        if (!closed.get()) rescan.run();
    }
}
