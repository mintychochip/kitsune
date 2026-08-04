package dev.jlo.kitsune.session;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.ui.RenderedMarker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchSessionManagerTest {
    @Test
    void beginReturnsMonotonicTokensForEachPlayerAndTracksCurrentTokens() {
        ManualSessionScheduler scheduler = new ManualSessionScheduler();
        SearchSessionManager manager = new SearchSessionManager(scheduler, Duration.ofSeconds(7));

        SearchToken firstAlice = manager.begin(player("alice"));
        SearchToken secondAlice = manager.begin(player("alice"));
        SearchToken firstBob = manager.begin(player("bob"));

        assertFalse(manager.isCurrent(firstAlice.playerId(), firstAlice.generation()));
        assertTrue(manager.isCurrent(secondAlice.playerId(), secondAlice.generation()));
        assertTrue(manager.isCurrent(firstBob.playerId(), firstBob.generation()));
        assertTrue(secondAlice.generation() > firstAlice.generation());
        assertFalse(manager.isCurrent(firstAlice.playerId(), secondAlice.generation() + 1));
        assertFalse(manager.isCurrent(firstAlice.playerId(), 0));
        assertFalse(manager.isCurrent(player("charlie"), secondAlice.generation()));
        assertNotEquals(firstAlice.generation(), secondAlice.generation());
    }

    @Test
    void beginReplacementCancelsPreviousExpiryAndRemovesPreviousMarkers() {
        ManualSessionScheduler scheduler = new ManualSessionScheduler();
        SearchSessionManager manager = new SearchSessionManager(scheduler, Duration.ofMillis(250));
        UUID player = player("replacement");

        SearchToken first = manager.begin(player);
        RecordingMarker firstMarker = new RecordingMarker("first-1");
        RecordingMarker secondMarker = new RecordingMarker("first-2");

        assertTrue(manager.attach(first, List.of(firstMarker, secondMarker)));
        assertEquals(1, scheduler.taskCount());
        ManualSessionScheduler.ScheduledTask firstTask = scheduler.task(0);
        assertEquals(Duration.ofMillis(250), firstTask.delay());
        assertFalse(firstTask.isCanceled());

        SearchToken replacement = manager.begin(player);
        assertEquals(first.playerId(), replacement.playerId());
        assertNotEquals(first, replacement);
        assertTrue(manager.isCurrent(replacement));
        assertFalse(manager.isCurrent(first));
        assertTrue(firstMarker.removed());
        assertTrue(secondMarker.removed());

        assertTrue(firstTask.isCanceled(), "replacement should cancel stale marker expiry task");
        assertEquals(2, scheduler.taskCount());
        assertFalse(scheduler.task(1).isCanceled());
    }

    @Test
    void lateAttachAfterReplacementIsRejectedAndCleanupStillRuns() {
        ManualSessionScheduler scheduler = new ManualSessionScheduler();
        SearchSessionManager manager = new SearchSessionManager(scheduler, Duration.ofSeconds(15));
        UUID player = player("late-attach");

        SearchToken first = manager.begin(player);
        SearchToken replacement = manager.begin(player);

        RecordingMarker stale = new RecordingMarker("late");
        RecordingMarker additional = new RecordingMarker("extra");
        assertFalse(manager.attach(first, List.of(stale, additional)));

        assertTrue(stale.removed());
        assertTrue(additional.removed());

        SearchToken replacementToken = replacement;
        assertTrue(manager.attach(replacementToken, List.of(new RecordingMarker("current"))));
        assertTrue(manager.isCurrent(replacementToken));
    }

    @Test
    void attachAcceptsCurrentTokenAndExpiryRemovesAllMarkers() {
        ManualSessionScheduler scheduler = new ManualSessionScheduler();
        SearchSessionManager manager = new SearchSessionManager(scheduler, Duration.ofSeconds(3));
        UUID player = player("expiry");

        SearchToken token = manager.begin(player);
        RecordingMarker first = new RecordingMarker("expiry-first");
        RecordingMarker second = new RecordingMarker("expiry-second");

        assertTrue(manager.attach(token, List.of(first, second)));
        assertFalse(first.removed());
        assertFalse(second.removed());

        assertEquals(1, scheduler.taskCount());
        scheduler.fire(0);
        assertTrue(first.removed());
        assertTrue(second.removed());
        assertFalse(manager.isCurrent(token));

        RecordingMarker late = new RecordingMarker("stale-on-expiry");
        assertFalse(manager.attach(token, List.of(late)));
        assertTrue(late.removed());
    }

    @Test
    void clearAndClearAllCoverPlayerLifecycleAndDisableTransitions() {
        for (TerminalTransition transition : TerminalTransition.values()) {
            ManualSessionScheduler scheduler = new ManualSessionScheduler();
            SearchSessionManager manager = new SearchSessionManager(scheduler, Duration.ofSeconds(10));
            SearchToken token = manager.begin(player(transition.name()));
            RecordingMarker marker = new RecordingMarker("transition-" + transition);
            assertTrue(manager.attach(token, List.of(marker)));

            transition.apply(manager,
                transition == TerminalTransition.DISABLE ? null : token.playerId(),
                marker);

            assertTrue(marker.removed(), transition.name() + " should remove marker");
            assertFalse(manager.isCurrent(token), transition.name() + " should invalidate token");
        }

        {
            ManualSessionScheduler scheduler = new ManualSessionScheduler();
            SearchSessionManager manager = new SearchSessionManager(scheduler, Duration.ofSeconds(10));
            UUID alice = player("alice-life");
            UUID bob = player("bob-life");

            SearchToken aliceToken = manager.begin(alice);
            SearchToken bobToken = manager.begin(bob);
            RecordingMarker aliceMarker = new RecordingMarker("alice");
            RecordingMarker bobMarker = new RecordingMarker("bob");
            assertTrue(manager.attach(aliceToken, List.of(aliceMarker)));
            assertTrue(manager.attach(bobToken, List.of(bobMarker)));

            manager.clear(alice);
            assertTrue(aliceMarker.removed());
            assertFalse(bobMarker.removed());
            assertTrue(manager.isCurrent(bobToken), "bob should remain current after clearing alice");

            manager.clearAll();
            assertTrue(bobMarker.removed());
            assertFalse(manager.isCurrent(bobToken));
        }
    }

    @Test
    void differentPlayersDoNotAffectEachOther() {
        ManualSessionScheduler scheduler = new ManualSessionScheduler();
        SearchSessionManager manager = new SearchSessionManager(scheduler, Duration.ofSeconds(12));

        UUID alice = player("alice-isolated");
        UUID bob = player("bob-isolated");

        SearchToken aliceToken = manager.begin(alice);
        SearchToken bobToken = manager.begin(bob);
        RecordingMarker aliceMarker = new RecordingMarker("alice-isolated");
        RecordingMarker bobMarker = new RecordingMarker("bob-isolated");

        assertTrue(manager.attach(aliceToken, List.of(aliceMarker)));
        assertTrue(manager.attach(bobToken, List.of(bobMarker)));

        SearchToken bobReplacement = manager.begin(bob);
        assertTrue(manager.isCurrent(bobReplacement));
        assertFalse(manager.isCurrent(bobToken));
        assertFalse(aliceMarker.removed(), "alice markers must survive bob replacement");
        assertTrue(manager.isCurrent(bobReplacement));
        assertTrue(manager.isCurrent(aliceToken));

        assertTrue(manager.isCurrent(new SearchToken(alice, aliceToken.generation())));
        assertFalse(manager.isCurrent(new SearchToken(alice, aliceToken.generation() + 1)));
    }

    @Test
    void rootInvalidationClearsOnlySessionsThatExposeThatRoot() {
        ManualSessionScheduler scheduler = new ManualSessionScheduler();
        SearchSessionManager manager = new SearchSessionManager(
            scheduler,
            Duration.ofSeconds(20)
        );
        BlockKey invalidated = root("invalidated", 1);
        BlockKey retained = root("retained", 2);
        SearchToken alice = manager.begin(player("root-alice"));
        SearchToken bob = manager.begin(player("root-bob"));
        RecordingMarker aliceFirst = new RecordingMarker(
            "root-alice-first",
            invalidated
        );
        RecordingMarker aliceSecond = new RecordingMarker(
            "root-alice-second",
            retained
        );
        RecordingMarker bobMarker = new RecordingMarker(
            "root-bob-marker",
            retained
        );

        assertTrue(manager.attach(alice, List.of(aliceFirst, aliceSecond)));
        assertTrue(manager.attach(bob, List.of(bobMarker)));

        manager.invalidateRoot(invalidated);

        assertFalse(manager.isCurrent(alice));
        assertTrue(aliceFirst.removed());
        assertTrue(aliceSecond.removed());
        assertTrue(manager.isCurrent(bob));
        assertFalse(bobMarker.removed());
    }

    @Test
    void markerRemovalFailuresDoNotSkipSiblingsAndCanBeRetriedIdempotently() {
        ManualSessionScheduler scheduler = new ManualSessionScheduler();
        SearchSessionManager manager = new SearchSessionManager(scheduler, Duration.ofSeconds(20));

        UUID player = player("marker-failure");
        SearchToken token = manager.begin(player);

        RecordingMarker healthy = new RecordingMarker("healthy");
        RecordingMarker failing = new RecordingMarker("failing", true);
        assertTrue(manager.attach(token, List.of(healthy, failing)));
        assertFalse(healthy.removed());
        assertFalse(failing.removed());

        assertDoesNotThrow(() -> manager.clear(player));
        assertTrue(healthy.removed());
        assertTrue(failing.removed());

        assertEquals(1, healthy.removeCount());
        assertEquals(1, failing.removeCount());

        assertDoesNotThrow(() -> manager.clear(player));
        assertDoesNotThrow(() -> manager.clearAll());
        assertEquals(1, healthy.removeCount());
        assertEquals(1, failing.removeCount());
    }

    @Test
    void schedulingFailureInvalidatesTheOldSessionAndPublishesNoReplacement() {
        ManualSessionScheduler scheduler = new ManualSessionScheduler();
        SearchSessionManager manager = new SearchSessionManager(scheduler, Duration.ofSeconds(20));
        UUID player = player("schedule-failure");
        SearchToken first = manager.begin(player);
        RecordingMarker marker = new RecordingMarker("schedule-failure-marker");
        assertTrue(manager.attach(first, List.of(marker)));

        scheduler.failNext();
        assertThrows(IllegalStateException.class, () -> manager.begin(player));

        assertTrue(marker.removed());
        assertFalse(manager.isCurrent(first));

        SearchToken recovered = manager.begin(player);
        assertTrue(manager.isCurrent(recovered));
        assertTrue(recovered.generation() > first.generation());
    }

    @Test
    void duplicateMarkerIdsNeverLeakUnretainedMarkers() {
        ManualSessionScheduler scheduler = new ManualSessionScheduler();
        SearchSessionManager manager = new SearchSessionManager(scheduler, Duration.ofSeconds(20));
        SearchToken token = manager.begin(player("duplicate-markers"));
        RecordingMarker retained = new RecordingMarker("same-id");
        RecordingMarker duplicate = new RecordingMarker("same-id");

        assertTrue(manager.attach(token, List.of(retained, duplicate)));
        assertFalse(retained.removed());
        assertTrue(duplicate.removed());

        RecordingMarker laterDuplicate = new RecordingMarker("same-id");
        assertTrue(manager.attach(token, List.of(laterDuplicate)));
        assertTrue(laterDuplicate.removed());

        manager.clear(token);
        assertTrue(retained.removed());
        assertEquals(1, retained.removeCount());
    }

    @Test
    void constructorRejectsNonPositiveMarkerLifetime() {
        ManualSessionScheduler scheduler = new ManualSessionScheduler();

        assertThrows(IllegalArgumentException.class,
            () -> new SearchSessionManager(scheduler, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
            () -> new SearchSessionManager(scheduler, Duration.ofMillis(-1)));
        assertDoesNotThrow(() -> new SearchSessionManager(scheduler, Duration.ofNanos(1)));
    }


    private static UUID player(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static BlockKey root(String world, int x) {
        return new BlockKey(player(world), x, 64, 0);
    }

    private static final class RecordingMarker implements RenderedMarker {
        private final UUID id;
        private final BlockKey root;
        private final boolean throwOnRemove;
        private int removeCalls;

        private RecordingMarker(String label) {
            this(label, SearchSessionManagerTest.root("default-root", 0), false);
        }

        private RecordingMarker(String label, boolean throwOnRemove) {
            this(
                label,
                SearchSessionManagerTest.root("default-root", 0),
                throwOnRemove
            );
        }

        private RecordingMarker(String label, BlockKey root) {
            this(label, root, false);
        }

        private RecordingMarker(
            String label,
            BlockKey root,
            boolean throwOnRemove
        ) {
            this.id = UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8));
            this.root = root;
            this.throwOnRemove = throwOnRemove;
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public BlockKey root() {
            return root;
        }

        @Override
        public void remove() {
            removeCalls++;
            if (throwOnRemove) {
                throw new RuntimeException("forced remove failure");
            }
        }

        private boolean removed() {
            return removeCalls > 0;
        }

        private int removeCount() {
            return removeCalls;
        }
    }

    private static enum TerminalTransition {
        QUIT {
            @Override
            public void apply(SearchSessionManager manager, UUID playerId, RecordingMarker marker) {
                manager.clear(playerId);
            }
        },
        WORLD_CHANGED {
            @Override
            public void apply(SearchSessionManager manager, UUID playerId, RecordingMarker marker) {
                manager.clear(playerId);
            }
        },
        ROOT_INVALIDATED {
            @Override
            public void apply(SearchSessionManager manager, UUID playerId, RecordingMarker marker) {
                manager.clear(playerId);
            }
        },
        DISABLE {
            @Override
            public void apply(SearchSessionManager manager, UUID playerId, RecordingMarker marker) {
                manager.clearAll();
            }
        };

        public abstract void apply(SearchSessionManager manager, UUID playerId, RecordingMarker marker);
    }

    private static final class ManualSessionScheduler implements SessionScheduler {
        private final List<ScheduledTask> tasks = new ArrayList<>();
        private boolean failNext;

        private void failNext() {
            failNext = true;
        }

        @Override
        public SessionTask schedule(Duration delay, Runnable action) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("forced schedule failure");
            }
            ScheduledTask task = new ScheduledTask(delay, action);
            tasks.add(task);
            return task;
        }

        private ScheduledTask task(int index) {
            return tasks.get(index);
        }

        private int taskCount() {
            return tasks.size();
        }

        private void fire(int index) {
            tasks.get(index).fire();
        }

        private static final class ScheduledTask implements SessionTask {
            private final Duration delay;
            private final Runnable action;
            private boolean canceled;
            private boolean fired;

            private ScheduledTask(Duration delay, Runnable action) {
                this.delay = delay;
                this.action = action;
            }

            @Override
            public void cancel() {
                canceled = true;
            }

            private void fire() {
                if (!canceled && !fired) {
                    action.run();
                }
                fired = true;
            }

            private boolean isCanceled() {
                return canceled;
            }

            private boolean isFired() {
                return fired;
            }

            private Duration delay() {
                return delay;
            }
        }
    }
}
