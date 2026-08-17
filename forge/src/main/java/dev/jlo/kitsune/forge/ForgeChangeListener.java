package dev.jlo.kitsune.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for chunks and block changes and requests a rescan until {@link #close() closed}.
 */
public final class ForgeChangeListener implements AutoCloseable {
    private final Runnable rescan;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a listener that invokes {@code rescan} whenever a relevant change occurs.
     *
     * @param rescan callback invoked for every observed change while open
     */
    public ForgeChangeListener(Runnable rescan) {
        this.rescan = Objects.requireNonNull(rescan, "Rescan callback must not be null");
    }

    /**
     * Registers this listener with the Forge event bus.
     */
    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * Marks the listener closed so further changes are ignored.
     */
    @Override
    public void close() {
        closed.set(true);
    }

    /**
     * Requests a rescan when a chunk finishes loading.
     *
     * @param event the chunk load event
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        requestRescan();
    }

    /**
     * Requests a rescan when a chunk starts unloading.
     *
     * @param event the chunk unload event
     */
    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        requestRescan();
    }

    /**
     * Requests a rescan when a block changes in the world.
     *
     * @param event the block change event
     */
    @SubscribeEvent
    public void onBlockChange(BlockEvent event) {
        requestRescan();
    }

    private void requestRescan() {
        if (!closed.get()) rescan.run();
    }
}
