package dev.jlo.kitsune.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;

import java.util.Objects;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Registers the {@code /kitsune} command with a Brigadier dispatcher.
 */
public final class ForgeCommand {
    private ForgeCommand() {
    }

    /**
     * Registers the {@code kitsune} command tree backed by the supplied runtime. Invoking the
     * bare command shows usage; invoking it with a query executes a search.
     *
     * @param dispatcher dispatcher to register the command with
     * @param runtime runtime that handles usage and search requests
     */
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
