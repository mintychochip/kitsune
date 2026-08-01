package dev.jlo.kitsune.item;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import dev.jlo.kitsune.api.item.NestedContentsProvider;
import dev.jlo.kitsune.model.ItemDescriptor;

final class BukkitTraversalAdapter implements TraversalAdapter<ItemStack> {
    private final Server server;

    BukkitTraversalAdapter(Server server) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
    }

    @Override
    public ItemDescriptor describe(ItemStack node) {
        Objects.requireNonNull(node, "Item stack must not be null");
        if (node.isEmpty()) throw new IllegalArgumentException("Item stack must not be empty");
        return BukkitItemDescriber.describe(node, server);
    }

    @Override
    public byte[] fingerprint(ItemStack node) {
        Objects.requireNonNull(node, "Item stack must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(node.serializeAsBytes());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @Override
    public List<TraversalChild<ItemStack>> children(ItemStack node) {
        Objects.requireNonNull(node, "Item stack must not be null");
        if (node.isEmpty()) return List.of();

        for (NestedContentsProvider provider : NestedContentsRegistry.providers(server)) {
            try {
                ItemStack providerInput = node.clone();
                if (!provider.supports(providerInput)) continue;

                List<NestedContentsProvider.ChildItem> provided =
                    Objects.requireNonNull(
                        provider.children(providerInput),
                        "Nested contents provider returned a null list"
                    );
                List<TraversalChild<ItemStack>> children =
                    new ArrayList<>(provided.size());
                for (NestedContentsProvider.ChildItem child : provided) {
                    if (child == null) {
                        throw new IllegalArgumentException(
                            "Nested contents provider returned a null child"
                        );
                    }
                    ItemStack childStack = child.stack();
                    if (childStack.isEmpty()) {
                        throw new IllegalArgumentException(
                            "Nested contents provider returned an empty child"
                        );
                    }
                    children.add(
                        new TraversalChild<>(
                            child.label(),
                            child.slot(),
                            childStack.clone()
                        )
                    );
                }
                children.sort(Comparator.comparingInt(TraversalChild::slot));
                return List.copyOf(children);
            } catch (Exception failure) {
                ItemFeatureRegistry.reportFailure(server, provider, failure);
                throw new IllegalStateException(
                    "Nested contents provider failed: "
                        + provider.getClass().getName(),
                    failure
                );
            }
        }
        return List.of();
    }
}
