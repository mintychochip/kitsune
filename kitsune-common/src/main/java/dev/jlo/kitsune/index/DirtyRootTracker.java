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

/** Tracks dirty and deleted roots until indexed work completes. */
public final class DirtyRootTracker {
    private static final long[] RETRY_DELAYS = {20L, 100L, 200L};
    static final long SNAPSHOT_DELAY_TICKS = 1L;
    static final long TRANSFER_DELAY_TICKS = 5L;
    static final long TRANSFER_MAX_LATENCY_TICKS = 20L;
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
    private final Set<BlockKey> followUp = new LinkedHashSet<>();
    private final Map<BlockKey, Long> windowStart = new LinkedHashMap<>();

    /** Schedules a snapshot action for a changed root. */
    public void markDirty(BlockKey key, long eventTick) {
        scheduleEvent(key, eventTick, PendingAction.SNAPSHOT, SNAPSHOT_DELAY_TICKS, 0L);
    }

    /** Schedules a coalesced snapshot for hopper and other item transfers. */
    public void markTransferDirty(BlockKey key, long eventTick) {
        scheduleEvent(
            key,
            eventTick,
            PendingAction.SNAPSHOT,
            TRANSFER_DELAY_TICKS,
            TRANSFER_MAX_LATENCY_TICKS
        );
    }

    /** Schedules a delete action for a removed root. */
    public void markDeleted(BlockKey key, long eventTick) {
        followUp.remove(key);
        scheduleEvent(key, eventTick, PendingAction.DELETE, SNAPSHOT_DELAY_TICKS, 0L);
    }

    /** Returns whether the root has uncompleted snapshot or delete work. */
    public boolean isPending(BlockKey key) {
        Objects.requireNonNull(key, "Root key must not be null");
        return current.containsKey(key);
    }

    /** Claims work whose retry time has arrived. */
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

    /** Returns whether work is still the current revision for its root. */
    public boolean isCurrent(PendingRoot work) {
        Objects.requireNonNull(work, "Pending work must not be null");
        return work.equals(current.get(work.key()));
    }

    /** Completes a claimed work item, returning whether it was accepted. */
    public boolean complete(PendingRoot work, long currentTick) {
        Objects.requireNonNull(work, "Pending work must not be null");
        requireTick(currentTick);
        if (!isCurrent(work) || !claimed.remove(work)) return false;
        current.remove(work.key());
        retryCounts.remove(work.key());
        removeScheduled(work.key());
        windowStart.remove(work.key());
        if (followUp.remove(work.key())) {
            scheduleEvent(
                work.key(),
                currentTick,
                PendingAction.SNAPSHOT,
                SNAPSHOT_DELAY_TICKS,
                0L
            );
        }
        return true;
    }

    /** Releases failed work and schedules a retry when available. */
    public boolean fail(PendingRoot work, long currentTick) {
        Objects.requireNonNull(work, "Pending work must not be null");
        requireTick(currentTick);
        if (!isCurrent(work) || !claimed.remove(work)) return false;

        int retryCount = retryCounts.getOrDefault(work.key(), 0);
        if (retryCount >= RETRY_DELAYS.length) {
            current.remove(work.key());
            retryCounts.remove(work.key());
            removeScheduled(work.key());
            windowStart.remove(work.key());
            if (followUp.remove(work.key())) {
                scheduleEvent(
                    work.key(),
                    currentTick,
                    PendingAction.SNAPSHOT,
                    SNAPSHOT_DELAY_TICKS,
                    0L
                );
            }
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
        PendingAction action,
        long delayTicks,
        long maxLatencyTicks
    ) {
        Objects.requireNonNull(key, "Root key must not be null");
        requireTick(eventTick);
        if (action == PendingAction.SNAPSHOT && claimedSnapshot(key)) {
            followUp.add(key);
            return;
        }
        long revision = Math.incrementExact(revisions.getOrDefault(key, 0L));
        revisions.put(key, revision);
        PendingRoot work = new PendingRoot(key, revision, action);
        clearPriorWork(key);
        current.put(key, work);
        retryCounts.remove(key);
        windowStart.putIfAbsent(key, eventTick);
        long dueTick = Math.addExact(eventTick, delayTicks);
        if (maxLatencyTicks > 0L) {
            long cap = Math.addExact(windowStart.get(key), maxLatencyTicks);
            if (dueTick > cap) {
                dueTick = cap;
            }
        }
        schedule(work, dueTick);
    }

    private boolean claimedSnapshot(BlockKey key) {
        for (PendingRoot work : claimed) {
            if (work.key().equals(key) && work.action() == PendingAction.SNAPSHOT) {
                return true;
            }
        }
        return false;
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
