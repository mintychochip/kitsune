package dev.jlo.kitsune.session;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.search.SearchGuard;
import dev.jlo.kitsune.ui.RenderedMarker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class SearchSessionManager implements SearchGuard {
    private final SessionScheduler scheduler;
    private final Duration markerLifetime;
    private final Map<UUID, PlayerSession> sessions = new HashMap<>();
    private long generation;

    /**
     * Creates a manager with the given scheduler and marker lifetime.
     *
     * @param scheduler      scheduler used to schedule session expiry
     * @param markerLifetime lifetime after which a session's markers expire
     * @throws NullPointerException     if either argument is null
     * @throws IllegalArgumentException if the marker lifetime is not positive
     */
    public SearchSessionManager(SessionScheduler scheduler, Duration markerLifetime) {
        this.scheduler = Objects.requireNonNull(scheduler, "Scheduler must not be null");
        Objects.requireNonNull(markerLifetime, "Marker lifetime must not be null");
        if (markerLifetime.isZero() || markerLifetime.isNegative()) {
            throw new IllegalArgumentException("Marker lifetime must be positive");
        }
        this.markerLifetime = markerLifetime;
    }

    /**
     * Begins a new search session for the given player, invalidating any prior
     * session and its markers.
     *
     * @param playerId player owning the session
     * @return the token identifying the new session
     * @throws NullPointerException if the player ID is null
     */
    public SearchToken begin(UUID playerId) {
        Objects.requireNonNull(playerId, "Player ID must not be null");
        synchronized (sessions) {
            PlayerSession previous = sessions.remove(playerId);
            if (previous != null) {
                cleanup(previous);
            }

            generation = Math.incrementExact(generation);
            SearchToken token = new SearchToken(playerId, generation);
            PlayerSession replacement = new PlayerSession(token);
            sessions.put(playerId, replacement);

            try {
                SessionTask expiryTask = scheduler.schedule(markerLifetime, () -> expire(token));
                if (expiryTask == null) {
                    throw new IllegalStateException("Scheduler returned a null task");
                }
                replacement.expiryTask = expiryTask;
                return token;
            } catch (RuntimeException | Error failure) {
                if (sessions.remove(playerId, replacement)) {
                    cleanup(replacement);
                }
                throw failure;
            }
        }
    }

    /**
     * Attaches rendered markers to the current session for the given token.
     *
     * <p>Markers are rejected when they duplicate an existing marker id with
     * different content, or when the token no longer identifies the current
     * session for the owning player. Rejected markers are removed and the
     * collection is left unmodified.
     *
     * @param token   token identifying the session
     * @param markers markers to attach
     * @return {@code true} if the session is still current and markers were
     *         attached, {@code false} if the session had ended
     * @throws NullPointerException if either argument is null or a marker has
     *         a null id or root
     */
    public boolean attach(
        SearchToken token,
        Collection<? extends RenderedMarker> markers
    ) {
        Objects.requireNonNull(token, "Token must not be null");
        Objects.requireNonNull(markers, "Markers must not be null");

        List<RenderedMarker> incoming = List.copyOf(markers);
        Map<UUID, TrackedMarker> unique = new LinkedHashMap<>();
        List<RenderedMarker> rejected = new ArrayList<>();
        try {
            for (RenderedMarker marker : incoming) {
                UUID id = Objects.requireNonNull(
                    marker.id(),
                    "Marker ID must not be null"
                );
                BlockKey root = Objects.requireNonNull(
                    marker.root(),
                    "Marker root must not be null"
                );
                TrackedMarker tracked = new TrackedMarker(root, marker);
                TrackedMarker prior = unique.putIfAbsent(id, tracked);
                if (prior != null && prior.marker() != marker) {
                    rejected.add(marker);
                }
            }
        } catch (RuntimeException failure) {
            removeAllMarkers(incoming);
            throw failure;
        }

        synchronized (sessions) {
            PlayerSession session = sessions.get(token.playerId());
            if (session == null || !token.equals(session.token)) {
                removeAllMarkers(incoming);
                return false;
            }

            for (Map.Entry<UUID, TrackedMarker> entry : unique.entrySet()) {
                TrackedMarker tracked = entry.getValue();
                TrackedMarker existing = session.markers.putIfAbsent(
                    entry.getKey(),
                    tracked
                );
                if (
                    existing != null
                    && existing.marker() != tracked.marker()
                ) {
                    rejected.add(tracked.marker());
                }
            }
            removeAllMarkers(rejected);
            return true;
        }
    }

    /**
     * Invalidates and clears every session tracking a marker rooted at the
     * given block.
     *
     * @param root block root to invalidate
     * @throws NullPointerException if the root is null
     */
    public void invalidateRoot(BlockKey root) {
        Objects.requireNonNull(root, "Root must not be null");
        synchronized (sessions) {
            Iterator<PlayerSession> iterator = sessions.values().iterator();
            while (iterator.hasNext()) {
                PlayerSession session = iterator.next();
                boolean affected = session.markers.values().stream()
                    .anyMatch(marker -> root.equals(marker.root()));
                if (affected) {
                    iterator.remove();
                    cleanup(session);
                }
            }
        }
    }

    @Override
    public boolean isCurrent(SearchToken token) {
        Objects.requireNonNull(token, "Token must not be null");
        synchronized (sessions) {
            PlayerSession session = sessions.get(token.playerId());
            return session != null && token.equals(session.token);
        }
    }

    /**
     * Reports whether the session for the given player still carries the
     * expected generation.
     *
     * @param playerId          player owning the session
     * @param expectedGeneration generation to compare against
     * @return {@code true} if the session is current
     */
    public boolean isCurrent(UUID playerId, long expectedGeneration) {
        return isCurrent(new SearchToken(playerId, expectedGeneration));
    }

    /**
     * Ends the current session for the given player, removing its markers.
     *
     * @param playerId player whose session to clear
     * @throws NullPointerException if the player ID is null
     */
    public void clear(UUID playerId) {
        Objects.requireNonNull(playerId, "Player ID must not be null");
        synchronized (sessions) {
            PlayerSession session = sessions.remove(playerId);
            if (session != null) {
                cleanup(session);
            }
        }
    }

    /**
     * Ends the session identified by the token, removing its markers.
     *
     * <p>No action is taken when the session has already been replaced or
     * ended.
     *
     * @param token token identifying the session to clear
     * @throws NullPointerException if the token is null
     */
    public void clear(SearchToken token) {
        Objects.requireNonNull(token, "Token must not be null");
        synchronized (sessions) {
            PlayerSession session = sessions.get(token.playerId());
            if (session == null || !token.equals(session.token)) {
                return;
            }
            sessions.remove(token.playerId());
            cleanup(session);
        }
    }

    /**
     * Ends every session, removing all tracked markers.
     */
    public void clearAll() {
        synchronized (sessions) {
            List<PlayerSession> detached = List.copyOf(sessions.values());
            sessions.clear();
            for (PlayerSession session : detached) {
                cleanup(session);
            }
        }
    }

    private void expire(SearchToken token) {
        synchronized (sessions) {
            PlayerSession session = sessions.get(token.playerId());
            if (session == null || !token.equals(session.token)) {
                return;
            }
            sessions.remove(token.playerId());
            cleanup(session);
        }
    }

    private static void cleanup(PlayerSession session) {
        SessionTask expiryTask = session.expiryTask;
        session.expiryTask = null;
        if (expiryTask != null) {
            try {
                expiryTask.cancel();
            } catch (RuntimeException ignored) {
                // Marker cleanup must continue after a scheduler failure.
            }
        }
        removeAllTrackedMarkers(session.markers.values());
        session.markers.clear();
    }

    private static void removeAllTrackedMarkers(
        Collection<TrackedMarker> markers
    ) {
        Set<RenderedMarker> removed = Collections.newSetFromMap(
            new IdentityHashMap<>()
        );
        for (TrackedMarker tracked : markers) {
            RenderedMarker marker = tracked.marker();
            if (!removed.add(marker)) {
                continue;
            }
            try {
                marker.remove();
            } catch (RuntimeException ignored) {
                // One broken entity must not strand its siblings.
            }
        }
    }

    private static void removeAllMarkers(Collection<? extends RenderedMarker> markers) {
        Set<RenderedMarker> removed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RenderedMarker marker : markers) {
            if (marker == null || !removed.add(marker)) {
                continue;
            }
            try {
                marker.remove();
            } catch (RuntimeException ignored) {
                // One broken entity must not strand its siblings.
            }
        }
    }

    private static final class PlayerSession {
        private final SearchToken token;
        private final Map<UUID, TrackedMarker> markers = new LinkedHashMap<>();
        private SessionTask expiryTask;

        private PlayerSession(SearchToken token) {
            this.token = token;
        }
    }
    private record TrackedMarker(
        BlockKey root,
        RenderedMarker marker
    ) {
        private TrackedMarker {
            Objects.requireNonNull(root, "Root must not be null");
            Objects.requireNonNull(marker, "Marker must not be null");
        }
    }

}
