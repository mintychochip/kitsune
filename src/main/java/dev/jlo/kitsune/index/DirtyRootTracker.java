package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.BlockKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public final class DirtyRootTracker {
    private static final long[] RETRY_DELAYS = {20L, 100L, 200L};
    private static final Comparator<PendingRoot> WORK_ORDER = Comparator
        .comparing((PendingRoot work) -> work.key().worldId().toString())
        .thenComparingInt(work -> work.key().x())
        .thenComparingInt(work -> work.key().y())
        .thenComparingInt(work -> work.key().z());

    private final Map<BlockKey, Long> revisions = new LinkedHashMap<>();
    private final Map<BlockKey, PendingRoot> current = new LinkedHashMap<>();
    private final Map<BlockKey, Integer> retryCounts = new LinkedHashMap<>();
    private final NavigableMap<Long, Set<PendingRoot>> due = new TreeMap<>();
    private final Map<BlockKey, Long> dueTicks = new LinkedHashMap<>();
    private final Set<PendingRoot> claimed = new LinkedHashSet<>();

    public void markDirty(BlockKey key, long eventTick) {
        scheduleEvent(key, eventTick, PendingAction.SNAPSHOT);
    }

    public void markDeleted(BlockKey key, long eventTick) {
        scheduleEvent(key, eventTick, PendingAction.DELETE);
    }

    public List<PendingRoot> claimDue(long currentTick) {
        requireTick(currentTick);
        List<PendingRoot> result = new ArrayList<>();
        List<Long> elapsedTicks = new ArrayList<>(
            due.headMap(currentTick, true).keySet()
        );
        for (Long elapsedTick : elapsedTicks) {
            Set<PendingRoot> scheduled = due.remove(elapsedTick);
            if (scheduled == null) continue;
            for (PendingRoot candidate : scheduled) {
                if (!candidate.equals(current.get(candidate.key()))) continue;
                if (!Objects.equals(dueTicks.get(candidate.key()), elapsedTick)) {
                    continue;
                }
                dueTicks.remove(candidate.key());
                claimed.add(candidate);
                result.add(candidate);
            }
        }
        result.sort(WORK_ORDER);
        return List.copyOf(result);
    }

    public boolean isCurrent(PendingRoot work) {
        Objects.requireNonNull(work, "Pending work must not be null");
        return work.equals(current.get(work.key()));
    }

    public boolean complete(PendingRoot work) {
        Objects.requireNonNull(work, "Pending work must not be null");
        if (!isCurrent(work) || !claimed.remove(work)) return false;
        current.remove(work.key());
        retryCounts.remove(work.key());
        removeScheduled(work.key());
        return true;
    }

    public boolean fail(PendingRoot work, long currentTick) {
        Objects.requireNonNull(work, "Pending work must not be null");
        requireTick(currentTick);
        if (!isCurrent(work) || !claimed.remove(work)) return false;

        int retryCount = retryCounts.getOrDefault(work.key(), 0);
        if (retryCount >= RETRY_DELAYS.length) {
            current.remove(work.key());
            retryCounts.remove(work.key());
            removeScheduled(work.key());
            return false;
        }

        long retryTick = Math.addExact(
            currentTick,
            RETRY_DELAYS[retryCount]
        );
        retryCounts.put(work.key(), retryCount + 1);
        schedule(work, retryTick);
        return true;
    }

    private void scheduleEvent(
        BlockKey key,
        long eventTick,
        PendingAction action
    ) {
        Objects.requireNonNull(key, "Root key must not be null");
        requireTick(eventTick);
        long revision = Math.incrementExact(revisions.getOrDefault(key, 0L));
        revisions.put(key, revision);
        PendingRoot work = new PendingRoot(key, revision, action);
        clearPriorWork(key);
        current.put(key, work);
        retryCounts.remove(key);
        schedule(work, Math.addExact(eventTick, 1L));
    }

    private void schedule(PendingRoot work, long dueTick) {
        removeScheduled(work.key());
        dueTicks.put(work.key(), dueTick);
        due.computeIfAbsent(dueTick, ignored -> new LinkedHashSet<>())
            .add(work);
    }

    private void clearPriorWork(BlockKey key) {
        removeScheduled(key);
        claimed.removeIf(work -> work.key().equals(key));
    }

    private void removeScheduled(BlockKey key) {
        Long tick = dueTicks.remove(key);
        if (tick == null) return;
        Set<PendingRoot> scheduled = due.get(tick);
        if (scheduled == null) return;
        scheduled.removeIf(work -> work.key().equals(key));
        if (scheduled.isEmpty()) due.remove(tick);
    }

    private static void requireTick(long tick) {
        if (tick < 0) {
            throw new IllegalArgumentException("Tick must not be negative");
        }
    }
}
