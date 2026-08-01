package dev.jlo.kitsune.item;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

import io.papermc.paper.persistence.PersistentDataContainerView;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import dev.jlo.kitsune.model.ItemDescriptor;

public final class BukkitItemDescriber {
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_TEXT_CODE_POINTS = 256;
    private static final PlainTextComponentSerializer PLAIN_TEXT =
        PlainTextComponentSerializer.plainText();

    private BukkitItemDescriber() {}

    public static ItemDescriptor describe(ItemStack stack) {
        return describe(stack, org.bukkit.Bukkit.getServer());
    }

    public static ItemDescriptor describe(ItemStack stack, Server server) {
        if (stack == null || stack.isEmpty()) {
            return ItemDescriptor.builder()
                .materialKey(Material.AIR.getKey().toString())
                .amount(1)
                .buildBounded(MAX_ENTRIES, MAX_TEXT_CODE_POINTS);
        }

        Material material = stack.getType();
        String materialKey = material.getKey().toString();
        int amount = stack.getAmount();
        ItemDescriptor.Builder builder = ItemDescriptor.builder()
            .materialKey(materialKey)
            .amount(amount);

        addMaterialFeatures(builder, material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            addGeneralMetadata(builder, meta);
            addTypedMetadata(builder, meta);
            addPersistentData(
                builder,
                "meta_pdc",
                meta.getPersistentDataContainer()
            );
        }
        addPersistentData(
            builder,
            "stack_pdc",
            stack.getPersistentDataContainer()
        );

        if (server != null) {
            ItemFeatureRegistry.contribute(server, stack, builder);
        }

        builder.materialKey(materialKey).amount(amount);
        return builder.buildBounded(MAX_ENTRIES, MAX_TEXT_CODE_POINTS);
    }

    private static void addMaterialFeatures(
        ItemDescriptor.Builder builder,
        Material material
    ) {
        builder.addScalarMetadata(
            "material_max_stack_size",
            Integer.toString(material.getMaxStackSize())
        );
        builder.addScalarMetadata(
            "material_max_durability",
            Short.toString(material.getMaxDurability())
        );

        addProperty(builder, material.isBlock(), "block");
        addProperty(builder, material.isItem(), "item");
        addProperty(builder, material.isBurnable(), "burnable");
        addProperty(builder, material.isFuel(), "fuel");
        addProperty(builder, material.isOccluding(), "occluding");
        addProperty(builder, material.isRecord(), "record");
        addProperty(builder, material.isSolid(), "solid");

        addTag(builder, material, Tag.ITEMS_SWORDS, "tool:sword");
        addTag(builder, material, Tag.ITEMS_AXES, "tool:axe");
        addTag(builder, material, Tag.ITEMS_PICKAXES, "tool:pickaxe");
        addTag(builder, material, Tag.ITEMS_SHOVELS, "tool:shovel");
        addTag(builder, material, Tag.ITEMS_HOES, "tool:hoe");
        addTag(builder, material, Tag.ITEMS_HEAD_ARMOR, "armor:head");
        addTag(builder, material, Tag.ITEMS_CHEST_ARMOR, "armor:chest");
        addTag(builder, material, Tag.ITEMS_LEG_ARMOR, "armor:legs");
        addTag(builder, material, Tag.ITEMS_FOOT_ARMOR, "armor:feet");
    }

    private static void addGeneralMetadata(
        ItemDescriptor.Builder builder,
        ItemMeta meta
    ) {
        if (meta.hasDisplayName()) {
            addDisplayText(builder, meta.displayName());
        }

        List<Component> lore = meta.lore();
        if (lore != null) {
            for (Component line : lore) {
                addLore(builder, line);
            }
        }

        meta.getEnchants().forEach((enchantment, level) -> {
            if (enchantment != null && level != null && level > 0) {
                builder.addEnchantment(enchantment.getKey().toString(), level);
            }
        });

        if (meta.hasAttributeModifiers()) {
            var modifiers = meta.getAttributeModifiers();
            if (modifiers != null) {
                for (var entry : modifiers.entries()) {
                    addAttribute(builder, entry.getKey(), entry.getValue());
                }
            }
        }

        for (ItemFlag flag : meta.getItemFlags()) {
            builder.addTrait("flag:" + flag.name().toLowerCase(Locale.ROOT));
        }

        builder.addScalarMetadata(
            "unbreakable",
            Boolean.toString(meta.isUnbreakable())
        );
        if (meta.hasMaxStackSize()) {
            builder.addScalarMetadata(
                "custom_max_stack_size",
                Integer.toString(meta.getMaxStackSize())
            );
        }
        if (meta.hasRarity()) {
            builder.addScalarMetadata(
                "rarity",
                meta.getRarity().name().toLowerCase(Locale.ROOT)
            );
        }

        if (meta instanceof Damageable damageable) {
            if (damageable.hasDamage()) {
                builder.addScalarMetadata(
                    "damage",
                    Integer.toString(damageable.getDamage())
                );
            }
            if (damageable.hasMaxDamage()) {
                builder.addScalarMetadata(
                    "custom_max_damage",
                    Integer.toString(damageable.getMaxDamage())
                );
            }
        }

        addCustomModelData(builder, meta.getCustomModelDataComponent());
    }

    private static void addAttribute(
        ItemDescriptor.Builder builder,
        Attribute attribute,
        AttributeModifier modifier
    ) {
        if (attribute == null || modifier == null) return;
        double value = modifier.getAmount();
        if (!Double.isFinite(value)) return;

        String key = attribute.getKey()
            + "|" + modifier.getKey()
            + "|" + modifier.getSlotGroup()
            + "|" + modifier.getOperation().name().toLowerCase(Locale.ROOT);
        builder.addAttribute(key, value);
    }

    private static void addCustomModelData(
        ItemDescriptor.Builder builder,
        CustomModelDataComponent component
    ) {
        if (component == null) return;

        List<Float> floats = component.getFloats();
        for (int index = 0; index < floats.size(); index++) {
            Float value = floats.get(index);
            if (value != null && Float.isFinite(value)) {
                builder.addScalarMetadata(
                    indexedKey("custom_model_data.float", index),
                    Float.toString(value)
                );
            }
        }

        List<Boolean> flags = component.getFlags();
        for (int index = 0; index < flags.size(); index++) {
            Boolean value = flags.get(index);
            if (value != null) {
                builder.addScalarMetadata(
                    indexedKey("custom_model_data.flag", index),
                    Boolean.toString(value)
                );
            }
        }

        List<String> strings = component.getStrings();
        for (int index = 0; index < strings.size(); index++) {
            addOptionalScalar(
                builder,
                indexedKey("custom_model_data.string", index),
                strings.get(index)
            );
        }

        List<Color> colors = component.getColors();
        for (int index = 0; index < colors.size(); index++) {
            Color value = colors.get(index);
            if (value != null) {
                builder.addScalarMetadata(
                    indexedKey("custom_model_data.color", index),
                    Integer.toUnsignedString(value.asARGB())
                );
            }
        }
    }

    private static void addTypedMetadata(
        ItemDescriptor.Builder builder,
        ItemMeta meta
    ) {
        if (meta instanceof PotionMeta potionMeta) {
            addPotionMetadata(builder, potionMeta);
        }
        if (meta instanceof BookMeta bookMeta) {
            addBookMetadata(builder, bookMeta);
        }
        if (meta instanceof FireworkMeta fireworkMeta) {
            addFireworkMetadata(builder, fireworkMeta);
        }
        if (meta instanceof ArmorMeta armorMeta && armorMeta.hasTrim()) {
            ArmorTrim trim = armorMeta.getTrim();
            if (trim != null) {
                builder.addTrait(
                    "trim_material:"
                        + RegistryAccess.registryAccess()
                            .getRegistry(RegistryKey.TRIM_MATERIAL)
                            .getKeyOrThrow(trim.getMaterial())
                );
                builder.addTrait(
                    "trim_pattern:"
                        + RegistryAccess.registryAccess()
                            .getRegistry(RegistryKey.TRIM_PATTERN)
                            .getKeyOrThrow(trim.getPattern())
                );
            }
        }
    }

    private static void addPotionMetadata(
        ItemDescriptor.Builder builder,
        PotionMeta meta
    ) {
        if (meta.hasBasePotionType()) {
            var type = meta.getBasePotionType();
            if (type != null) {
                builder.addScalarMetadata(
                    "potion_type",
                    type.getKey().toString()
                );
            }
        }
        if (meta.hasColor()) {
            Color color = meta.getColor();
            if (color != null) {
                builder.addScalarMetadata(
                    "potion_color",
                    Integer.toUnsignedString(color.asARGB())
                );
            }
        }

        for (PotionEffect effect : meta.getCustomEffects()) {
            if (effect == null || effect.getType() == null) continue;
            String effectKey = effect.getType().getKey().toString();
            String prefix = "potion_effect." + effectKey;
            builder.addTrait("potion_effect:" + effectKey);
            builder.addScalarMetadata(
                prefix + ".duration",
                Integer.toString(effect.getDuration())
            );
            builder.addScalarMetadata(
                prefix + ".amplifier",
                Integer.toString(effect.getAmplifier())
            );
            builder.addScalarMetadata(
                prefix + ".ambient",
                Boolean.toString(effect.isAmbient())
            );
            builder.addScalarMetadata(
                prefix + ".particles",
                Boolean.toString(effect.hasParticles())
            );
            builder.addScalarMetadata(
                prefix + ".icon",
                Boolean.toString(effect.hasIcon())
            );
        }
    }

    private static void addBookMetadata(
        ItemDescriptor.Builder builder,
        BookMeta meta
    ) {
        if (meta.hasTitle()) {
            addComponentScalar(builder, "book_title", meta.title());
        }
        if (meta.hasAuthor()) {
            addComponentScalar(builder, "book_author", meta.author());
        }
        if (meta.hasGeneration()) {
            builder.addScalarMetadata(
                "book_generation",
                meta.getGeneration().name().toLowerCase(Locale.ROOT)
            );
        }
    }

    private static void addFireworkMetadata(
        ItemDescriptor.Builder builder,
        FireworkMeta meta
    ) {
        builder.addScalarMetadata(
            "firework_power",
            Integer.toString(meta.getPower())
        );
        List<FireworkEffect> effects = meta.getEffects();
        for (int index = 0; index < effects.size(); index++) {
            FireworkEffect effect = effects.get(index);
            if (effect == null) continue;
            String prefix = indexedKey("firework_effect", index);
            String type = effect.getType().name().toLowerCase(Locale.ROOT);
            builder.addTrait("firework_effect:" + type);
            builder.addScalarMetadata(prefix + ".type", type);
            builder.addScalarMetadata(
                prefix + ".trail",
                Boolean.toString(effect.hasTrail())
            );
            builder.addScalarMetadata(
                prefix + ".flicker",
                Boolean.toString(effect.hasFlicker())
            );
            addOptionalScalar(
                builder,
                prefix + ".colors",
                serializeColors(effect.getColors())
            );
            addOptionalScalar(
                builder,
                prefix + ".fade_colors",
                serializeColors(effect.getFadeColors())
            );
        }
    }

    private static void addPersistentData(
        ItemDescriptor.Builder builder,
        String sourcePrefix,
        PersistentDataContainerView pdc
    ) {
        Set<NamespacedKey> keys = new TreeSet<>(
            Comparator.comparing(NamespacedKey::toString)
        );
        keys.addAll(pdc.getKeys());
        for (NamespacedKey key : keys) {
            String keyName = key.toString();
            builder.addCustomTag("pdc_key:" + keyName);
            String descriptorKey = sourcePrefix + ":" + keyName;
            if (pdc.has(key, PersistentDataType.BYTE)) {
                Byte value = pdc.get(key, PersistentDataType.BYTE);
                if (value != null) {
                    builder.addScalarMetadata(
                        descriptorKey,
                        Byte.toString(value)
                    );
                }
            } else if (pdc.has(key, PersistentDataType.INTEGER)) {
                Integer value = pdc.get(key, PersistentDataType.INTEGER);
                if (value != null) {
                    builder.addScalarMetadata(
                        descriptorKey,
                        Integer.toString(value)
                    );
                }
            } else if (pdc.has(key, PersistentDataType.LONG)) {
                Long value = pdc.get(key, PersistentDataType.LONG);
                if (value != null) {
                    builder.addScalarMetadata(
                        descriptorKey,
                        Long.toString(value)
                    );
                }
            } else if (pdc.has(key, PersistentDataType.FLOAT)) {
                Float value = pdc.get(key, PersistentDataType.FLOAT);
                if (value != null && Float.isFinite(value)) {
                    builder.addScalarMetadata(
                        descriptorKey,
                        Float.toString(value)
                    );
                }
            } else if (pdc.has(key, PersistentDataType.DOUBLE)) {
                Double value = pdc.get(key, PersistentDataType.DOUBLE);
                if (value != null && Double.isFinite(value)) {
                    builder.addScalarMetadata(
                        descriptorKey,
                        Double.toString(value)
                    );
                }
            } else if (pdc.has(key, PersistentDataType.STRING)) {
                addOptionalScalar(
                    builder,
                    descriptorKey,
                    pdc.get(key, PersistentDataType.STRING)
                );
            }
        }
    }

    private static void addDisplayText(
        ItemDescriptor.Builder builder,
        Component component
    ) {
        String text = plain(component);
        if (text != null) builder.addDisplayText(text);
    }

    private static void addLore(
        ItemDescriptor.Builder builder,
        Component component
    ) {
        String text = plain(component);
        if (text != null) builder.addLore(text);
    }

    private static void addComponentScalar(
        ItemDescriptor.Builder builder,
        String key,
        Component component
    ) {
        addOptionalScalar(builder, key, plain(component));
    }

    private static String plain(Component component) {
        if (component == null) return null;
        String value = PLAIN_TEXT.serialize(component);
        return value.isBlank() ? null : value;
    }

    private static void addOptionalScalar(
        ItemDescriptor.Builder builder,
        String key,
        String value
    ) {
        if (value != null && !value.isBlank()) {
            builder.addScalarMetadata(key, value);
        }
    }

    private static void addProperty(
        ItemDescriptor.Builder builder,
        boolean present,
        String trait
    ) {
        if (present) builder.addTrait(trait);
    }

    private static void addTag(
        ItemDescriptor.Builder builder,
        Material material,
        Tag<Material> tag,
        String trait
    ) {
        if (tag.isTagged(material)) builder.addTrait(trait);
    }

    private static String indexedKey(String prefix, int index) {
        if (index < 10) return prefix + ".00" + index;
        if (index < 100) return prefix + ".0" + index;
        return prefix + "." + index;
    }

    private static String serializeColors(List<Color> colors) {
        Objects.requireNonNull(colors, "Colors must not be null");
        StringJoiner result = new StringJoiner(",");
        for (Color color : colors) {
            if (color != null) {
                result.add(Integer.toUnsignedString(color.asARGB()));
            }
        }
        return result.toString();
    }
}
