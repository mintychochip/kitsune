package dev.jlo.kitsune.ui;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;
import dev.jlo.kitsune.search.ItemMatch;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link MarkerRenderer} spawns hidden displays, shows them only
 * to the owner, and handles show or spawn failures.
 */
class MarkerRendererTest {
    /**
     * The display is configured hidden and non-persistent before spawn, then
     * shown only to the owner; removing the marker is idempotent.
     */
    @Test
    void configuresDefaultHiddenDisplayBeforeSpawnAndShowsOnlyTheOwner() {
        MarkerHarness harness = MarkerHarness.create(false, false);
        String text = "DIAMOND FOUND · 1 · 7m";

        RenderedMarker marker = harness.renderer().spawn(
            harness.owner(),
            harness.root(),
            text,
            null
        ).orElseThrow();

        assertEquals(harness.displayId(), marker.id());
        assertEquals(List.of(
            "spawn:text:start",
            "visible:false",
            "persistent:false",
            "gravity:false",
            "billboard:CENTER",
            "see-through:true",
            "text",
            "spawn:text:return",
            "show:text"
        ), harness.calls());
        assertSame(text, harness.displayText());
        assertEquals(12.5, harness.textSpawnLocation().getX());
        assertEquals(66.0, harness.textSpawnLocation().getY());
        assertEquals(-7.5, harness.textSpawnLocation().getZ());
        assertEquals(1, harness.loadedChecks().get());
        assertEquals(0, harness.removeCalls().get());

        marker.remove();
        marker.remove();
        assertEquals(1, harness.removeCalls().get(), "rendered marker removal must be idempotent");
    }

    /**
     * A featured item spawns a private item display beneath the caption and
     * removing the marker clears both entities.
     */
    @Test
    void spawnsFeaturedItemDisplayAndRemovesBothEntities() {
        MarkerHarness harness = MarkerHarness.create(false, false);
        ItemMatch featured = new ItemMatch(
            ItemDescriptor.builder()
                .materialKey("minecraft:diamond_pickaxe")
                .amount(1)
                .build(),
            new ItemPath(List.of(new ItemPathStep("minecraft:barrel", 4))),
            0.91,
            1
        );

        RenderedMarker marker = harness.renderer().spawn(
            harness.owner(),
            harness.root(),
            "DIAMOND PICKAXE FOUND · 1 · 7m",
            featured
        ).orElseThrow();

        assertEquals(List.of(
            "spawn:text:start",
            "visible:false",
            "persistent:false",
            "gravity:false",
            "billboard:CENTER",
            "see-through:true",
            "text",
            "spawn:text:return",
            "spawn:item:start",
            "visible:false",
            "persistent:false",
            "gravity:false",
            "billboard:CENTER",
            "item-stack",
            "transformation",
            "spawn:item:return",
            "show:text",
            "show:item"
        ), harness.calls());
        assertEquals(Material.DIAMOND_PICKAXE, harness.spawnedItemStack().getType());

        marker.remove();
        assertEquals(2, harness.removeCalls().get());
    }

    /**
     * A failure while showing the display removes the spawned display and
     * returns no marker.
     */
    @Test
    void showFailureRemovesTheSpawnedDisplayAndReturnsNoMarker() {
        MarkerHarness harness = MarkerHarness.create(true, false);

        Optional<RenderedMarker> marker = harness.renderer().spawn(
            harness.owner(),
            harness.root(),
            "private",
            null
        );

        assertTrue(marker.isEmpty());
        assertEquals(1, harness.removeCalls().get());
        assertTrue(harness.calls().contains("show:text"));
    }

    /**
     * A failure while spawning returns no marker and never calls show entity.
     */
    @Test
    void spawnFailureReturnsNoMarkerAndNeverCallsShowEntity() {
        MarkerHarness harness = MarkerHarness.create(false, true);

        Optional<RenderedMarker> marker = harness.renderer().spawn(
            harness.owner(),
            harness.root(),
            "private",
            null
        );

        assertTrue(marker.isEmpty());
        assertFalse(harness.calls().contains("show:text"));
        assertEquals(0, harness.removeCalls().get());
    }

    /**
     * A fully-faked rendering harness that records display configuration
     * calls, spawned text and locations, chunk-loaded checks, and removals.
     */
    private record MarkerHarness(
        MarkerRenderer renderer,
        Player owner,
        BlockKey root,
        UUID displayId,
        List<String> mutableCalls,
        AtomicReference<String> text,
        AtomicReference<Location> textLocation,
        AtomicReference<Location> itemLocation,
        AtomicReference<ItemStack> itemStack,
        AtomicInteger loadedChecks,
        AtomicInteger removeCalls
    ) {
        private static MarkerHarness create(boolean failShow, boolean failSpawn) {
            UUID worldId = UUID.nameUUIDFromBytes("marker-world".getBytes(StandardCharsets.UTF_8));
            UUID playerId = UUID.nameUUIDFromBytes("marker-owner".getBytes(StandardCharsets.UTF_8));
            UUID displayId = UUID.nameUUIDFromBytes("marker-display".getBytes(StandardCharsets.UTF_8));
            UUID itemDisplayId = UUID.nameUUIDFromBytes("marker-item-display".getBytes(StandardCharsets.UTF_8));
            List<String> calls = new ArrayList<>();
            AtomicReference<String> text = new AtomicReference<>();
            AtomicReference<Location> textLocation = new AtomicReference<>();
            AtomicReference<Location> itemLocation = new AtomicReference<>();
            AtomicReference<ItemStack> itemStack = new AtomicReference<>();
            AtomicInteger loadedChecks = new AtomicInteger();
            AtomicInteger removeCalls = new AtomicInteger();
            AtomicReference<World> worldRef = new AtomicReference<>();

            TextDisplay textDisplay = proxy(TextDisplay.class, (method, arguments) -> {
                switch (method.getName()) {
                    case "getUniqueId":
                        return displayId;
                    case "getWorld":
                        return worldRef.get();
                    case "setVisibleByDefault":
                        calls.add("visible:" + arguments[0]);
                        return null;
                    case "setPersistent":
                        calls.add("persistent:" + arguments[0]);
                        return null;
                    case "setGravity":
                        calls.add("gravity:" + arguments[0]);
                        return null;
                    case "setBillboard":
                        calls.add("billboard:" + arguments[0]);
                        assertEquals(Display.Billboard.CENTER, arguments[0]);
                        return null;
                    case "setSeeThrough":
                        calls.add("see-through:" + arguments[0]);
                        return null;
                    case "setText":
                        if (arguments != null && arguments.length == 1) {
                            calls.add("text");
                            text.set((String) arguments[0]);
                            return null;
                        }
                        return text.get();
                    case "remove":
                        removeCalls.incrementAndGet();
                        calls.add("remove:text");
                        return null;
                    default:
                        return defaultValue(method.getReturnType());
                }
            });

            ItemDisplay itemDisplay = proxy(ItemDisplay.class, (method, arguments) -> {
                switch (method.getName()) {
                    case "getUniqueId":
                        return itemDisplayId;
                    case "getWorld":
                        return worldRef.get();
                    case "setVisibleByDefault":
                        calls.add("visible:" + arguments[0]);
                        return null;
                    case "setPersistent":
                        calls.add("persistent:" + arguments[0]);
                        return null;
                    case "setGravity":
                        calls.add("gravity:" + arguments[0]);
                        return null;
                    case "setBillboard":
                        calls.add("billboard:" + arguments[0]);
                        assertEquals(Display.Billboard.CENTER, arguments[0]);
                        return null;
                    case "setItemStack":
                        calls.add("item-stack");
                        itemStack.set((ItemStack) arguments[0]);
                        return null;
                    case "setTransformation":
                        calls.add("transformation");
                        assertTrue(arguments[0] instanceof Transformation);
                        return null;
                    case "remove":
                        removeCalls.incrementAndGet();
                        calls.add("remove:item");
                        return null;
                    default:
                        return defaultValue(method.getReturnType());
                }
            });

            World world = proxy(World.class, (method, arguments) -> {
                switch (method.getName()) {
                    case "getUID":
                        return worldId;
                    case "isChunkLoaded":
                        loadedChecks.incrementAndGet();
                        return true;
                    case "spawn":
                        if (failSpawn) {
                            throw new IllegalStateException("forced spawn failure");
                        }
                        Location location = (Location) arguments[0];
                        Class<?> entityClass = (Class<?>) arguments[1];
                        if (entityClass == TextDisplay.class) {
                            calls.add("spawn:text:start");
                            textLocation.set(location);
                            @SuppressWarnings("unchecked")
                            Consumer<TextDisplay> configurator = (Consumer<TextDisplay>) arguments[2];
                            configurator.accept(textDisplay);
                            calls.add("spawn:text:return");
                            return textDisplay;
                        }
                        if (entityClass == ItemDisplay.class) {
                            calls.add("spawn:item:start");
                            itemLocation.set(location);
                            @SuppressWarnings("unchecked")
                            Consumer<ItemDisplay> configurator = (Consumer<ItemDisplay>) arguments[2];
                            configurator.accept(itemDisplay);
                            calls.add("spawn:item:return");
                            return itemDisplay;
                        }
                        throw new AssertionError("Unexpected entity class: " + entityClass);
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
            worldRef.set(world);

            Player owner = proxy(Player.class, (method, arguments) -> {
                switch (method.getName()) {
                    case "getUniqueId":
                        return playerId;
                    case "getWorld":
                        return world;
                    case "isOnline":
                        return true;
                    case "showEntity":
                        if (arguments[1] == textDisplay) {
                            calls.add("show:text");
                        } else if (arguments[1] == itemDisplay) {
                            calls.add("show:item");
                        } else {
                            throw new AssertionError("Unexpected display for showEntity");
                        }
                        if (failShow) {
                            throw new IllegalStateException("forced visibility failure");
                        }
                        return null;
                    default:
                        return defaultValue(method.getReturnType());
                }
            });

            Server server = proxy(Server.class, (method, arguments) -> switch (method.getName()) {
                case "getWorld" -> world;
                default -> defaultValue(method.getReturnType());
            });
            Plugin plugin = proxy(Plugin.class, (method, arguments) -> switch (method.getName()) {
                case "getServer" -> server;
                default -> defaultValue(method.getReturnType());
            });

            return new MarkerHarness(
                new MarkerRenderer(plugin),
                owner,
                new BlockKey(worldId, 12, 64, -8),
                displayId,
                calls,
                text,
                textLocation,
                itemLocation,
                itemStack,
                loadedChecks,
                removeCalls
            );
        }

        private List<String> calls() {
            return List.copyOf(mutableCalls);
        }

        private String displayText() {
            return text.get();
        }

        private Location textSpawnLocation() {
            return textLocation.get();
        }

        private Location itemSpawnLocation() {
            return itemLocation.get();
        }

        private ItemStack spawnedItemStack() {
            return itemStack.get();
        }

    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Method method, Object[] arguments) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (_ignored, method, arguments) -> invocation.invoke(method, arguments)
        ));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Void.class || type == void.class) return null;
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0;
        throw new AssertionError("Unknown primitive type: " + type);
    }
}
