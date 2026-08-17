package dev.jlo.kitsune.index;

import java.util.Objects;

import dev.jlo.kitsune.model.BlockKey;

/** Resolves live storage roots into canonical logical inventories. */
public final class RootResolver<T> {

    /** States produced while resolving a root. */
    public enum Status {
        RESOLVED,
        MISSING,
        UNAVAILABLE,
        UNRESOLVED_LOOT,
        UNSUPPORTED
    }


    public record RootProbe<T>(
            BlockKey key,
            String blockType,
            T logicalInventory,
            boolean persistentBlockInventory,
            boolean unresolvedLoot,
            BlockKey connectedHalf,
            boolean connectedHalfLoaded) {

        public RootProbe {
            Objects.requireNonNull(key, "Key must not be null");
        }
    }

    /** Provides live availability and root probes to the resolver. */
    public interface LiveAccess<T> {
        default boolean isAvailable(BlockKey key) {
            return true;
        }

        RootProbe<T> inspect(BlockKey key);
    }

    public record Resolution<T>(
            Status status,
            BlockKey key,
            String blockType,
            T logicalInventory) {

        public Resolution {
            Objects.requireNonNull(status, "Status must not be null");
            if (status == Status.RESOLVED) {
                Objects.requireNonNull(key, "Resolved key must not be null");
                Objects.requireNonNull(blockType, "Resolved blockType must not be null");
                if (blockType.isBlank()) {
                    throw new IllegalArgumentException("Resolved blockType must not be blank");
                }
                Objects.requireNonNull(logicalInventory, "Resolved logicalInventory must not be null");
            } else {
                key = null;
                blockType = null;
                logicalInventory = null;
            }
        }

        public static <T> Resolution<T> resolved(BlockKey key, String blockType, T logicalInventory) {
            return new Resolution<>(Status.RESOLVED, key, blockType, logicalInventory);
        }

        public static <T> Resolution<T> missing() {
            return new Resolution<>(Status.MISSING, null, null, null);
        }

        public static <T> Resolution<T> unsupported() {
            return new Resolution<>(Status.UNSUPPORTED, null, null, null);
        }

        public static <T> Resolution<T> unavailable() {
            return new Resolution<>(Status.UNAVAILABLE, null, null, null);
        }

        public static <T> Resolution<T> unresolvedLoot() {
            return new Resolution<>(Status.UNRESOLVED_LOOT, null, null, null);
        }
    }

    private final LiveAccess<T> liveAccess;

    /** Creates a resolver backed by live access operations. */
    public RootResolver(LiveAccess<T> liveAccess) {
        this.liveAccess = Objects.requireNonNull(liveAccess, "LiveAccess must not be null");
    }

    /** Resolves a root key and reports its status and inventory. */
    public Resolution<T> resolve(BlockKey key) {
        Objects.requireNonNull(key, "Key must not be null");

        if (!liveAccess.isAvailable(key)) {
            return Resolution.unavailable();
        }

        RootProbe<T> probe = liveAccess.inspect(key);
        if (probe == null) {
            return Resolution.missing();
        }

        if (probe.key() == null || !probe.key().equals(key)) {
            return Resolution.missing();
        }

        if (!probe.persistentBlockInventory()) {
            return Resolution.unsupported();
        }

        if (probe.blockType() == null || probe.blockType().isBlank()) {
            return Resolution.unsupported();
        }

        if (isEnderChest(probe.blockType())) {
            return Resolution.unsupported();
        }

        if (probe.unresolvedLoot()) {
            return Resolution.unresolvedLoot();
        }

        if (probe.connectedHalf() == null) {
            if (probe.logicalInventory() == null) {
                return Resolution.unavailable();
            }
            return Resolution.resolved(key, probe.blockType(), probe.logicalInventory());
        }

        return resolveDouble(key, probe);
    }

    private Resolution<T> resolveDouble(BlockKey key, RootProbe<T> probe) {
        BlockKey connectedHalf = probe.connectedHalf();
        BlockKey canonical;
        try {
            canonical = canonicalDoubleChest(key, connectedHalf);
        } catch (IllegalArgumentException ex) {
            return Resolution.unavailable();
        }

        if (!probe.connectedHalfLoaded()) {
            return Resolution.unavailable();
        }

        RootProbe<T> other = liveAccess.inspect(connectedHalf);
        if (other == null) {
            return Resolution.unavailable();
        }

        if (!other.persistentBlockInventory()) {
            return Resolution.unavailable();
        }

        if (other.key() == null || !other.key().equals(connectedHalf)) {
            return Resolution.unavailable();
        }

        if (!probe.blockType().equals(other.blockType())) {
            return Resolution.unavailable();
        }

        if (!other.connectedHalfLoaded()) {
            return Resolution.unavailable();
        }

        if (isEnderChest(other.blockType())) {
            return Resolution.unavailable();
        }

        if (other.unresolvedLoot()) {
            return Resolution.unresolvedLoot();
        }

        if (other.connectedHalf() == null || !other.connectedHalf().equals(key)) {
            return Resolution.unavailable();
        }

        RootProbe<T> canonicalProbe = probe;
        if (!canonical.equals(key)) {
            canonicalProbe = other;
        }

        if (canonicalProbe.logicalInventory() == null || canonicalProbe.blockType() == null || canonicalProbe.blockType().isBlank()) {
            return Resolution.unavailable();
        }

        return Resolution.resolved(canonical, canonicalProbe.blockType(), canonicalProbe.logicalInventory());
    }

    /** Returns the stable key used for a double-chest pair. */
    public static BlockKey canonicalDoubleChest(BlockKey first, BlockKey second) {
        Objects.requireNonNull(first, "First key must not be null");
        Objects.requireNonNull(second, "Second key must not be null");
        if (!first.worldId().equals(second.worldId())) {
            throw new IllegalArgumentException("Double-chest keys must be in the same world");
        }

        int compareX = Integer.compare(first.x(), second.x());
        if (compareX != 0) return compareX < 0 ? first : second;

        int compareY = Integer.compare(first.y(), second.y());
        if (compareY != 0) return compareY < 0 ? first : second;

        int compareZ = Integer.compare(first.z(), second.z());
        return compareZ < 0 ? first : second;
    }

    private static boolean isEnderChest(String blockType) {
        return "minecraft:ender_chest".equals(blockType);
    }

}
