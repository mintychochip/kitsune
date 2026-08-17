package dev.jlo.kitsune.ui;

import dev.jlo.kitsune.model.BlockKey;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Spawns and tracks world text-display markers for search results.
 */
public final class MarkerRenderer {
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
     * Spawns a text display above a root and reveals it to its owner.
     *
     * @param owner player the marker is shown to
     * @param root root block the marker labels
     * @param text text displayed by the marker
     * @return the spawned marker, or empty when the root is unreachable
     */
    public Optional<RenderedMarker> spawn(Player owner, BlockKey root, String text) {
        Objects.requireNonNull(owner, "Owner must not be null");
        Objects.requireNonNull(root, "Root must not be null");
        Objects.requireNonNull(text, "Text must not be null");
        TextDisplay[] configuredDisplay = new TextDisplay[1];
        try {
            if (!owner.isOnline() || !owner.getWorld().getUID().equals(root.worldId())) return Optional.empty();
            World world = server.getWorld(root.worldId());
            if (world == null || !world.getUID().equals(root.worldId())) return Optional.empty();
            int chunkX = Math.floorDiv(root.x(), 16);
            int chunkZ = Math.floorDiv(root.z(), 16);
            if (!world.isChunkLoaded(chunkX, chunkZ)) return Optional.empty();
            Location location = new Location(world, root.x() + 0.5, root.y() + 2.25, root.z() + 0.5);
            TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
                configuredDisplay[0] = entity;
                entity.setVisibleByDefault(false);
                entity.setPersistent(false);
                entity.setGravity(false);
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setSeeThrough(true);
                entity.setText(text);
            });
            if (display == null) {
                removeQuietly(configuredDisplay[0]);
                return Optional.empty();
            }
            configuredDisplay[0] = display;
            owner.showEntity(plugin, display);
            return Optional.of(new DisplayMarker(root, display));
        } catch (RuntimeException failure) {
            removeQuietly(configuredDisplay[0]);
            return Optional.empty();
        }
    }

    private static void removeQuietly(TextDisplay display) {
        if (display == null) return;
        try { display.remove(); } catch (RuntimeException ignored) { }
    }

    private static final class DisplayMarker implements RenderedMarker {
        private final TextDisplay display;
        private final UUID id;
        private final BlockKey root;
        private boolean removed;

        private DisplayMarker(BlockKey root, TextDisplay display) {
            this.root = Objects.requireNonNull(root, "Root must not be null");
            this.display = Objects.requireNonNull(display, "Display must not be null");
            this.id = Objects.requireNonNull(display.getUniqueId(), "Display ID must not be null");
        }

        @Override public UUID id() { return id; }
        @Override public BlockKey root() { return root; }

        @Override
        public synchronized void remove() {
            if (removed) return;
            display.remove();
            removed = true;
        }
    }
}
