package dev.jlo.kitsune.item;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;

import dev.jlo.kitsune.model.ItemDescriptor;

/**
 * Adapts Bukkit {@link ItemStack}s to the kit contents traversal contract,
 * describing, fingerprinting, and expanding nested item contents.
 */
public final class BukkitTraversalAdapter implements TraversalAdapter<ItemStack> {
    private final Server server;

    /**
     * Creates an adapter using the supplied server for tag context.
     *
     * @param server server providing tag and registry context
     */
    public BukkitTraversalAdapter(Server server) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
    }

    /**
     * Builds a descriptor for a non-empty stack.
     *
     * @param node stack to describe
     * @return descriptor for the stack
     * @throws IllegalArgumentException when the stack is empty
     */
    @Override
    public ItemDescriptor describe(ItemStack node) {
        if (BukkitItemDescriber.isEmpty(node)) throw new IllegalArgumentException("Item stack must not be empty");
        return BukkitItemDescriber.describe(node, server);
    }

    /**
     * Returns a SHA-256 fingerprint of a stack's serialized contents.
     *
     * @param node stack to fingerprint
     * @return content fingerprint bytes
     */
    @Override
    public byte[] fingerprint(ItemStack node) {
        Objects.requireNonNull(node, "Item stack must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(BukkitItemSerialization.bytes(node));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Expands a stack into its nested children via registered providers, in slot order.
     *
     * @param node stack to expand
     * @return an empty list for empty stacks or stacks without registered contents
     */
    @Override
    public List<TraversalChild<ItemStack>> children(ItemStack node) {
        if (BukkitItemDescriber.isEmpty(node)) return List.of();
        for (BukkitNestedContentsProvider provider : NestedContentsRegistry.providers(server)) {
            if (!provider.supports(node)) continue;
            List<BukkitNestedContentsProvider.Child> provided = Objects.requireNonNull(
                provider.children(node.clone()), "Nested contents provider returned a null list");
            List<TraversalChild<ItemStack>> children = new ArrayList<>(provided.size());
            for (BukkitNestedContentsProvider.Child child : provided) {
                if (child == null || BukkitItemDescriber.isEmpty(child.stack())) {
                    throw new IllegalArgumentException("Nested contents provider returned an invalid child");
                }
                children.add(new TraversalChild<>(child.label(), child.slot(), child.stack().clone()));
            }
            children.sort(Comparator.comparingInt(TraversalChild::slot));
            return List.copyOf(children);
        }
        return List.of();
    }
}
