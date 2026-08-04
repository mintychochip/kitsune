package dev.jlo.kitsune.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class FabricMod implements ModInitializer {
    private FabricRuntime runtime;

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
