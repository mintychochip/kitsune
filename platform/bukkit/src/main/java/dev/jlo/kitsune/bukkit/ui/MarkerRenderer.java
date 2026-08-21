package dev.jlo.kitsune.ui;

import dev.jlo.kitsune.item.BukkitItemStackFactory;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.search.ItemMatch;
import org.bukkit.Server;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Spawns and tracks world display markers for search results.
 */
public final class MarkerRenderer {
    private static final double TEXT_Y_OFFSET = 2.0;
    private static final double ITEM_Y_OFFSET = 1.15;
    private static final float ITEM_SCALE = 0.45f;

    private final Plugin plugin;
    private final Server server;

    /**
     * Creates a renderer on the plugin owning the spawned displays.
     *
     * @param plugin plugin owning spawned displays
     */
    public MarkerRenderer(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin must not be null");
        this.server = Objects.requireNonNull(plugin.getServer(), "Server must not be null");
    }

    /**
     * Spawns a caption and optional featured item icon above a root.
     *
     * @param owner player the marker is shown to
     * @param root root block the marker labels
     * @param text caption displayed above the root
     * @param featuredItem best matching item to preview, or {@code null} for text only
     * @return the spawned marker, or empty when the root is unreachable
     */
    public Optional<RenderedMarker> spawn(
        Player owner,
        BlockKey root,
        String text,
        ItemMatch featuredItem
    ) {
        Objects.requireNonNull(owner, "Owner must not be null");
        Objects.requireNonNull(root, "Root must not be null");
        Objects.requireNonNull(text, "Text must not be null");

        List<Display> spawnedDisplays = new ArrayList<>(2);
        try {
            if (!owner.isOnline() || !owner.getWorld().getUID().equals(root.worldId())) {
                return Optional.empty();
            }
            World world = server.getWorld(root.worldId());
            if (world == null || !world.getUID().equals(root.worldId())) {
                return Optional.empty();
            }
            int chunkX = Math.floorDiv(root.x(), 16);
            int chunkZ = Math.floorDiv(root.z(), 16);
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                return Optional.empty();
            }

            TextDisplay textDisplay = spawnTextDisplay(world, root, text, spawnedDisplays);
            if (textDisplay == null) {
                removeQuietly(spawnedDisplays);
                return Optional.empty();
            }

            ItemDisplay itemDisplay = spawnItemDisplay(world, root, featuredItem, spawnedDisplays);
            owner.showEntity(plugin, textDisplay);
            if (itemDisplay != null) {
                owner.showEntity(plugin, itemDisplay);
            }

            return Optional.of(new CompositeMarker(
                root,
                textDisplay,
                itemDisplay
            ));
        } catch (RuntimeException failure) {
            removeQuietly(spawnedDisplays);
            return Optional.empty();
        }
    }

    private TextDisplay spawnTextDisplay(
        World world,
        BlockKey root,
        String text,
        List<Display> spawnedDisplays
    ) {
        Location location = markerLocation(world, root, TEXT_Y_OFFSET);
        TextDisplay[] configuredDisplay = new TextDisplay[1];
        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            configuredDisplay[0] = entity;
            configureSharedDisplay(entity);
            entity.setSeeThrough(true);
            entity.setText(text);
        });
        if (display == null) {
            removeQuietly(configuredDisplay[0]);
            return null;
        }
        spawnedDisplays.add(display);
        return display;
    }

    private ItemDisplay spawnItemDisplay(
        World world,
        BlockKey root,
        ItemMatch featuredItem,
        List<Display> spawnedDisplays
    ) {
        if (featuredItem == null) {
            return null;
        }
        Optional<ItemStack> stack = BukkitItemStackFactory.fromDescriptor(featuredItem.descriptor());
        if (stack.isEmpty()) {
            return null;
        }

        Location location = markerLocation(world, root, ITEM_Y_OFFSET);
        ItemDisplay[] configuredDisplay = new ItemDisplay[1];
        ItemDisplay display = world.spawn(location, ItemDisplay.class, entity -> {
            configuredDisplay[0] = entity;
            configureSharedDisplay(entity);
            entity.setItemStack(stack.get());
            entity.setTransformation(new Transformation(
                new Vector3f(0, 0, 0),
                new Quaternionf(),
                new Vector3f(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE),
                new Quaternionf()
            ));
        });
        if (display == null) {
            removeQuietly(configuredDisplay[0]);
            return null;
        }
        spawnedDisplays.add(display);
        return display;
    }

    private static Location markerLocation(World world, BlockKey root, double yOffset) {
        return new Location(world, root.x() + 0.5, root.y() + yOffset, root.z() + 0.5);
    }

    private static void configureSharedDisplay(Display entity) {
        entity.setVisibleByDefault(false);
        entity.setPersistent(false);
        entity.setGravity(false);
        entity.setBillboard(Display.Billboard.CENTER);
    }

    private static void removeQuietly(Display display) {
        if (display == null) {
            return;
        }
        try {
            display.remove();
        } catch (RuntimeException ignored) {
        }
    }

    private static void removeQuietly(List<Display> displays) {
        for (Display display : displays) {
            removeQuietly(display);
        }
    }

    private static final class CompositeMarker implements RenderedMarker {
        private final BlockKey root;
        private final TextDisplay textDisplay;
        private final ItemDisplay itemDisplay;
        private final UUID id;
        private boolean removed;

        private CompositeMarker(BlockKey root, TextDisplay textDisplay, ItemDisplay itemDisplay) {
            this.root = Objects.requireNonNull(root, "Root must not be null");
            this.textDisplay = Objects.requireNonNull(textDisplay, "Text display must not be null");
            this.itemDisplay = itemDisplay;
            this.id = Objects.requireNonNull(textDisplay.getUniqueId(), "Text display ID must not be null");
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public BlockKey root() {
            return root;
        }

        @Override
        public synchronized void remove() {
            if (removed) {
                return;
            }
            removeQuietly(textDisplay);
            removeQuietly(itemDisplay);
            removed = true;
        }
    }
}
