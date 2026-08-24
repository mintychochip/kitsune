package org.aincraft.kitsune.serialization.providers;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.aincraft.kitsune.Item;
import org.aincraft.kitsune.api.serialization.TagProvider;
import org.aincraft.kitsune.api.serialization.TagProviderRegistry;
import org.aincraft.kitsune.api.serialization.Tags;

public final class TagProviders {
    private TagProviders() {}

    private static final Map<Predicate<String>, Set<String>> MATERIAL_TAGS = Map.of(
        TagMatch.contains("NETHERITE"), Set.of("netherite"),
        TagMatch.contains("DIAMOND"), Set.of("diamond"),
        TagMatch.contains("IRON"), Set.of("iron"),
        TagMatch.any(TagMatch.contains("GOLD"), TagMatch.contains("GOLDEN")), Set.of("gold", "golden"),
        TagMatch.contains("STONE"), Set.of("stone"),
        TagMatch.any(TagMatch.contains("WOOD"), TagMatch.contains("WOODEN")), Set.of("wood", "wooden"),
        TagMatch.contains("LEATHER"), Set.of("leather"),
        TagMatch.contains("CHAINMAIL"), Set.of("chainmail")
    );

    private static void addMaterialTags(String material, Tags tags) {
        for (Map.Entry<Predicate<String>, Set<String>> entry : MATERIAL_TAGS.entrySet()) {
            if (entry.getKey().test(material)) {
                tags.pushAll(entry.getValue());
                break;
            }
        }
    }

    public static final TagProvider ENCHANTMENT = (tags, item) -> {
        var enchantments = item.enchantments();
        if (!enchantments.isEmpty()) {
            tags.push("enchanted");
        }
        for (var entry : enchantments.entrySet()) {
            tags.push(entry.getKey(), entry.getKey() + "_" + entry.getValue());
        }
    };

    public static final TagProvider SOLID_BLOCK = (tags, item) -> {
        tags.pushIf(item.isSolid(), "solid").pushIf(item.isBlock(), "block");
    };

    public static final TagProvider BLOCK_OPACITY = (tags, item) -> {
        tags.pushIf(item.isOccluding(), "occluding")
            .pushIf(item.isBlock() && !item.isOccluding(), "transparent");
    };

    public static final TagProvider BLOCK_GRAVITY = (tags, item) -> {
        tags.pushIf(item.hasGravity(), "gravity", "falling");
    };

    public static final TagProvider REDSTONE = (tags, item) -> {
        String m = item.material().toUpperCase();
        tags.pushIf(m.contains("REDSTONE"), "redstone")
            .pushIf(m.contains("POWERED"), "powered")
            .pushIf(m.contains("COMPARATOR"), "comparator")
            .pushIf(m.contains("REPEATER"), "repeater");
    };

    public static final TagProvider BLOCK_COLOR = (tags, item) -> {
        String[] colors = {"white","orange","magenta","light_blue","yellow","lime","pink","gray",
            "light_gray","cyan","purple","blue","brown","green","red","black"};
        String m = item.material().toUpperCase();
        for (String color : colors) {
            if (m.startsWith(color.toUpperCase() + "_")) {
                tags.push(color);
                break;
            }
        }
    };

    public static final TagProvider BLOCK_MATERIAL = (tags, item) -> {
        String u = item.material().toUpperCase();

        tags.when(u.contains("GLASS"), t -> t.push("glass").pushIf(u.contains("PANE"), "pane"));
        tags.when(u.contains("WOOL"), t -> t.push("wool", "soft"));
        tags.when(u.contains("CONCRETE"), t -> t.push("concrete").pushIf(u.contains("POWDER"), "powder"));
        tags.when(u.contains("TERRACOTTA"), t -> t.push("terracotta", "clay").pushIf(u.contains("GLAZED"), "glazed"));
        tags.when(u.contains("CANDLE"), t -> t.push("candle", "lightsource"));
        tags.when(u.contains("CARPET"), t -> t.push("carpet", "flooring"));
        tags.when(u.contains("BED") && !u.contains("BEDROCK"), t -> t.push("bed", "furniture"));
        tags.when(u.contains("BANNER"), t -> t.push("banner", "decorative"));
        tags.when(u.contains("SHULKER"), t -> t.push("shulker", "storage", "container"));
        tags.when(u.contains("OAK") && !u.contains("DARK_OAK"), t -> t.push("oak", "wood"));
        tags.when(u.contains("SPRUCE"), t -> t.push("spruce", "wood"));
        tags.when(u.contains("BIRCH"), t -> t.push("birch", "wood"));
        tags.when(u.contains("JUNGLE"), t -> t.push("jungle", "wood"));
        tags.when(u.contains("ACACIA"), t -> t.push("acacia", "wood"));
        tags.when(u.contains("DARK_OAK"), t -> t.push("darkoak", "wood"));
        tags.when(u.contains("MANGROVE"), t -> t.push("mangrove", "wood"));
        tags.when(u.contains("CHERRY"), t -> t.push("cherry", "wood"));
        tags.when(u.contains("BAMBOO"), t -> t.push("bamboo", "wood"));
        tags.when(u.contains("CRIMSON"), t -> t.push("crimson", "netherstem"));
        tags.when(u.contains("WARPED"), t -> t.push("warped", "netherstem"));
        tags.when(u.contains("STONE") && !u.contains("REDSTONE") && !u.contains("GLOWSTONE") && !u.contains("SANDSTONE"),
            t -> t.push("stone"));
        tags.when(u.contains("COBBLESTONE"), t -> t.push("cobblestone", "cobble"));
        tags.when(u.contains("DEEPSLATE"), t -> t.push("deepslate"));
        tags.when(u.contains("BRICK"), t -> t.push("brick"));
        tags.when(u.contains("SANDSTONE"), t -> t.push("sandstone"));
        tags.when(u.endsWith("_ORE"), t -> t.push("ore", "mineable"));
    };

    public static final TagProvider MINERAL = (tags, item) -> {
        String u = item.material().toUpperCase();

        tags.when(u.endsWith("_ORE") || u.equals("NETHER_GOLD_ORE") || u.equals("ANCIENT_DEBRIS"),
            t -> t.push("ore", "mineral", "mineable"));
        tags.when(u.startsWith("RAW_"), t -> t.push("raw", "mineral", "smeltable"));
        tags.when(u.endsWith("_INGOT") || u.equals("NETHERITE_INGOT") || u.equals("COPPER_INGOT"),
            t -> t.push("ingot", "mineral", "metal", "refined"));
        tags.when(u.endsWith("_NUGGET"), t -> t.push("nugget", "mineral", "metal"));
        tags.when(Set.of("DIAMOND","EMERALD","AMETHYST_SHARD","LAPIS_LAZULI","PRISMARINE_SHARD",
                "PRISMARINE_CRYSTALS","QUARTZ","NETHER_QUARTZ").contains(u),
            t -> t.push("gem", "mineral", "precious"));
        tags.when(u.contains("DIAMOND"), t -> t.push("diamond", "mineral"));
        tags.when(u.contains("EMERALD"), t -> t.push("emerald", "mineral"));
        tags.when(u.contains("GOLD") || u.contains("GOLDEN"), t -> t.push("gold", "mineral"));
        tags.when(u.contains("IRON"), t -> t.push("iron", "mineral"));
        tags.when(u.contains("COPPER"), t -> t.push("copper", "mineral"));
        tags.when(u.contains("NETHERITE"), t -> t.push("netherite", "mineral"));
        tags.when(u.contains("LAPIS"), t -> t.push("lapis", "mineral"));
        tags.when(u.contains("REDSTONE"), t -> t.push("redstone", "mineral"));
        tags.when(u.contains("QUARTZ"), t -> t.push("quartz", "mineral"));
        tags.when(u.contains("AMETHYST"), t -> t.push("amethyst", "mineral"));
        tags.when(u.contains("COAL") && !u.contains("CHARCOAL"), t -> t.push("coal", "mineral"));
        tags.when(u.endsWith("_BLOCK") && (u.contains("DIAMOND")||u.contains("EMERALD")||u.contains("GOLD")||
                u.contains("IRON")||u.contains("COPPER")||u.contains("NETHERITE")||u.contains("LAPIS")||
                u.contains("REDSTONE")||u.contains("COAL")||u.contains("AMETHYST")||u.contains("QUARTZ")||
                u.contains("RAW_")),
            t -> t.push("mineralblock", "storage"));
    };

    public static final TagProvider WEAPON = (tags, item) -> {
        String u = item.material().toUpperCase();

        tags.when(u.endsWith("_SWORD"), t -> {
            t.push("weapon", "sword", "melee", "combat");
            addMaterialTags(u, t);
        });
        tags.when(u.endsWith("_AXE") && !u.contains("PICKAXE"), t -> {
            t.push("weapon", "axe", "melee", "combat");
            addMaterialTags(u, t);
        });
        tags.when(u.equals("BOW"), t -> t.push("weapon", "bow", "ranged", "projectile", "combat"));
        tags.when(u.equals("CROSSBOW"), t -> t.push("weapon", "crossbow", "ranged", "projectile", "combat"));
        tags.when(u.equals("TRIDENT"), t -> t.push("weapon", "trident", "melee", "ranged", "throwable", "combat"));
        tags.when(u.equals("MACE"), t -> t.push("weapon", "mace", "melee", "combat", "heavyhitter"));
        tags.when(u.contains("ARROW"), t -> {
            t.push("ammo", "ammunition", "projectile", "combat");
            t.pushIf(u.equals("SPECTRAL_ARROW"), "spectral");
            t.pushIf(u.equals("TIPPED_ARROW"), "tipped", "potion");
        });
        tags.when(u.equals("FIREWORK_ROCKET"), t -> t.push("ammo", "firework", "projectile", "elytra"));
        tags.when(u.equals("SNOWBALL") || u.equals("EGG"), t -> t.push("weapon", "throwable", "projectile"));
        tags.when(u.equals("ENDER_PEARL"), t -> t.push("throwable", "teleport", "projectile"));
        tags.when(u.equals("WIND_CHARGE"), t -> t.push("weapon", "throwable", "projectile", "knockback"));
        tags.when(u.equals("SHIELD"), t -> t.push("weapon", "shield", "defense", "combat", "blocking"));
    };

    public static final TagProvider TOOL = (tags, item) -> {
        String u = item.material().toUpperCase();

        tags.when(u.endsWith("_PICKAXE"), t -> { t.push("tool", "pickaxe", "mining", "breaking"); addMaterialTags(u, t); });
        tags.when(u.endsWith("_SHOVEL"), t -> { t.push("tool", "shovel", "digging", "breaking"); addMaterialTags(u, t); });
        tags.when(u.endsWith("_HOE"), t -> { t.push("tool", "hoe", "farming", "tilling"); addMaterialTags(u, t); });
        tags.when(u.endsWith("_AXE") && !u.contains("PICKAXE"), t -> {
            t.push("tool", "axe", "woodcutting", "chopping", "breaking");
            addMaterialTags(u, t);
        });
        tags.when(u.equals("SHEARS"), t -> t.push("tool", "shears", "shearing", "harvesting"));
        tags.when(u.equals("FLINT_AND_STEEL"), t -> t.push("tool", "flintandsteel", "fire", "igniter"));
        tags.when(u.equals("FISHING_ROD"), t -> t.push("tool", "fishingrod", "fishing", "catching"));
        tags.when(u.equals("CARROT_ON_A_STICK") || u.equals("WARPED_FUNGUS_ON_A_STICK"), t -> t.push("tool", "riding", "control"));
        tags.when(u.equals("LEAD"), t -> t.push("tool", "lead", "leash", "mob"));
        tags.when(u.equals("NAME_TAG"), t -> t.push("tool", "nametag", "naming", "mob"));
        tags.when(u.equals("BRUSH"), t -> t.push("tool", "brush", "archaeology", "excavation"));
        tags.when(u.equals("SPYGLASS"), t -> t.push("tool", "spyglass", "zoom", "scouting"));
        tags.when(u.equals("COMPASS") || u.equals("RECOVERY_COMPASS"), t -> {
            t.push("tool", "compass", "navigation");
            t.pushIf(u.equals("RECOVERY_COMPASS"), "recovery", "death");
        });
        tags.when(u.equals("CLOCK"), t -> t.push("tool", "clock", "time"));
        tags.when(u.contains("MAP"), t -> {
            t.push("tool", "map", "navigation");
            t.pushIf(u.equals("FILLED_MAP"), "filled");
        });
        tags.when(u.contains("BUCKET"), t -> {
            t.push("tool", "bucket", "container");
            t.pushIf(u.equals("WATER_BUCKET"), "water")
             .pushIf(u.equals("LAVA_BUCKET"), "lava")
             .pushIf(u.equals("MILK_BUCKET"), "milk")
             .pushIf(u.equals("POWDER_SNOW_BUCKET"), "powdersnow");
            t.when(u.contains("FISH_BUCKET") || u.equals("AXOLOTL_BUCKET") || u.equals("TADPOLE_BUCKET"),
                bt -> bt.push("mobbucket", "mob"));
        });
        tags.when(u.equals("BONE_MEAL"), t -> t.push("tool", "bonemeal", "farming", "growth"));
        tags.when(u.equals("WRITABLE_BOOK") || u.equals("WRITTEN_BOOK"), t -> t.push("tool", "book", "writing"));
    };

    public static final TagProvider ARMOR = (tags, item) -> {
        String u = item.material().toUpperCase();

        tags.when(TagMatch.any(TagMatch.endsWith("_HELMET"), TagMatch.equals("TURTLE_HELMET")).test(u), t -> {
            t.push("armor", "helmet", "headgear", "head", "wearable");
            addMaterialTags(u, t);
            t.pushIf(u.equals("TURTLE_HELMET"), "turtle", "waterbreathing");
        });
        tags.when(u.endsWith("_CHESTPLATE"), t -> { t.push("armor", "chestplate", "chest", "body", "wearable"); addMaterialTags(u, t); });
        tags.when(u.endsWith("_LEGGINGS"), t -> { t.push("armor", "leggings", "pants", "legs", "wearable"); addMaterialTags(u, t); });
        tags.when(u.endsWith("_BOOTS"), t -> { t.push("armor", "boots", "footwear", "feet", "wearable"); addMaterialTags(u, t); });
        tags.when(u.equals("ELYTRA"), t -> t.push("armor", "elytra", "wings", "chest", "wearable", "flying", "gliding"));
        tags.when(u.contains("_HORSE_ARMOR"), t -> {
            t.push("armor", "horsearmor", "horse", "mount", "pet");
            t.pushIf(u.contains("IRON"), "iron")
             .pushIf(u.contains("GOLDEN") || u.contains("GOLD"), "gold")
             .pushIf(u.contains("DIAMOND"), "diamond")
             .pushIf(u.contains("LEATHER"), "leather");
        });
        tags.when(u.equals("WOLF_ARMOR"), t -> t.push("armor", "wolfarmor", "wolf", "pet"));
        tags.when(u.startsWith("LEATHER_"), t -> t.push("leather", "dyeable"));
        tags.when(u.startsWith("CHAINMAIL_"), t -> t.push("chainmail", "chain"));
        tags.when(u.contains("_HEAD") || u.contains("_SKULL") || u.equals("PLAYER_HEAD") || u.equals("DRAGON_HEAD"), t -> {
            t.push("head", "headgear", "wearable", "decorative");
            t.pushIf(u.contains("SKELETON"), "skeleton")
             .pushIf(u.contains("ZOMBIE"), "zombie")
             .pushIf(u.contains("CREEPER"), "creeper")
             .pushIf(u.contains("WITHER"), "wither")
             .pushIf(u.contains("DRAGON"), "dragon")
             .pushIf(u.contains("PIGLIN"), "piglin")
             .pushIf(u.contains("PLAYER"), "player");
        });
        tags.when(u.equals("CARVED_PUMPKIN"), t -> t.push("head", "headgear", "wearable", "pumpkin", "enderman"));
    };

    public static final TagProvider STORAGE = (tags, item) -> {
        String u = item.material().toUpperCase();
        tags.when(item.hasBundle(), t -> t.push("bundle", "storage", "container"));
        tags.when(item.hasShulkerContents(), t -> t.push("shulkerbox", "storage", "container"));
        tags.when(u.contains("SHULKER_BOX"), t -> t.push("shulkerbox", "storage", "container"));
        tags.when(u.contains("CHEST"), t -> t.push("chest", "storage"));
        tags.when(u.contains("BARREL"), t -> t.push("barrel", "storage"));
        tags.when(u.equals("BUNDLE"), t -> t.push("bundle", "storage", "container"));
    };

    public static final TagProvider UNBREAKABLE = (tags, item) -> {
        tags.pushIf(item.isUnbreakable(), "unbreakable");
    };

    public static void registerDefaults(TagProviderRegistry registry) {
        registry.register(ENCHANTMENT);
        registry.register(SOLID_BLOCK);
        registry.register(BLOCK_OPACITY);
        registry.register(BLOCK_GRAVITY);
        registry.register(REDSTONE);
        registry.register(BLOCK_COLOR);
        registry.register(BLOCK_MATERIAL);
        registry.register(MINERAL);
        registry.register(WEAPON);
        registry.register(TOOL);
        registry.register(ARMOR);
        registry.register(STORAGE);
        registry.register(UNBREAKABLE);
    }
}
