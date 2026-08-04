package dev.jlo.kitsune.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;

import java.util.Objects;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class FabricCommand {
    private FabricCommand() {
    }

    public static void register(
        CommandDispatcher<ServerCommandSource> dispatcher,
        FabricRuntime runtime
    ) {
        Objects.requireNonNull(dispatcher, "Dispatcher must not be null");
        Objects.requireNonNull(runtime, "Runtime must not be null");
        dispatcher.register(
            literal("kitsune")
                .executes(context -> {
                    runtime.sendUsage(context.getSource());
                    return 1;
                })
                .then(argument("query", StringArgumentType.greedyString())
                    .executes(context -> {
                        runtime.executeSearch(
                            context.getSource(),
                            StringArgumentType.getString(context, "query")
                        );
                        return 1;
                    }))
        );
    }
}
