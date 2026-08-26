package dev.jlo.kitsune.item;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import dev.jlo.kitsune.api.item.ItemFeatureProvider;
import dev.jlo.kitsune.model.ItemDescriptor;

/**
 * Contributes a material taxonomy to item descriptors based on material key patterns.
 *
 * <p>Given a namespaced material key (for example
 * {@code minecraft:diamond_sword}) this provider adds trait tags such as
 * {@code weapon}, {@code sword}, {@code melee}, {@code combat}, and
 * {@code diamond}. The traits flow into sparse embeddings (weight 4.0) and
 * any dense provider consuming {@link ItemDescriptor#traits()}.
 *
 * <p>Pure Java: no Bukkit dependencies, operates only on the neutral
 * {@code materialKey}. Material keys are lowercased and the namespace prefix
 * (up to the first {@code ':'}) is stripped before matching.
 */
public final class MaterialTaxonomyFeatureProvider implements ItemFeatureProvider {
    /** Stable identifier used to select and serialize this provider. */
    public static final String ID = "taxonomy:material-v1";

    private static final Map<Predicate<String>, Set<String>> MATERIAL_TAGS = Map.of(
        contains("NETHERITE"), Set.of("netherite"),
        contains("DIAMOND"), Set.of("diamond"),
        contains("IRON"), Set.of("iron"),
        any(contains("GOLD"), contains("GOLDEN")), Set.of("gold", "golden"),
        contains("STONE"), Set.of("stone"),
        any(contains("WOOD"), contains("WOODEN")), Set.of("wood", "wooden"),
        contains("LEATHER"), Set.of("leather"),
        contains("CHAINMAIL"), Set.of("chainmail")
    );

    private static final Set<String> COLORS = Set.of(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
        "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    );

    private static final Set<String> GEMS = Set.of(
        "DIAMOND", "EMERALD", "AMETHYST_SHARD", "LAPIS_LAZULI", "PRISMARINE_SHARD",
        "PRISMARINE_CRYSTALS", "QUARTZ", "NETHER_QUARTZ"
    );

    private static final Set<String> MINERAL_BLOCKS = Set.of(
        "DIAMOND", "EMERALD", "GOLD", "IRON", "COPPER", "NETHERITE", "LAPIS",
        "REDSTONE", "COAL", "AMETHYST", "QUARTZ", "RAW_"
    );

    @Override
    public void contribute(ItemDescriptor item, ItemDescriptor.Builder descriptor) {
        String material = stripNamespace(item.materialKey()).toUpperCase(Locale.ROOT);
        if (material.isEmpty()) return;

        addMaterialTags(material, descriptor);
        contributeEnchantment(item, descriptor);
        contributeRedstone(material, descriptor);
        contributeColor(material, descriptor);
        contributeBlockMaterial(material, descriptor);
        contributeMineral(material, descriptor);
        contributeWeapon(material, descriptor);
        contributeTool(material, descriptor);
        contributeArmor(material, descriptor);
        contributeStorage(material, descriptor);
    }

    private static void addMaterialTags(String material, ItemDescriptor.Builder descriptor) {
        for (Map.Entry<Predicate<String>, Set<String>> entry : MATERIAL_TAGS.entrySet()) {
            if (entry.getKey().test(material)) {
                for (String tag : entry.getValue()) {
                    if ("wood".equals(tag)) {
                        addWood(descriptor);
                    } else {
                        descriptor.addTrait(tag);
                    }
                }
                break;
            }
        }
    }

    private static void addWood(ItemDescriptor.Builder descriptor) {
        descriptor.addTrait("wood").addCustomTag("wood");
    }

    private static void contributeEnchantment(ItemDescriptor item, ItemDescriptor.Builder descriptor) {
        if (!item.enchantments().isEmpty()) {
            descriptor.addTrait("enchanted");
        }
        item.enchantments().keySet().forEach(key -> {
            descriptor.addTrait(key);
            descriptor.addTrait(key + "_" + item.enchantments().get(key));
        });
    }

    private static void contributeRedstone(String material, ItemDescriptor.Builder descriptor) {
        if (material.contains("REDSTONE")) descriptor.addTrait("redstone");
        if (material.contains("POWERED")) descriptor.addTrait("powered");
        if (material.contains("COMPARATOR")) descriptor.addTrait("comparator");
        if (material.contains("REPEATER")) descriptor.addTrait("repeater");
    }

    private static void contributeColor(String material, ItemDescriptor.Builder descriptor) {
        for (String color : COLORS) {
            if (material.startsWith(color.toUpperCase(Locale.ROOT) + "_")) {
                descriptor.addTrait(color);
                break;
            }
        }
    }

    private static void contributeBlockMaterial(String material, ItemDescriptor.Builder descriptor) {
        if (material.contains("GLASS")) {
            descriptor.addTrait("glass");
            if (material.contains("PANE")) descriptor.addTrait("pane");
        }
        if (material.contains("WOOL")) {
            descriptor.addTrait("wool");
            descriptor.addTrait("soft");
        }
        if (material.contains("CONCRETE")) {
            descriptor.addTrait("concrete");
            if (material.contains("POWDER")) descriptor.addTrait("powder");
        }
        if (material.contains("TERRACOTTA")) {
            descriptor.addTrait("terracotta");
            descriptor.addTrait("clay");
            if (material.contains("GLAZED")) descriptor.addTrait("glazed");
        }
        if (material.contains("CANDLE")) {
            descriptor.addTrait("candle");
            descriptor.addTrait("lightsource");
        }
        if (material.contains("CARPET")) {
            descriptor.addTrait("carpet");
            descriptor.addTrait("flooring");
        }
        if (material.contains("BED") && !material.contains("BEDROCK")) {
            descriptor.addTrait("bed");
            descriptor.addTrait("furniture");
        }
        if (material.contains("BANNER")) {
            descriptor.addTrait("banner");
            descriptor.addTrait("decorative");
        }
        if (material.contains("SHULKER")) {
            descriptor.addTrait("shulker");
            descriptor.addTrait("storage");
            descriptor.addTrait("container");
        }
        if (material.contains("OAK") && !material.contains("DARK_OAK")) {
            descriptor.addTrait("oak");
            addWood(descriptor);
        }
        if (material.contains("SPRUCE")) {
            descriptor.addTrait("spruce");
            addWood(descriptor);
        }
        if (material.contains("BIRCH")) {
            descriptor.addTrait("birch");
            addWood(descriptor);
        }
        if (material.contains("JUNGLE")) {
            descriptor.addTrait("jungle");
            addWood(descriptor);
        }
        if (material.contains("ACACIA")) {
            descriptor.addTrait("acacia");
            addWood(descriptor);
        }
        if (material.contains("DARK_OAK")) {
            descriptor.addTrait("darkoak");
            addWood(descriptor);
        }
        if (material.contains("MANGROVE")) {
            descriptor.addTrait("mangrove");
            addWood(descriptor);
        }
        if (material.contains("CHERRY")) {
            descriptor.addTrait("cherry");
            addWood(descriptor);
        }
        if (material.contains("BAMBOO")) {
            descriptor.addTrait("bamboo");
            addWood(descriptor);
        }
        if (material.contains("CRIMSON")) {
            descriptor.addTrait("crimson");
            descriptor.addTrait("netherstem");
        }
        if (material.contains("WARPED")) {
            descriptor.addTrait("warped");
            descriptor.addTrait("netherstem");
        }
        if (material.contains("STONE") && !material.contains("REDSTONE")
                && !material.contains("GLOWSTONE") && !material.contains("SANDSTONE")) {
            descriptor.addTrait("stone");
        }
        if (material.contains("COBBLESTONE")) {
            descriptor.addTrait("cobblestone");
            descriptor.addTrait("cobble");
        }
        if (material.contains("DEEPSLATE")) {
            descriptor.addTrait("deepslate");
        }
        if (material.contains("BRICK")) {
            descriptor.addTrait("brick");
        }
        if (material.contains("SANDSTONE")) {
            descriptor.addTrait("sandstone");
        }
        if (material.endsWith("_ORE")) {
            descriptor.addTrait("ore");
            descriptor.addTrait("mineable");
        }
    }

    private static void contributeMineral(String material, ItemDescriptor.Builder descriptor) {
        if (material.endsWith("_ORE") || material.equals("NETHER_GOLD_ORE") || material.equals("ANCIENT_DEBRIS")) {
            descriptor.addTrait("ore");
            descriptor.addTrait("mineral");
            descriptor.addTrait("mineable");
        }
        if (material.startsWith("RAW_")) {
            descriptor.addTrait("raw");
            descriptor.addTrait("mineral");
            descriptor.addTrait("smeltable");
        }
        if (material.endsWith("_INGOT") || material.equals("NETHERITE_INGOT") || material.equals("COPPER_INGOT")) {
            descriptor.addTrait("ingot");
            descriptor.addTrait("mineral");
            descriptor.addTrait("metal");
            descriptor.addTrait("refined");
        }
        if (material.endsWith("_NUGGET")) {
            descriptor.addTrait("nugget");
            descriptor.addTrait("mineral");
            descriptor.addTrait("metal");
        }
        if (GEMS.contains(material)) {
            descriptor.addTrait("gem");
            descriptor.addTrait("mineral");
            descriptor.addTrait("precious");
        }
        if (material.contains("DIAMOND")) {
            descriptor.addTrait("diamond");
            descriptor.addTrait("mineral");
        }
        if (material.contains("EMERALD")) {
            descriptor.addTrait("emerald");
            descriptor.addTrait("mineral");
        }
        if (material.contains("GOLD") || material.contains("GOLDEN")) {
            descriptor.addTrait("gold");
            descriptor.addTrait("mineral");
        }
        if (material.contains("IRON")) {
            descriptor.addTrait("iron");
            descriptor.addTrait("mineral");
        }
        if (material.contains("COPPER")) {
            descriptor.addTrait("copper");
            descriptor.addTrait("mineral");
        }
        if (material.contains("NETHERITE")) {
            descriptor.addTrait("netherite");
            descriptor.addTrait("mineral");
        }
        if (material.contains("LAPIS")) {
            descriptor.addTrait("lapis");
            descriptor.addTrait("mineral");
        }
        if (material.contains("REDSTONE")) {
            descriptor.addTrait("redstone");
            descriptor.addTrait("mineral");
        }
        if (material.contains("QUARTZ")) {
            descriptor.addTrait("quartz");
            descriptor.addTrait("mineral");
        }
        if (material.contains("AMETHYST")) {
            descriptor.addTrait("amethyst");
            descriptor.addTrait("mineral");
        }
        if (material.contains("COAL") && !material.contains("CHARCOAL")) {
            descriptor.addTrait("coal");
            descriptor.addTrait("mineral");
        }
        if (material.endsWith("_BLOCK") && MINERAL_BLOCKS.stream().anyMatch(material::contains)) {
            descriptor.addTrait("mineralblock");
            descriptor.addTrait("storage");
        }
    }

    private static void contributeWeapon(String material, ItemDescriptor.Builder descriptor) {
        if (material.endsWith("_SWORD")) {
            descriptor.addTrait("weapon");
            descriptor.addTrait("sword");
            descriptor.addTrait("melee");
            descriptor.addTrait("combat");
            addMaterialTags(material, descriptor);
        }
        if (material.endsWith("_AXE") && !material.contains("PICKAXE")) {
            descriptor.addTrait("weapon");
            descriptor.addTrait("axe");
            descriptor.addTrait("melee");
            descriptor.addTrait("combat");
            addMaterialTags(material, descriptor);
        }
        if (material.equals("BOW")) {
            descriptor.addTrait("weapon");
            descriptor.addTrait("bow");
            descriptor.addTrait("ranged");
            descriptor.addTrait("projectile");
            descriptor.addTrait("combat");
        }
        if (material.equals("CROSSBOW")) {
            descriptor.addTrait("weapon");
            descriptor.addTrait("crossbow");
            descriptor.addTrait("ranged");
            descriptor.addTrait("projectile");
            descriptor.addTrait("combat");
        }
        if (material.equals("TRIDENT")) {
            descriptor.addTrait("weapon");
            descriptor.addTrait("trident");
            descriptor.addTrait("melee");
            descriptor.addTrait("ranged");
            descriptor.addTrait("throwable");
            descriptor.addTrait("combat");
        }
        if (material.equals("MACE")) {
            descriptor.addTrait("weapon");
            descriptor.addTrait("mace");
            descriptor.addTrait("melee");
            descriptor.addTrait("combat");
            descriptor.addTrait("heavyhitter");
        }
        if (material.contains("ARROW")) {
            descriptor.addTrait("ammo");
            descriptor.addTrait("ammunition");
            descriptor.addTrait("projectile");
            descriptor.addTrait("combat");
            if (material.equals("SPECTRAL_ARROW")) descriptor.addTrait("spectral");
            if (material.equals("TIPPED_ARROW")) {
                descriptor.addTrait("tipped");
                descriptor.addTrait("potion");
            }
        }
        if (material.equals("FIREWORK_ROCKET")) {
            descriptor.addTrait("ammo");
            descriptor.addTrait("firework");
            descriptor.addTrait("projectile");
            descriptor.addTrait("elytra");
        }
        if (material.equals("SNOWBALL") || material.equals("EGG")) {
            descriptor.addTrait("weapon");
            descriptor.addTrait("throwable");
            descriptor.addTrait("projectile");
        }
        if (material.equals("ENDER_PEARL")) {
            descriptor.addTrait("throwable");
            descriptor.addTrait("teleport");
            descriptor.addTrait("projectile");
        }
        if (material.equals("WIND_CHARGE")) {
            descriptor.addTrait("weapon");
            descriptor.addTrait("throwable");
            descriptor.addTrait("projectile");
            descriptor.addTrait("knockback");
        }
        if (material.equals("SHIELD")) {
            descriptor.addTrait("weapon");
            descriptor.addTrait("shield");
            descriptor.addTrait("defense");
            descriptor.addTrait("combat");
            descriptor.addTrait("blocking");
        }
    }

    private static void contributeTool(String material, ItemDescriptor.Builder descriptor) {
        if (material.endsWith("_PICKAXE")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("pickaxe");
            descriptor.addTrait("mining");
            descriptor.addTrait("breaking");
            addMaterialTags(material, descriptor);
        }
        if (material.endsWith("_SHOVEL")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("shovel");
            descriptor.addTrait("digging");
            descriptor.addTrait("breaking");
            addMaterialTags(material, descriptor);
        }
        if (material.endsWith("_HOE")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("hoe");
            descriptor.addTrait("farming");
            descriptor.addTrait("tilling");
            addMaterialTags(material, descriptor);
        }
        if (material.endsWith("_AXE") && !material.contains("PICKAXE")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("axe");
            descriptor.addTrait("woodcutting");
            descriptor.addTrait("chopping");
            descriptor.addTrait("breaking");
            addMaterialTags(material, descriptor);
        }
        if (material.equals("SHEARS")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("shears");
            descriptor.addTrait("shearing");
            descriptor.addTrait("harvesting");
        }
        if (material.equals("FLINT_AND_STEEL")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("flintandsteel");
            descriptor.addTrait("fire");
            descriptor.addTrait("igniter");
        }
        if (material.equals("FISHING_ROD")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("fishingrod");
            descriptor.addTrait("fishing");
            descriptor.addTrait("catching");
        }
        if (material.equals("CARROT_ON_A_STICK") || material.equals("WARPED_FUNGUS_ON_A_STICK")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("riding");
            descriptor.addTrait("control");
        }
        if (material.equals("LEAD")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("lead");
            descriptor.addTrait("leash");
            descriptor.addTrait("mob");
        }
        if (material.equals("NAME_TAG")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("nametag");
            descriptor.addTrait("naming");
            descriptor.addTrait("mob");
        }
        if (material.equals("BRUSH")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("brush");
            descriptor.addTrait("archaeology");
            descriptor.addTrait("excavation");
        }
        if (material.equals("SPYGLASS")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("spyglass");
            descriptor.addTrait("zoom");
            descriptor.addTrait("scouting");
        }
        if (material.equals("COMPASS") || material.equals("RECOVERY_COMPASS")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("compass");
            descriptor.addTrait("navigation");
            if (material.equals("RECOVERY_COMPASS")) {
                descriptor.addTrait("recovery");
                descriptor.addTrait("death");
            }
        }
        if (material.equals("CLOCK")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("clock");
            descriptor.addTrait("time");
        }
        if (material.contains("MAP")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("map");
            descriptor.addTrait("navigation");
            if (material.equals("FILLED_MAP")) descriptor.addTrait("filled");
        }
        if (material.contains("BUCKET")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("bucket");
            descriptor.addTrait("container");
            if (material.equals("WATER_BUCKET")) descriptor.addTrait("water");
            if (material.equals("LAVA_BUCKET")) descriptor.addTrait("lava");
            if (material.equals("MILK_BUCKET")) descriptor.addTrait("milk");
            if (material.equals("POWDER_SNOW_BUCKET")) descriptor.addTrait("powdersnow");
            if (material.contains("FISH_BUCKET") || material.equals("AXOLOTL_BUCKET") || material.equals("TADPOLE_BUCKET")) {
                descriptor.addTrait("mobbucket");
                descriptor.addTrait("mob");
            }
        }
        if (material.equals("BONE_MEAL")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("bonemeal");
            descriptor.addTrait("farming");
            descriptor.addTrait("growth");
        }
        if (material.equals("WRITABLE_BOOK") || material.equals("WRITTEN_BOOK")) {
            descriptor.addTrait("tool");
            descriptor.addTrait("book");
            descriptor.addTrait("writing");
        }
    }

    private static void contributeArmor(String material, ItemDescriptor.Builder descriptor) {
        if (material.endsWith("_HELMET") || material.equals("TURTLE_HELMET")) {
            descriptor.addTrait("armor");
            descriptor.addTrait("helmet");
            descriptor.addTrait("headgear");
            descriptor.addTrait("head");
            descriptor.addTrait("wearable");
            addMaterialTags(material, descriptor);
            if (material.equals("TURTLE_HELMET")) {
                descriptor.addTrait("turtle");
                descriptor.addTrait("waterbreathing");
            }
        }
        if (material.endsWith("_CHESTPLATE")) {
            descriptor.addTrait("armor");
            descriptor.addTrait("chestplate");
            descriptor.addTrait("chest");
            descriptor.addTrait("body");
            descriptor.addTrait("wearable");
            addMaterialTags(material, descriptor);
        }
        if (material.endsWith("_LEGGINGS")) {
            descriptor.addTrait("armor");
            descriptor.addTrait("leggings");
            descriptor.addTrait("pants");
            descriptor.addTrait("legs");
            descriptor.addTrait("wearable");
            addMaterialTags(material, descriptor);
        }
        if (material.endsWith("_BOOTS")) {
            descriptor.addTrait("armor");
            descriptor.addTrait("boots");
            descriptor.addTrait("footwear");
            descriptor.addTrait("feet");
            descriptor.addTrait("wearable");
            addMaterialTags(material, descriptor);
        }
        if (material.equals("ELYTRA")) {
            descriptor.addTrait("armor");
            descriptor.addTrait("elytra");
            descriptor.addTrait("wings");
            descriptor.addTrait("chest");
            descriptor.addTrait("wearable");
            descriptor.addTrait("flying");
            descriptor.addTrait("gliding");
        }
        if (material.contains("_HORSE_ARMOR")) {
            descriptor.addTrait("armor");
            descriptor.addTrait("horsearmor");
            descriptor.addTrait("horse");
            descriptor.addTrait("mount");
            descriptor.addTrait("pet");
            if (material.contains("IRON")) descriptor.addTrait("iron");
            if (material.contains("GOLDEN") || material.contains("GOLD")) descriptor.addTrait("gold");
            if (material.contains("DIAMOND")) descriptor.addTrait("diamond");
            if (material.contains("LEATHER")) descriptor.addTrait("leather");
        }
        if (material.equals("WOLF_ARMOR")) {
            descriptor.addTrait("armor");
            descriptor.addTrait("wolfarmor");
            descriptor.addTrait("wolf");
            descriptor.addTrait("pet");
        }
        if (material.startsWith("LEATHER_")) {
            descriptor.addTrait("leather");
            descriptor.addTrait("dyeable");
        }
        if (material.startsWith("CHAINMAIL_")) {
            descriptor.addTrait("chainmail");
            descriptor.addTrait("chain");
        }
        if (material.contains("_HEAD") || material.contains("_SKULL") || material.equals("PLAYER_HEAD")
                || material.equals("DRAGON_HEAD")) {
            descriptor.addTrait("head");
            descriptor.addTrait("headgear");
            descriptor.addTrait("wearable");
            descriptor.addTrait("decorative");
            if (material.contains("SKELETON")) descriptor.addTrait("skeleton");
            if (material.contains("ZOMBIE")) descriptor.addTrait("zombie");
            if (material.contains("CREEPER")) descriptor.addTrait("creeper");
            if (material.contains("WITHER")) descriptor.addTrait("wither");
            if (material.contains("DRAGON")) descriptor.addTrait("dragon");
            if (material.contains("PIGLIN")) descriptor.addTrait("piglin");
            if (material.contains("PLAYER")) descriptor.addTrait("player");
        }
        if (material.equals("CARVED_PUMPKIN")) {
            descriptor.addTrait("head");
            descriptor.addTrait("headgear");
            descriptor.addTrait("wearable");
            descriptor.addTrait("pumpkin");
            descriptor.addTrait("enderman");
        }
    }

    private static void contributeStorage(String material, ItemDescriptor.Builder descriptor) {
        if (material.contains("SHULKER_BOX")) {
            descriptor.addTrait("shulkerbox");
            descriptor.addTrait("storage");
            descriptor.addTrait("container");
        }
        if (material.contains("CHEST")) {
            descriptor.addTrait("chest");
            descriptor.addTrait("storage");
            if (material.equals("CHEST") || material.equals("TRAPPED_CHEST")) {
                addWood(descriptor);
                descriptor.addCustomTag("wooden");
                descriptor.addCustomTag("planks");
            }
        }
        if (material.contains("BARREL")) {
            descriptor.addTrait("barrel");
            descriptor.addTrait("storage");
        }
        if (material.equals("BUNDLE")) {
            descriptor.addTrait("bundle");
            descriptor.addTrait("storage");
            descriptor.addTrait("container");
        }
    }

    private static String stripNamespace(String materialKey) {
        if (materialKey == null) return "";
        int colon = materialKey.indexOf(':');
        return colon < 0 ? materialKey : materialKey.substring(colon + 1);
    }

    private static Predicate<String> contains(String fragment) {
        return value -> value.contains(fragment);
    }

    private static Predicate<String> any(Predicate<String> first, Predicate<String> second) {
        return value -> first.test(value) || second.test(value);
    }
}
