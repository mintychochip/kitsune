package org.aincraft.kitsune.visualizer;

import org.aincraft.kitsune.BukkitLocation;
import org.aincraft.kitsune.config.KitsuneConfig;
import org.aincraft.kitsune.model.SearchResult;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Visualizes container search results using ItemDisplay entities to show item icons.
 * Tracks displays per-container location so multiple chests can show items simultaneously.
 */
public class ContainerItemDisplay {
    private final Logger logger;
    private final KitsuneConfig config;
    private final JavaPlugin plugin;

    // Track displays per location key (world:x:y:z)
    private final Map<String, List<ItemDisplay>> locationDisplays = new HashMap<>();
    private final Map<String, Map<ItemDisplay, Double>> locationInitialAngles = new HashMap<>();
    private final Map<String, Map<ItemDisplay, Integer>> locationItemIndices = new HashMap<>();
    private final Map<String, Integer> locationAnimationTasks = new HashMap<>();
    private final Map<String, Location> locationCenters = new HashMap<>();
    private final Map<String, TextDisplay> locationTextDisplays = new HashMap<>();

    public ContainerItemDisplay(Logger logger, KitsuneConfig config, JavaPlugin plugin) {
        this.logger = logger;
        this.config = config;
        this.plugin = plugin;
    }

    /**
     * Generates a unique key for a block location.
     */
    private String locationKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    /**
     * Spawns ItemDisplay entities showing the top items from a container search result.
     */
    public void spawnItemDisplays(SearchResult result, Player player) {
        if (player == null || !player.isOnline()) {
            logger.info("[ItemDisplay] Player is null or offline");
            return;
        }

        Location bukkitLoc = BukkitLocation.toBukkitOrNull(result.location());
        if (bukkitLoc == null) {
            logger.info("[ItemDisplay] BukkitLocation is null");
            return;
        }

        String locKey = locationKey(bukkitLoc);

        // Clean up existing displays for THIS location only
        clearLocationDisplays(locKey);

        int displayCount = config.visualizerDisplayCount();
        if (displayCount <= 0) {
            displayCount = 6;
        }

        World world = bukkitLoc.getWorld();
        if (world == null) {
            logger.info("[ItemDisplay] World is null");
            return;
        }

        List<ItemStack> topItems = getTopItemsFromResult(result, displayCount);
        logger.info("[ItemDisplay] Preview: " + result.preview() + ", Items found: " + topItems.size());
        if (topItems.isEmpty()) {
            return;
        }

        List<ItemDisplay> displays = new ArrayList<>();
        Map<ItemDisplay, Double> initialAngles = new HashMap<>();
        Map<ItemDisplay, Integer> itemIndices = new HashMap<>();

        double radius = config.visualizerRadius();
        double heightOffset = config.visualizerHeight();

        for (int i = 0; i < topItems.size(); i++) {
            double angle = (2 * Math.PI * i) / topItems.size(); // Spread evenly in circle
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;

            Location displayLoc = bukkitLoc.clone().add(x + 0.5, heightOffset, z + 0.5);
            final ItemStack itemToDisplay = topItems.get(i);

            try {
                ItemDisplay display = world.spawn(displayLoc, ItemDisplay.class, entity -> {
                    entity.setItemStack(itemToDisplay);
                    entity.setGlowing(true);
                    entity.setBrightness(new Display.Brightness(15, 15));

                    Transformation transformation = new Transformation(
                        new Vector3f(0, 0, 0),
                        new AxisAngle4f(0, 0, 0, 1),
                        new Vector3f(0.5f, 0.5f, 0.5f),
                        new AxisAngle4f(0, 0, 0, 1)
                    );
                    entity.setTransformation(transformation);
                    entity.setBillboard(Display.Billboard.CENTER);
                    entity.setViewRange(100f);
                });

                initialAngles.put(display, angle);
                itemIndices.put(display, i);
                displays.add(display);
            } catch (Exception e) {
                logger.warning("Failed to spawn ItemDisplay: " + e.getMessage());
            }
        }

        if (!displays.isEmpty()) {
            locationDisplays.put(locKey, displays);
            locationInitialAngles.put(locKey, initialAngles);
            locationItemIndices.put(locKey, itemIndices);
            locationCenters.put(locKey, bukkitLoc.clone());

            // Spawn TextDisplay showing item count - start at chest center
            int itemCount = topItems.size();
            Location textStartLoc = bukkitLoc.clone().add(0.5, 0.5, 0.5);
            try {
                TextDisplay textDisplay = world.spawn(textStartLoc, TextDisplay.class, entity -> {
                    entity.text(net.kyori.adventure.text.Component.text()
                        .append(net.kyori.adventure.text.Component.text("x" + itemCount,
                            net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                        .append(net.kyori.adventure.text.Component.text(" items",
                            net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .build());
                    entity.setBillboard(Display.Billboard.CENTER);
                    entity.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                    entity.setBrightness(new Display.Brightness(15, 15));
                    entity.setViewRange(100f);
                });
                locationTextDisplays.put(locKey, textDisplay);
            } catch (Exception e) {
                logger.warning("Failed to spawn TextDisplay: " + e.getMessage());
            }

            // Start animation for this location
            startAnimation(locKey);

            // Schedule cleanup for this location
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                clearLocationDisplays(locKey);
            }, config.visualizerDurationTicks());
        }
    }

    private List<ItemStack> getTopItemsFromResult(SearchResult result, int maxItems) {
        List<ItemStack> items = new ArrayList<>();

        try {
            String preview = result.preview();
            if (preview == null) {
                preview = "";
            }
            String lowerPreview = preview.toLowerCase();

            if (lowerPreview.contains("diamond")) {
                items.add(new ItemStack(org.bukkit.Material.DIAMOND));
            }
            if (lowerPreview.contains("iron")) {
                items.add(new ItemStack(org.bukkit.Material.IRON_INGOT));
            }
            if (lowerPreview.contains("gold")) {
                items.add(new ItemStack(org.bukkit.Material.GOLD_INGOT));
            }
            if (lowerPreview.contains("emerald")) {
                items.add(new ItemStack(org.bukkit.Material.EMERALD));
            }
            if (lowerPreview.contains("netherite")) {
                items.add(new ItemStack(org.bukkit.Material.NETHERITE_INGOT));
            }
            if (lowerPreview.contains("redstone")) {
                items.add(new ItemStack(org.bukkit.Material.REDSTONE));
            }
            if (lowerPreview.contains("lapis")) {
                items.add(new ItemStack(org.bukkit.Material.LAPIS_LAZULI));
            }
            if (lowerPreview.contains("coal")) {
                items.add(new ItemStack(org.bukkit.Material.COAL));
            }
            if (lowerPreview.contains("chest") || lowerPreview.contains("container")) {
                items.add(new ItemStack(org.bukkit.Material.CHEST));
            }
            if (lowerPreview.contains("shulker")) {
                items.add(new ItemStack(org.bukkit.Material.SHULKER_BOX));
            }
            if (lowerPreview.contains("barrel")) {
                items.add(new ItemStack(org.bukkit.Material.BARREL));
            }
            if (lowerPreview.contains("sword")) {
                items.add(new ItemStack(org.bukkit.Material.DIAMOND_SWORD));
            }
            if (lowerPreview.contains("pickaxe")) {
                items.add(new ItemStack(org.bukkit.Material.DIAMOND_PICKAXE));
            }
            if (lowerPreview.contains("apple")) {
                items.add(new ItemStack(org.bukkit.Material.APPLE));
            }

            if (items.isEmpty()) {
                items.add(new ItemStack(org.bukkit.Material.CHEST));
                items.add(new ItemStack(org.bukkit.Material.DIAMOND));
                if (maxItems > 2) {
                    items.add(new ItemStack(org.bukkit.Material.IRON_INGOT));
                }
                if (maxItems > 3) {
                    items.add(new ItemStack(org.bukkit.Material.GOLD_INGOT));
                }
            }

            if (items.size() > maxItems) {
                return items.subList(0, maxItems);
            }

        } catch (Exception e) {
            logger.warning("Failed to get items from search result: " + e.getMessage());
            items.add(new ItemStack(org.bukkit.Material.CHEST));
        }

        return items;
    }

    /**
     * Clears displays for a specific location.
     */
    private void clearLocationDisplays(String locKey) {
        // Cancel animation task
        Integer taskId = locationAnimationTasks.remove(locKey);
        if (taskId != null) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }

        List<ItemDisplay> displays = locationDisplays.remove(locKey);
        if (displays != null) {
            for (ItemDisplay display : displays) {
                if (display != null && !display.isDead()) {
                    display.remove();
                }
            }
        }

        TextDisplay textDisplay = locationTextDisplays.remove(locKey);
        if (textDisplay != null && !textDisplay.isDead()) {
            textDisplay.remove();
        }

        // Clear angles, indices and center
        locationInitialAngles.remove(locKey);
        locationItemIndices.remove(locKey);
        locationCenters.remove(locKey);
    }

    /**
     * Removes all displays for a specific player (cleanup on quit).
     */
    public void removeDisplaysForPlayer(Player player) {
        // This is now a no-op since we track by location, not player
        // Displays will naturally expire via their scheduled cleanup
    }

    /**
     * Removes all displays when the plugin is disabled.
     */
    public void cleanupAll() {
        for (List<ItemDisplay> displays : locationDisplays.values()) {
            for (ItemDisplay display : displays) {
                if (display != null && !display.isDead()) {
                    display.remove();
                }
            }
        }
        locationDisplays.clear();
        locationInitialAngles.clear();
        locationItemIndices.clear();
        locationCenters.clear();

        for (TextDisplay textDisplay : locationTextDisplays.values()) {
            if (textDisplay != null && !textDisplay.isDead()) {
                textDisplay.remove();
            }
        }
        locationTextDisplays.clear();

        for (Integer taskId : locationAnimationTasks.values()) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }
        locationAnimationTasks.clear();

        logger.info("Cleaned up all ItemDisplay entities");
    }

    // Animation phases
    private static final int EMERGE_DURATION = 15; // ticks for emerge animation

    /**
     * Easing function - ease out back for a satisfying "pop" effect
     */
    private double easeOutBack(double t) {
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }

    /**
     * Starts the animation task for a specific location.
     */
    private void startAnimation(String locKey) {
        Location centerLoc = locationCenters.get(locKey);
        if (centerLoc == null) return;

        // Initialize all displays at chest center (inside the chest)
        List<ItemDisplay> displays = locationDisplays.get(locKey);
        if (displays != null) {
            for (ItemDisplay display : displays) {
                if (display != null && !display.isDead()) {
                    // Start at center of chest, slightly inside
                    Location startLoc = centerLoc.clone().add(0.5, 0.5, 0.5);
                    display.teleport(startLoc);
                    // Start small
                    Transformation startTransform = new Transformation(
                        new Vector3f(0, 0, 0),
                        new AxisAngle4f(0, 0, 0, 1),
                        new Vector3f(0.01f, 0.01f, 0.01f),
                        new AxisAngle4f(0, 0, 0, 1)
                    );
                    display.setTransformation(startTransform);
                }
            }
        }

        BukkitRunnable task = new BukkitRunnable() {
            private int tick = 0;

            @Override
            public void run() {
                List<ItemDisplay> displays = locationDisplays.get(locKey);
                Map<ItemDisplay, Double> angles = locationInitialAngles.get(locKey);
                Map<ItemDisplay, Integer> indices = locationItemIndices.get(locKey);

                if (displays == null || angles == null || indices == null || displays.isEmpty()) {
                    cancel();
                    return;
                }

                double spinSpeed = config.visualizerSpinSpeed();
                double radius = config.visualizerRadius();
                double heightOffset = config.visualizerHeight();
                int totalItems = displays.size();

                for (ItemDisplay display : displays) {
                    if (display == null || display.isDead()) {
                        continue;
                    }

                    Double initialAngle = angles.get(display);
                    Integer itemIndex = indices.get(display);
                    if (initialAngle == null || itemIndex == null) {
                        continue;
                    }

                    double x, y, scale;
                    double bobOffset = 0;

                    if (tick < EMERGE_DURATION) {
                        // Emerge animation - items come out of chest
                        double progress = (double) tick / EMERGE_DURATION;
                        double easedProgress = easeOutBack(progress);

                        // Stagger each item slightly
                        double staggerDelay = itemIndex * 0.1;
                        double adjustedProgress = Math.max(0, Math.min(1, (progress - staggerDelay) / (1 - staggerDelay)));
                        double adjustedEased = easeOutBack(adjustedProgress);

                        // Interpolate from center to circle position
                        double targetX = Math.cos(initialAngle) * radius;
                        double targetZ = Math.sin(initialAngle) * radius;

                        x = targetX * adjustedEased;
                        double z = targetZ * adjustedEased;
                        y = heightOffset * adjustedEased;

                        // Scale grows from tiny to full
                        scale = 0.01 + 0.49 * adjustedEased;

                        Location newLoc = centerLoc.clone().add(x + 0.5, y + 0.5, z + 0.5);
                        display.teleport(newLoc);
                    } else {
                        // Normal floating animation after emerge
                        x = Math.cos(initialAngle) * radius;
                        double z = Math.sin(initialAngle) * radius;

                        // Bobbing motion (up/down oscillation) - staggered per item
                        int floatTick = tick - EMERGE_DURATION;
                        double phaseOffset = (2 * Math.PI * itemIndex) / totalItems;
                        bobOffset = Math.sin(floatTick * 0.15 + phaseOffset) * 0.2;

                        Location newLoc = centerLoc.clone().add(x + 0.5, heightOffset + bobOffset, z + 0.5);
                        display.teleport(newLoc);

                        scale = 0.5;
                    }

                    // Apply spin
                    if (config.visualizerSpinEnabled()) {
                        double spinAngle = Math.toRadians(spinSpeed * tick);
                        Transformation transformation = display.getTransformation();

                        AxisAngle4f spinRotation = new AxisAngle4f((float) spinAngle, 0, 1, 0);
                        transformation = new Transformation(
                            transformation.getTranslation(),
                            spinRotation,
                            new Vector3f((float) scale, (float) scale, (float) scale),
                            new AxisAngle4f(transformation.getRightRotation())
                        );

                        display.setTransformation(transformation);
                    }
                }

                // Animate text display
                TextDisplay textDisplay = locationTextDisplays.get(locKey);
                if (textDisplay != null && !textDisplay.isDead()) {
                    double textHeightOffset = heightOffset + 0.8;
                    if (tick < EMERGE_DURATION) {
                        double progress = (double) tick / EMERGE_DURATION;
                        double easedProgress = easeOutBack(progress);
                        double textY = textHeightOffset * easedProgress;
                        Location textLoc = centerLoc.clone().add(0.5, textY + 0.5, 0.5);
                        textDisplay.teleport(textLoc);
                    } else {
                        int floatTick = tick - EMERGE_DURATION;
                        double textBob = Math.sin(floatTick * 0.15) * 0.1;
                        Location textLoc = centerLoc.clone().add(0.5, textHeightOffset + textBob, 0.5);
                        textDisplay.teleport(textLoc);
                    }
                }

                tick++;
            }
        };

        int taskId = task.runTaskTimer(plugin, 0, 1).getTaskId();
        locationAnimationTasks.put(locKey, taskId);
    }
}
