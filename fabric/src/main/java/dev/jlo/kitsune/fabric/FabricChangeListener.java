package dev.jlo.kitsune.fabric;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FabricChangeListener {
    private final Runnable rescan;
    private final AtomicBoolean closed = new AtomicBoolean();

    public FabricChangeListener(Runnable rescan) {
        this.rescan = Objects.requireNonNull(rescan, "Rescan callback must not be null");
    }

    public void register() {
        ServerChunkEvents.CHUNK_LOAD.register(this::onChunkLoad);
        ServerChunkEvents.CHUNK_UNLOAD.register(this::onChunkUnload);
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register(this::onBlockEntityLoad);
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register(this::onBlockEntityUnload);
    }

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
