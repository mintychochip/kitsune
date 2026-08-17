package dev.jlo.kitsune.forge;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge mod entry point that wires the Kitsune runtime to server lifecycle and tick events.
 */
@Mod(ForgeMod.MOD_ID)
public final class ForgeMod {
    /** Mod id used for the {@code @Mod} annotation. */
    public static final String MOD_ID = "kitsune";

    private ForgeRuntime runtime;
    private CommandDispatcher<CommandSourceStack> pendingDispatcher;

    /**
     * Creates the mod entry point and registers it with the Forge event bus.
     */
    public ForgeMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * Starts the runtime when the server starts, registering commands once available.
     *
     * @param event the server starting event
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        runtime = new ForgeRuntime();
        runtime.start(event.getServer());
        if (pendingDispatcher != null) {
            runtime.registerCommands(pendingDispatcher);
            pendingDispatcher = null;
        } else {
            runtime.registerCommands(event.getServer().getCommands().getDispatcher());
        }
    }

    /**
     * Registers runtime commands, deferring them until the runtime starts if needed.
     *
     * @param event the command registration event
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
     * Advances the runtime at the end of each server tick.
     *
     * @param event the server tick event
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && runtime != null) runtime.onServerTick();
    }

    /**
     * Shuts down the runtime when the server stops.
     *
     * @param event the server stopping event
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
