package dev.jlo.kitsune.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge mod entry point wiring the game event bus to the Kitsune runtime
 * lifecycle: starting it on server start, registering commands, forwarding
 * server ticks, and closing it on server stop.
 */
@Mod(NeoForgeMod.MOD_ID)
public final class NeoForgeMod {
    /** Mod identifier as declared in the {@code @Mod} annotation. */
    public static final String MOD_ID = "kitsune";

    private NeoForgeRuntime runtime;
    private CommandDispatcher<CommandSourceStack> pendingDispatcher;

    /**
     * Registers this instance on the NeoForge common event bus.
     *
     * @param modBus mod event bus
     */
    public NeoForgeMod(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(this);
    }

    /**
     * Starts the runtime on server start and registers commands, using a
     * dispatcher captured earlier when the runtime was unavailable.
     *
     * @param event server starting event
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        runtime = new NeoForgeRuntime();
        runtime.start(event.getServer());
        if (pendingDispatcher != null) {
            runtime.registerCommands(pendingDispatcher);
            pendingDispatcher = null;
        } else {
            runtime.registerCommands(event.getServer().getCommands().getDispatcher());
        }
    }

    /**
     * Registers commands on the given dispatcher, deferring the dispatcher when
     * the runtime has not yet started.
     *
     * @param event command-registration event
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        if (runtime == null) {
            pendingDispatcher = event.getDispatcher();
        } else {
            runtime.registerCommands(event.getDispatcher());
        }
    }

    /**
     * Forwards the post-tick event to the active runtime.
     *
     * @param event post-server-tick event
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (runtime != null) runtime.onServerTick();
    }

    /**
     * Closes the runtime and clears any pending dispatcher on server stop.
     *
     * @param event server stopping event
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        pendingDispatcher = null;
    }
}
