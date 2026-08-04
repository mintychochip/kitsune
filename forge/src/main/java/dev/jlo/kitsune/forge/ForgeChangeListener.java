package dev.jlo.kitsune.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ForgeChangeListener implements AutoCloseable {
    private final Runnable rescan;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ForgeChangeListener(Runnable rescan) {
        this.rescan = Objects.requireNonNull(rescan, "Rescan callback must not be null");
    }

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
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
