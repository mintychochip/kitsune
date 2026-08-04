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

@Mod(NeoForgeMod.MOD_ID)
public final class NeoForgeMod {
    public static final String MOD_ID = "kitsune";

    private NeoForgeRuntime runtime;
    private CommandDispatcher<CommandSourceStack> pendingDispatcher;

    public NeoForgeMod(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(this);
    }

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

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        if (runtime == null) {
            pendingDispatcher = event.getDispatcher();
        } else {
            runtime.registerCommands(event.getDispatcher());
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (runtime != null) runtime.onServerTick();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        pendingDispatcher = null;
    }
}
