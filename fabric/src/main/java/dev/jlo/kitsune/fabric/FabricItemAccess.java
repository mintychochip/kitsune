package dev.jlo.kitsune.fabric;

import dev.jlo.kitsune.item.TraversalAdapter;
import dev.jlo.kitsune.item.TraversalChild;
import dev.jlo.kitsune.model.ItemDescriptor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Rarity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Fabric {@link TraversalAdapter} that describes, fingerprints, and expands
 * {@link ItemStack} instances for indexing and nested-item traversal.
 */
public final class FabricItemAccess implements TraversalAdapter<ItemStack> {
    private final RegistryWrapper.WrapperLookup registryLookup;

    /**
     * Creates an access adapter without a registry lookup; nested NBT item
     * children are not expanded.
     */
    public FabricItemAccess() {
        this(null);
    }

    /**
     * Creates an access adapter using the supplied registry lookup for
     * decoding nested NBT item children.
     *
     * @param registryLookup registry lookup, or {@code null} to disable NBT
     *                       child expansion
     */
    public FabricItemAccess(RegistryWrapper.WrapperLookup registryLookup) {
        this.registryLookup = registryLookup;
    }

    /**
     * Builds a textual descriptor from an item stack's material, amount,
     * name, lore, enchantments, rarity, damage, custom data, and traits.
     *
     * @param stack item stack to describe; must not be empty
     * @return descriptor for the stack
     */
    @Override
    public ItemDescriptor describe(ItemStack stack) {
        Objects.requireNonNull(stack, "Item stack must not be null");
        if (stack.isEmpty()) throw new IllegalArgumentException("Item stack must not be empty");

        ItemDescriptor.Builder descriptor = ItemDescriptor.builder()
            .materialKey(Registries.ITEM.getId(stack.getItem()).toString())
            .amount(stack.getCount());

        addText(descriptor, stack.getCustomName());
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            lore.lines().forEach(line -> addLore(descriptor, line));
        }

        ItemEnchantmentsComponent enchantments = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchantments != null) {
            enchantments.getEnchantmentEntries().forEach(entry -> {
                RegistryEntry<?> enchantment = entry.getKey();
                String key = enchantment.getKey()
                    .map(registryKey -> registryKey.getValue().toString())
                    .orElse(enchantment.getIdAsString());
                if (!key.isBlank() && entry.getIntValue() > 0) {
                    descriptor.addEnchantment(key, entry.getIntValue());
                }
            });
        }

        Rarity rarity = stack.getRarity();
        if (rarity != null) descriptor.addScalarMetadata("rarity", rarity.name().toLowerCase(java.util.Locale.ROOT));
        if (stack.isDamaged()) descriptor.addScalarMetadata("damage", Integer.toString(stack.getDamage()));
        if (stack.getMaxDamage() > 0) descriptor.addScalarMetadata("max-damage", Integer.toString(stack.getMaxDamage()));
        if (stack.hasGlint()) descriptor.addTrait("enchanted");

        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData != null && !customData.isEmpty()) {
            descriptor.addScalarMetadata("custom-data", customData.copyNbt().toString());
        }
        return descriptor.build();
    }

    /**
     * Computes a SHA-256 fingerprint of the item stack's string
     * representation.
     *
     * @param stack item stack to fingerprint; must not be null
     * @return 32-byte fingerprint
     */
    @Override
    public byte[] fingerprint(ItemStack stack) {
        Objects.requireNonNull(stack, "Item stack must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(stack.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Expands a non-empty stack's nested contents (bundles, containers, and
     * custom-data item lists) into traversable children. When no registry
     * lookup is configured, only bundle and container children are returned.
     *
     * @param stack item stack to expand; must not be null
     * @return immutable list of nested item children, sorted by slot
     */
    @Override
    public List<TraversalChild<ItemStack>> children(ItemStack stack) {
        Objects.requireNonNull(stack, "Item stack must not be null");
        if (stack.isEmpty()) return List.of();

        List<TraversalChild<ItemStack>> children = new ArrayList<>();
        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            int slot = 0;
            for (ItemStack child : bundle.iterateCopy()) {
                if (child != null && !child.isEmpty()) children.add(new TraversalChild<>("bundle", slot++, child));
            }
        }

        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null) {
            int slot = 0;
            for (ItemStack child : container.iterateNonEmptyCopy()) {
                if (child != null && !child.isEmpty()) children.add(new TraversalChild<>("container", slot++, child));
            }
        }

        if (registryLookup != null) {
            NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
            if (customData != null) {
                NbtCompound root = customData.copyNbt();
                addNbtChildren(children, root, "items");
                if (root.contains("BlockEntityTag", NbtElement.COMPOUND_TYPE)) {
                    addNbtChildren(children, root.getCompound("BlockEntityTag"), "shulker");
                }
            }
        }
        children.sort(Comparator.comparingInt(TraversalChild::slot));
        return List.copyOf(children);
    }

    private void addNbtChildren(List<TraversalChild<ItemStack>> children, NbtCompound root, String label) {
        if (!root.contains("Items", NbtElement.LIST_TYPE)) return;
        NbtList items = root.getList("Items", NbtElement.COMPOUND_TYPE);
        for (int index = 0; index < items.size(); index++) {
            ItemStack child = ItemStack.fromNbtOrEmpty(registryLookup, items.getCompound(index));
            if (!child.isEmpty()) children.add(new TraversalChild<>(label, index, child));
        }
    }

    private static void addText(ItemDescriptor.Builder descriptor, Text text) {
        if (text != null && !text.getString().isBlank()) descriptor.addDisplayText(text.getString());
    }

    private static void addLore(ItemDescriptor.Builder descriptor, Text text) {
        if (text != null && !text.getString().isBlank()) descriptor.addLore(text.getString());
    }
}
