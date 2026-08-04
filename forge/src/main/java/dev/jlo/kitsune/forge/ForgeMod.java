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

@Mod(ForgeMod.MOD_ID)
public final class ForgeMod {
    public static final String MOD_ID = "kitsune";

    private ForgeRuntime runtime;
    private CommandDispatcher<CommandSourceStack> pendingDispatcher;

    public ForgeMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

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

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        if (runtime == null) {
            pendingDispatcher = event.getDispatcher();
        } else {
            runtime.registerCommands(event.getDispatcher());
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && runtime != null) runtime.onServerTick();
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
