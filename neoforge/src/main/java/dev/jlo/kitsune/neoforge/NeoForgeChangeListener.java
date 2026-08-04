package dev.jlo.kitsune.neoforge;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NeoForgeChangeListener implements AutoCloseable {
    private final Runnable rescan;
    private final AtomicBoolean closed = new AtomicBoolean();

    public NeoForgeChangeListener(Runnable rescan) {
        this.rescan = Objects.requireNonNull(rescan, "Rescan callback must not be null");
    }

    public void register() {
        NeoForge.EVENT_BUS.register(this);
    }

    @Override
    public void close() {
        closed.set(true);
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        requestRescan();
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        requestRescan();
    }

    @SubscribeEvent
    public void onBlockChange(BlockEvent event) {
        requestRescan();
    }

    private void requestRescan() {
        if (!closed.get()) rescan.run();
    }
}
