package dev.jlo.kitsune.neoforge;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for chunk load/unload and block-change events and triggers an index
 * rescan callback, disabling itself once closed.
 */
public final class NeoForgeChangeListener implements AutoCloseable {
    private final Runnable rescan;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a change listener that runs the given rescan callback on relevant
     * level-change events.
     *
     * @param rescan callback invoked when a relevant change event occurs
     */
    public NeoForgeChangeListener(Runnable rescan) {
        this.rescan = Objects.requireNonNull(rescan, "Rescan callback must not be null");
    }

    /**
     * Registers this listener on the NeoForge event bus.
     */
    public void register() {
        NeoForge.EVENT_BUS.register(this);
    }

    /**
     * Marks the listener closed so subsequent events no longer trigger rescans.
     */
    @Override
    public void close() {
        closed.set(true);
    }

    /**
     * Requests a rescan when a chunk finishes loading.
     *
     * @param event chunk load event
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        requestRescan();
    }

    /**
     * Requests a rescan when a chunk is unloaded.
     *
     * @param event chunk unload event
     */
    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        requestRescan();
    }

    /**
     * Requests a rescan when a block changes.
     *
     * @param event block change event
     */
    @SubscribeEvent
    public void onBlockChange(BlockEvent event) {
        requestRescan();
    }

    private void requestRescan() {
        if (!closed.get()) rescan.run();
    }
}
