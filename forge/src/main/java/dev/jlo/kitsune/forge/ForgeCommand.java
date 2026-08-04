package dev.jlo.kitsune.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;

import java.util.Objects;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class ForgeCommand {
    private ForgeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, ForgeRuntime runtime) {
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
