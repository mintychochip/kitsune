package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies lexicographic slicing, immutability, and completion of the reconciliation cursor.
 */
class ReconciliationCursorTest {

    private static final UUID WORLD_ID = new UUID(0L, 1L);

    @Test
    void nextReturnsLexicographicallySortedSlicesWithoutWrapping() {
        ReconciliationCursor cursor = new ReconciliationCursor(List.of(
                key(2, 64, 2),
                key(1, 64, 3),
                key(1, 0, 0),
                key(1, -1, 5)
        ));

        assertFalse(cursor.complete());
        assertEquals(
                List.of(key(1, -1, 5), key(1, 0, 0)),
                cursor.next(2)
        );
        assertEquals(
                List.of(key(1, 64, 3), key(2, 64, 2)),
                cursor.next(2)
        );
        assertEquals(List.of(), cursor.next(1));
        assertTrue(cursor.complete());
    }

    @Test
    void nextReturnsAtMostBudgetAndCompletesOnLastSlice() {
        ReconciliationCursor cursor = new ReconciliationCursor(List.of(
                key(3, 64, 0),
                key(1, 64, 0),
                key(2, 64, 0)
        ));

        assertEquals(List.of(key(1, 64, 0), key(2, 64, 0)), cursor.next(2));
        assertEquals(List.of(key(3, 64, 0)), cursor.next(2));
        assertEquals(List.of(), cursor.next(2));
        assertTrue(cursor.complete());
    }

    @Test
    void nextProducesImmutableSlicesAndConstructorCopiesInput() {
        List<BlockKey> mutableRoots = new ArrayList<>(List.of(
                key(2, 64, 0),
                key(1, 64, 0)
        ));
        ReconciliationCursor cursor = new ReconciliationCursor(mutableRoots);

        mutableRoots.set(0, key(99, 64, 0));

        List<BlockKey> firstSlice = cursor.next(2);
        assertEquals(List.of(key(1, 64, 0), key(2, 64, 0)), firstSlice);
        assertThrows(
                UnsupportedOperationException.class,
                () -> firstSlice.add(key(100, 64, 0))
        );
    }

    @Test
    void newSourceMutationDoesNotEnterActivePass() {
        List<BlockKey> mutableRoots = new ArrayList<>(List.of(
                key(10, 64, 0),
                key(30, 64, 0),
                key(20, 64, 0)
        ));
        ReconciliationCursor cursor = new ReconciliationCursor(mutableRoots);

        assertEquals(List.of(key(10, 64, 0)), cursor.next(1));

        mutableRoots.add(key(40, 64, 0));
        mutableRoots.set(1, key(50, 64, 0));

        assertEquals(List.of(key(20, 64, 0), key(30, 64, 0)), cursor.next(2));
        assertEquals(List.of(), cursor.next(1));
    }

    @Test
    void emptyCursorIsCompleteAndAlwaysReturnsEmpty() {
        ReconciliationCursor cursor = new ReconciliationCursor(List.of());

        assertTrue(cursor.complete());
        assertEquals(List.of(), cursor.next(1));
        assertTrue(cursor.complete());
    }

    @Test
    void rejectsNullSourceAndNonPositiveBudget() {
        assertThrows(NullPointerException.class, () -> new ReconciliationCursor(null));

        ReconciliationCursor cursor = new ReconciliationCursor(List.of(key(1, 64, 0)));
        assertThrows(IllegalArgumentException.class, () -> cursor.next(0));
        assertThrows(IllegalArgumentException.class, () -> cursor.next(-5));
    }

    private static BlockKey key(int x, int y, int z) {
        return new BlockKey(WORLD_ID, x, y, z);
    }
}
