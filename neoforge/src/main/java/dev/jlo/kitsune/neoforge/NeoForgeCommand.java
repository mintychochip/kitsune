package dev.jlo.kitsune.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;

import java.util.Objects;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Registers the {@code /kitsune} Brigadier command tree with a dispatcher.
 */
public final class NeoForgeCommand {
    private NeoForgeCommand() {
    }

    /**
     * Registers the {@code /kitsune} command: a bare invocation sends usage,
     * and a greedy {@code query} argument performs a search.
     *
     * @param dispatcher dispatcher to register the command on
     * @param runtime runtime that handles command execution
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, NeoForgeRuntime runtime) {
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
