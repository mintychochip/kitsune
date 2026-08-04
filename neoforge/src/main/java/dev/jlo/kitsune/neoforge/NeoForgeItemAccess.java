package dev.jlo.kitsune.neoforge;

import dev.jlo.kitsune.item.TraversalAdapter;
import dev.jlo.kitsune.item.TraversalChild;
import dev.jlo.kitsune.model.ItemDescriptor;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class NeoForgeItemAccess implements TraversalAdapter<ItemStack> {
    private final HolderLookup.Provider registryLookup;

    public NeoForgeItemAccess() {
        this(null);
    }

    public NeoForgeItemAccess(HolderLookup.Provider registryLookup) {
        this.registryLookup = registryLookup;
    }

    @Override
    public ItemDescriptor describe(ItemStack stack) {
        Objects.requireNonNull(stack, "Item stack must not be null");
        if (stack.isEmpty()) throw new IllegalArgumentException("Item stack must not be empty");

        ItemDescriptor.Builder descriptor = ItemDescriptor.builder()
            .materialKey(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
            .amount(stack.getCount());

        addText(descriptor, stack.getCustomName());
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) lore.lines().forEach(line -> addLore(descriptor, line));

        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments != null) {
            enchantments.entrySet().forEach(entry -> {
                Holder<Enchantment> enchantment = entry.getKey();
                String key = enchantment.unwrapKey()
                    .map(resourceKey -> resourceKey.location().toString())
                    .orElseGet(enchantment::getRegisteredName);
                if (!key.isBlank() && entry.getIntValue() > 0) {
                    descriptor.addEnchantment(key, entry.getIntValue());
                }
            });
        }

        Rarity rarity = stack.getRarity();
        if (rarity != null) descriptor.addScalarMetadata("rarity", rarity.name().toLowerCase(java.util.Locale.ROOT));
        if (stack.isDamaged()) descriptor.addScalarMetadata("damage", Integer.toString(stack.getDamageValue()));
        if (stack.getMaxDamage() > 0) descriptor.addScalarMetadata("max-damage", Integer.toString(stack.getMaxDamage()));
        if (stack.hasFoil()) descriptor.addTrait("enchanted");

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null && !customData.isEmpty()) {
            descriptor.addScalarMetadata("custom-data", customData.copyTag().toString());
        }
        CustomData blockEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (blockEntityData != null && !blockEntityData.isEmpty()) {
            descriptor.addScalarMetadata("block-entity-data", blockEntityData.copyTag().toString());
        }
        return descriptor.build();
    }

    @Override
    public byte[] fingerprint(ItemStack stack) {
        Objects.requireNonNull(stack, "Item stack must not be null");
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(stack.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @Override
    public List<TraversalChild<ItemStack>> children(ItemStack stack) {
        Objects.requireNonNull(stack, "Item stack must not be null");
        if (stack.isEmpty()) return List.of();

        List<TraversalChild<ItemStack>> children = new ArrayList<>();
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            int slot = 0;
            for (ItemStack child : bundle.itemsCopy()) {
                if (child != null && !child.isEmpty()) children.add(new TraversalChild<>("bundle", slot++, child));
            }
        }

        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            int slot = 0;
            for (ItemStack child : container.nonEmptyItemsCopy()) {
                if (child != null && !child.isEmpty()) children.add(new TraversalChild<>("container", slot++, child.copy()));
            }
        }

        if (registryLookup != null) {
            addComponentChildren(children, stack.get(DataComponents.BLOCK_ENTITY_DATA), "block-entity");
            addComponentChildren(children, stack.get(DataComponents.CUSTOM_DATA), "custom-data");
        }
        children.sort(Comparator.comparingInt(TraversalChild::slot));
        return List.copyOf(children);
    }

    private void addComponentChildren(
        List<TraversalChild<ItemStack>> children,
        CustomData data,
        String label
    ) {
        if (data != null) addNbtChildren(children, data.copyTag(), label);
    }

    private void addNbtChildren(List<TraversalChild<ItemStack>> children, CompoundTag root, String label) {
        if (!root.contains("Items", Tag.TAG_LIST)) return;
        ListTag items = root.getList("Items", Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            ItemStack child = ItemStack.parse(registryLookup, items.getCompound(index)).orElse(ItemStack.EMPTY);
            if (!child.isEmpty()) children.add(new TraversalChild<>(label, index, child));
        }
    }

    private static void addText(ItemDescriptor.Builder descriptor, Component text) {
        if (text != null && !text.getString().isBlank()) descriptor.addDisplayText(text.getString());
    }

    private static void addLore(ItemDescriptor.Builder descriptor, Component text) {
        if (text != null && !text.getString().isBlank()) descriptor.addLore(text.getString());
    }
}
