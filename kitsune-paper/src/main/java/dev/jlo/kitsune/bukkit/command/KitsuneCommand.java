package dev.jlo.kitsune.command;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.search.AllowedRoot;
import dev.jlo.kitsune.search.LiveRootAccess;
import dev.jlo.kitsune.search.LiveRootSnapshot;
import dev.jlo.kitsune.search.ItemMatch;

import dev.jlo.kitsune.search.RootMatch;
import dev.jlo.kitsune.search.SearchContext;
import dev.jlo.kitsune.search.SearchOutcome;
import dev.jlo.kitsune.search.SearchPolicy;
import dev.jlo.kitsune.search.SearchService;
import dev.jlo.kitsune.search.ServerThreadBridge;
import dev.jlo.kitsune.session.SearchSessionManager;
import dev.jlo.kitsune.session.SearchToken;
import dev.jlo.kitsune.ui.ChatTreeRenderer;
import dev.jlo.kitsune.ui.MarkerRenderer;
import dev.jlo.kitsune.ui.RenderedMarker;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Comparator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
/**
 * Executes the {@code /kitsune} command, running searches and rendering results.
 */
public final class KitsuneCommand implements CommandExecutor {
    private static final String PERMISSION = "kitsune.search";
    private static final String NO_PLAYER_MESSAGE = "Only players can use this command.";
    private static final String NO_PERMISSION_MESSAGE = "You do not have permission to use this command.";
    private static final String SERVICE_UNAVAILABLE_MESSAGE = "Search is currently unavailable.";
    private static final String GENERIC_ERROR_MESSAGE = "Search failed. Please try again later.";

    private final Server server;
    private final SearchService searchService;
    private final SearchSessionManager sessions;
    private final ServerThreadBridge serverBridge;
    private final LiveRootAccess liveRoots;
    private final SearchPolicy policy;
    private final ChatTreeRenderer chatRenderer;
    private final MarkerRenderer markerRenderer;

    private volatile boolean stopped;

    /**
     * Creates the command executor with the services needed to run and render searches.
     *
     * @param server Bukkit server
     * @param searchService performs searches
     * @param sessions tracks active player search sessions
     * @param serverBridge dispatches work to the server thread
     * @param liveRoots revalidates live roots
     * @param policy search policy constraints
     * @param chatRenderer renders chat output
     * @param markerRenderer renders world markers
     */
    public KitsuneCommand(
        Server server,
        SearchService searchService,
        SearchSessionManager sessions,
        ServerThreadBridge serverBridge,
        LiveRootAccess liveRoots,
        SearchPolicy policy,
        ChatTreeRenderer chatRenderer,
        MarkerRenderer markerRenderer
    ) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
        this.searchService = Objects.requireNonNull(searchService, "SearchService must not be null");
        this.sessions = Objects.requireNonNull(sessions, "Search session manager must not be null");
        this.serverBridge = Objects.requireNonNull(serverBridge, "Server thread bridge must not be null");
        this.liveRoots = Objects.requireNonNull(liveRoots, "Live root access must not be null");
        this.policy = Objects.requireNonNull(policy, "Policy must not be null");
        this.chatRenderer = Objects.requireNonNull(chatRenderer, "Chat renderer must not be null");
        this.markerRenderer = Objects.requireNonNull(markerRenderer, "Marker renderer must not be null");
    }

    /**
     * Parses and executes a search command, or rejects it when unavailable,
     * the sender is not a player, or the sender lacks permission.
     *
     * @param sender command sender
     * @param command command being executed
     * @param label command label used
     * @param args command arguments
     * @return {@code true} when the command was handled
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (stopped) {
            sender.sendMessage(SERVICE_UNAVAILABLE_MESSAGE);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(NO_PLAYER_MESSAGE);
            return true;
        }

        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(NO_PERMISSION_MESSAGE);
            return true;
        }

        final SearchRequest request;
        try {
            request = SearchRequest.parse(args);
        } catch (IllegalArgumentException parseFailure) {
            player.sendMessage(parseFailure.getMessage());
            return true;
        }

        UUID playerId = player.getUniqueId();
        World world = player.getWorld();
        Location location = player.getLocation();
        BlockKey origin = new BlockKey(
            world.getUID(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ()
        );
        SearchContext context = new SearchContext(playerId, origin);

        SearchToken token;
        try {
            token = sessions.begin(playerId);
        } catch (RuntimeException failure) {
            player.sendMessage(GENERIC_ERROR_MESSAGE);
            return true;
        }

        try {
            searchService.search(context, request, token, sessions)
                .whenComplete((outcome, failure) ->
                    handleCompletion(playerId, context, request, token, outcome, failure)
                );
        } catch (RuntimeException failure) {
            sessions.clear(token);
            player.sendMessage(GENERIC_ERROR_MESSAGE);
        }

        return true;
    }

    /**
     * Stops the command, rejecting further invocations and clearing active sessions.
     */
    public void stop() {
        stopped = true;
        sessions.clearAll();
    }

    private void handleCompletion(
        UUID playerId,
        SearchContext context,
        SearchRequest request,
        SearchToken token,
        SearchOutcome outcome,
        Throwable failure
    ) {
        dispatchCompletion(serverBridge, sessions, token, () -> {
            Player player = null;
            try {
                if (stopped) {
                    sessions.clear(token);
                    return;
                }

                player = server.getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    sessions.clear(token);
                    return;
                }
                if (!isCurrentPlayerInOriginWorld(player, context, token)) {
                    sessions.clear(token);
                    return;
                }
                if (failure != null) {
                    sessions.clear(token);
                    player.sendMessage(GENERIC_ERROR_MESSAGE);
                    return;
                }

                SearchOutcome normalized = normalizeOutcome(outcome);
                switch (normalized.status()) {
                    case CANCELED -> sessions.clear(token);
                    case NO_MATCHES -> {
                        sessions.clear(token);
                        player.sendMessage(
                            "No accessible nearby storage matched " + request.query() + "."
                        );
                    }
                    case UNSUPPORTED_QUERY, INDEX_WARMING, FAILURE -> {
                        sessions.clear(token);
                        player.sendMessage(GENERIC_ERROR_MESSAGE);
                    }
                    case SUCCESS -> renderSuccess(
                        player,
                        context,
                        request,
                        token,
                        normalized
                    );
                }
            } catch (RuntimeException renderFailure) {
                sessions.clear(token);
                if (player != null && player.isOnline()) {
                    try {
                        player.sendMessage(GENERIC_ERROR_MESSAGE);
                    } catch (RuntimeException ignoredSendFailure) {
                        // The session is already terminal.
                    }
                }
            }
        });
    }

    static void dispatchCompletion(
        ServerThreadBridge bridge,
        SearchSessionManager sessions,
        SearchToken token,
        Runnable completion
    ) {
        Objects.requireNonNull(bridge, "Server bridge must not be null");
        Objects.requireNonNull(sessions, "Sessions must not be null");
        Objects.requireNonNull(token, "Token must not be null");
        Objects.requireNonNull(completion, "Completion must not be null");
        try {
            CompletableFuture<Void> dispatched = Objects.requireNonNull(
                bridge.run(completion),
                "Server bridge returned a null future"
            );
            dispatched.whenComplete((ignored, dispatchFailure) -> {
                if (dispatchFailure != null) {
                    sessions.clear(token);
                }
            });
        } catch (RuntimeException dispatchFailure) {
            sessions.clear(token);
        } catch (Error dispatchFailure) {
            sessions.clear(token);
            throw dispatchFailure;
        }
    }

    private void renderSuccess(
        Player player,
        SearchContext context,
        SearchRequest request,
        SearchToken token,
        SearchOutcome outcome
    ) {
        if (!sessions.isCurrent(token)) {
            return;
        }

        SearchOutcome visible = revalidateOutcome(context, outcome);
        if (visible.status() == SearchOutcome.Status.NO_MATCHES) {
            sessions.clear(token);
            player.sendMessage(
                "No accessible nearby storage matched " + request.query() + "."
            );
            return;
        }

        Component chat = request.verbose()
            ? chatRenderer.verboseChat(request.query(), visible)
            : chatRenderer.normalChat(visible);
        List<RenderedMarker> markers = new ArrayList<>(visible.roots().size());
        for (RootMatch root : visible.roots()) {
            String text = request.verbose()
                ? chatRenderer.verboseMarker(request.query(), root)
                : chatRenderer.normalMarker(request.query(), root);
            ItemMatch featuredItem = bestItemMatch(root);
            markerRenderer.spawn(player, root.key(), text, featuredItem).ifPresent(markers::add);
        }
        if (sessions.attach(token, markers)) {
            ((Audience) player).sendMessage(chat);
        }
    }

    private SearchOutcome normalizeOutcome(SearchOutcome outcome) {
        if (outcome == null || outcome.status() == null) {
            return SearchOutcome.failure();
        }
        return outcome;
    }

    private SearchOutcome revalidateOutcome(SearchContext context, SearchOutcome outcome) {
        if (outcome.status() != SearchOutcome.Status.SUCCESS) {
            return outcome;
        }

        List<RootMatch> visibleRoots = new ArrayList<>();
        int visibleStacks = 0;

        for (RootMatch root : outcome.roots()) {
            LiveRootSnapshot live = liveRoots.validateSnapshot(
                context,
                root.identity(),
                policy.radius()
            );
            if (live == null) {
                continue;
            }
            AllowedRoot allowed = live.allowedRoot();
            if (!root.identity().equals(allowed.identity())) {
                continue;
            }

            List<ItemMatch> itemMatches = LiveItemHoverRefresher.refresh(
                root,
                live.draft()
            );
            visibleStacks = Math.addExact(visibleStacks, root.totalMatchingStacks());
            if (Double.compare(root.distance(), allowed.distance()) == 0
                && itemMatches == root.itemMatches()) {
                visibleRoots.add(root);
            } else {
                visibleRoots.add(new RootMatch(
                    allowed.identity(),
                    allowed.distance(),
                    root.bestScore(),
                    root.totalMatchingStacks(),
                    itemMatches
                ));
            }
        }

        if (visibleRoots.isEmpty()) {
            return SearchOutcome.noMatches();
        }

        return SearchOutcome.success(
            visibleRoots,
            visibleRoots.size(),
            visibleStacks
        );
    }

    private static ItemMatch bestItemMatch(RootMatch root) {
        return root.itemMatches().stream()
            .max(Comparator.comparingDouble(ItemMatch::score))
            .orElseThrow(() -> new IllegalStateException("Root match must expose item matches"));
    }


    private boolean isCurrentPlayerInOriginWorld(Player player, SearchContext context, SearchToken token) {
        if (!sessions.isCurrent(token)) {
            return false;
        }

        World world = player.getWorld();
        return world != null && world.getUID().equals(context.origin().worldId());
    }
}
