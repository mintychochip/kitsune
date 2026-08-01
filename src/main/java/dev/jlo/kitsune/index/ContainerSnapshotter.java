package dev.jlo.kitsune.index;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.jlo.kitsune.item.NestedItemWalker;
import dev.jlo.kitsune.item.NestedItemWalkResult;
import dev.jlo.kitsune.item.TraversalChild;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ContainerDraft;
import dev.jlo.kitsune.model.ItemDraft;
import dev.jlo.kitsune.model.ItemPath;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class ContainerSnapshotter {

    public enum Status {
        COMPLETE,
        MISSING,
        UNAVAILABLE,
        UNRESOLVED_LOOT,
        UNSUPPORTED
    }

    public record Result(Status status, ContainerDraft draft) {
        public Result {
            Objects.requireNonNull(status, "Status must not be null");
            if ((status == Status.COMPLETE) != (draft != null)) {
                throw new IllegalArgumentException(
                    "A draft is required exactly for a complete snapshot"
                );
            }
        }
    }

    private final RootResolver<Inventory> resolver;
    private final NestedItemWalker<ItemStack> walker;

    public ContainerSnapshotter(RootResolver<Inventory> resolver, NestedItemWalker<ItemStack> walker) {
        this.resolver = Objects.requireNonNull(resolver, "Resolver must not be null");
        this.walker = Objects.requireNonNull(walker, "Walker must not be null");
    }

    public Result snapshot(BlockKey key) {
        Objects.requireNonNull(key, "Key must not be null");

        RootResolver.Resolution<Inventory> resolution = resolver.resolve(key);
        switch (resolution.status()) {
            case RESOLVED:
                break;
            case MISSING:
                return new Result(Status.MISSING, null);
            case UNAVAILABLE:
                return new Result(Status.UNAVAILABLE, null);
            case UNRESOLVED_LOOT:
                return new Result(Status.UNRESOLVED_LOOT, null);
            case UNSUPPORTED:
                return new Result(Status.UNSUPPORTED, null);
            default:
                return new Result(Status.UNSUPPORTED, null);
        }

        Inventory inventory = resolution.logicalInventory();
        String blockType = resolution.blockType();
        BlockKey resolvedKey = resolution.key();

        int size = inventory.getSize();
        List<ItemDraft> leaves = new ArrayList<>();
        MessageDigest digest = messageDigest();
        writeInt(digest, size);

        for (int slot = 0; slot < size; slot++) {
            writeInt(digest, slot);
            ItemStack stack = inventory.getItem(slot);
            boolean empty = stack == null || stack.isEmpty();

            digest.update((byte) (empty ? 0 : 1));

            if (empty) {
                continue;
            }

            ItemStack clone = stack.clone();
            byte[] serialized = clone.serializeAsBytes();
            writeInt(digest, serialized.length);
            digest.update(serialized);

            TraversalChild<ItemStack> root = new TraversalChild<>(blockType, slot, clone);
            NestedItemWalkResult walkResult = walker.walk(root);
            leaves.addAll(walkResult.leaves());
        }


        byte[] fingerprint = digest.digest();
        ContainerDraft draft = new ContainerDraft(resolvedKey, blockType, fingerprint, List.copyOf(leaves));
        return new Result(Status.COMPLETE, draft);
    }

    private static MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }


    private static void writeInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
