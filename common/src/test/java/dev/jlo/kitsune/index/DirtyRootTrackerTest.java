package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirtyRootTrackerTest {
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORLD_ID_TWO = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void sameTickDirtyEventsCoalesceToOneNextTickSnapshotWithRevisionAdvancingToTen() {
        DirtyRootTracker tracker = new DirtyRootTracker();
        BlockKey root = key(WORLD_ID, 0, 64, 0);

        for (int index = 0; index < 10; index++) {
            tracker.markDirty(root, 100L);
        }

        assertTrue(tracker.claimDue(100L).isEmpty(), "Nothing should be due on the event tick");

        PendingRoot due = assertSingleDue(tracker, 101L);

        assertEquals(root, due.key());
        assertEquals(10L, due.revision());
        assertEquals(PendingAction.SNAPSHOT, due.action());
    }

    @Test
    void deletionSupersedesSnapshotAndLaterDirtyUsesHigherRevision() {
        DirtyRootTracker tracker = new DirtyRootTracker();
        BlockKey root = key(WORLD_ID, 1, 64, 0);

        tracker.markDirty(root, 1L);
        tracker.markDeleted(root, 3L);

        PendingRoot delete = assertSingleDue(tracker, 4L);

        assertEquals(root, delete.key());
        assertEquals(2L, delete.revision());
        assertEquals(PendingAction.DELETE, delete.action());

        tracker.markDirty(root, 5L);

        PendingRoot snapshot = assertSingleDue(tracker, 6L);

        assertEquals(root, snapshot.key());
        assertEquals(3L, snapshot.revision());
        assertEquals(PendingAction.SNAPSHOT, snapshot.action());
    }

    @Test
    void staleCompletionCannotEraseNewerDueRevision() {
        DirtyRootTracker tracker = new DirtyRootTracker();
        BlockKey root = key(WORLD_ID, 2, 64, 0);

        tracker.markDirty(root, 1L);
        PendingRoot stale = assertSingleDue(tracker, 2L);
        tracker.markDirty(root, 3L);

        assertFalse(tracker.complete(stale));

        PendingRoot current = assertSingleDue(tracker, 4L);

        assertEquals(2L, current.revision());
        assertEquals(PendingAction.SNAPSHOT, current.action());
    }

    @Test
    void successfulCompletionDoesNotResetTheRootRevisionTimeline() {
        DirtyRootTracker tracker = new DirtyRootTracker();
        BlockKey root = key(WORLD_ID, 6, 64, 0);
        tracker.markDirty(root, 1L);
        PendingRoot first = assertSingleDue(tracker, 2L);

        assertTrue(tracker.complete(first));
        tracker.markDeleted(root, 3L);

        PendingRoot second = assertSingleDue(tracker, 4L);
        assertEquals(2L, second.revision());
        assertEquals(PendingAction.DELETE, second.action());
    }

    @Test
    void claimDueIsDeterministicByWorldThenXThenYThenZ() {
        DirtyRootTracker tracker = new DirtyRootTracker();

        BlockKey unsortedOne = key(WORLD_ID, 1, 0, 0);
        BlockKey unsortedTwo = key(WORLD_ID_TWO, -2, 0, 0);
        BlockKey unsortedThree = key(WORLD_ID, 0, 0, 1);
        BlockKey unsortedFour = key(WORLD_ID, 0, -1, 2);

        tracker.markDirty(unsortedOne, 10L);
        tracker.markDirty(unsortedTwo, 10L);
        tracker.markDirty(unsortedThree, 10L);
        tracker.markDirty(unsortedFour, 10L);

        List<BlockKey> due = tracker.claimDue(11L).stream()
            .map(PendingRoot::key)
            .toList();

        assertEquals(
            List.of(unsortedFour, unsortedThree, unsortedOne, unsortedTwo),
            due
        );
    }

    @Test
    void failUses20Then100Then200DelayAndStopsAfterFourthFailedAttempt() {
        DirtyRootTracker tracker = new DirtyRootTracker();
        tracker.markDirty(key(WORLD_ID, 3, 64, 0), 1L);

        PendingRoot attempt1 = assertSingleDue(tracker, 2L);
        assertEquals(PendingAction.SNAPSHOT, attempt1.action());

        assertTrue(tracker.fail(attempt1, 2L));
        assertTrue(tracker.claimDue(21L).isEmpty());

        PendingRoot retry1 = assertSingleDue(tracker, 22L);
        assertEquals(1L, retry1.revision());

        assertTrue(tracker.fail(retry1, 22L));
        assertTrue(tracker.claimDue(121L).isEmpty());

        PendingRoot retry2 = assertSingleDue(tracker, 122L);
        assertEquals(1L, retry2.revision());

        assertTrue(tracker.fail(retry2, 122L));
        assertTrue(tracker.claimDue(321L).isEmpty());

        PendingRoot retry3 = assertSingleDue(tracker, 322L);

        assertFalse(tracker.fail(retry3, 322L));
        assertTrue(tracker.claimDue(323L).isEmpty());
    }

    @Test
    void newerRealEventResetsRetryCountAndRevisionTimeline() {
        DirtyRootTracker tracker = new DirtyRootTracker();

        tracker.markDirty(key(WORLD_ID, 4, 64, 0), 1L);
        PendingRoot original = assertSingleDue(tracker, 2L);
        assertTrue(tracker.fail(original, 2L));

        tracker.markDirty(key(WORLD_ID, 4, 64, 0), 3L);

        PendingRoot current = assertSingleDue(tracker, 4L);
        assertEquals(2L, current.revision());
        assertTrue(tracker.fail(current, 4L));
        assertTrue(
            tracker.claimDue(22L).isEmpty(),
            "The superseded revision-1 retry must not fire"
        );
        PendingRoot retried = assertSingleDue(tracker, 24L);

        assertEquals(2L, retried.revision());
    }

    @Test
    void nullAndNegativeInputIsRejected() {
        DirtyRootTracker tracker = new DirtyRootTracker();
        BlockKey root = key(WORLD_ID, 5, 64, 0);

        assertThrows(NullPointerException.class, () -> tracker.markDirty(null, 1L));
        assertThrows(NullPointerException.class, () -> tracker.markDeleted(null, 1L));
        assertThrows(NullPointerException.class, () -> tracker.complete(null));
        assertThrows(NullPointerException.class, () -> tracker.fail(null, 1L));

        assertThrows(IllegalArgumentException.class, () -> tracker.markDirty(root, -1L));
        assertThrows(IllegalArgumentException.class, () -> tracker.markDeleted(root, -1L));
        assertThrows(IllegalArgumentException.class, () -> tracker.claimDue(-1L));

        tracker.markDirty(root, 10L);
        PendingRoot due = assertSingleDue(tracker, 11L);
        assertThrows(IllegalArgumentException.class, () -> tracker.fail(due, -1L));
    }

    private static PendingRoot assertSingleDue(DirtyRootTracker tracker, long currentTick) {
        List<PendingRoot> due = tracker.claimDue(currentTick);
        assertEquals(1, due.size());
        return due.getFirst();
    }

    private static BlockKey key(UUID worldId, int x, int y, int z) {
        return new BlockKey(worldId, x, y, z);
    }
}
