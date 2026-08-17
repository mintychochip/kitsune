package dev.jlo.kitsune.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Fabric mod entry point that starts and stops the Kitsune runtime as the
 * dedicated server lifecycle begins and ends.
 *
 * <p>On server start a new {@link FabricRuntime} is created and started; on
 * server stop the runtime is closed and released.
 */
public final class FabricMod implements ModInitializer {
    private FabricRuntime runtime;

    /**
     * Registers server start/stop lifecycle handlers that manage the runtime.
     */
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            runtime = new FabricRuntime();
            runtime.start(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (runtime != null) {
                runtime.close();
                runtime = null;
            }
        });
    }
}
