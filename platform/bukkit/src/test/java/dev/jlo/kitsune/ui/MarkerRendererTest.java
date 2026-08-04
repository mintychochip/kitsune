package dev.jlo.kitsune.ui;

import dev.jlo.kitsune.model.BlockKey;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
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

class MarkerRendererTest {
    @Test
    void configuresDefaultHiddenDisplayBeforeSpawnAndShowsOnlyTheOwner() {
        MarkerHarness harness = MarkerHarness.create(false, false);
        String text = "DIAMOND FOUND · 1 · 7m";

        RenderedMarker marker = harness.renderer().spawn(
            harness.owner(),
            harness.root(),
            text
        ).orElseThrow();

        assertEquals(harness.displayId(), marker.id());
        assertEquals(List.of(
            "spawn:start",
            "visible:false",
            "persistent:false",
            "gravity:false",
            "billboard:CENTER",
            "see-through:true",
            "text",
            "spawn:return",
            "show:owner"
        ), harness.calls());
        assertSame(text, harness.displayText());
        assertEquals(12.5, harness.spawnLocation().getX());
        assertEquals(66.25, harness.spawnLocation().getY());
        assertEquals(-7.5, harness.spawnLocation().getZ());
        assertEquals(1, harness.loadedChecks().get());
        assertEquals(0, harness.removeCalls().get());

        marker.remove();
        marker.remove();
        assertEquals(1, harness.removeCalls().get(), "rendered marker removal must be idempotent");
    }

    @Test
    void showFailureRemovesTheSpawnedDisplayAndReturnsNoMarker() {
        MarkerHarness harness = MarkerHarness.create(true, false);

        Optional<RenderedMarker> marker = harness.renderer().spawn(
            harness.owner(),
            harness.root(),
            "private"
        );

        assertTrue(marker.isEmpty());
        assertEquals(1, harness.removeCalls().get());
        assertTrue(harness.calls().contains("show:owner"));
    }

    @Test
    void spawnFailureReturnsNoMarkerAndNeverCallsShowEntity() {
        MarkerHarness harness = MarkerHarness.create(false, true);

        Optional<RenderedMarker> marker = harness.renderer().spawn(
            harness.owner(),
            harness.root(),
            "private"
        );

        assertTrue(marker.isEmpty());
        assertFalse(harness.calls().contains("show:owner"));
        assertEquals(0, harness.removeCalls().get());
    }

    private record MarkerHarness(
        MarkerRenderer renderer,
        Player owner,
        BlockKey root,
        UUID displayId,
        List<String> mutableCalls,
        AtomicReference<String> text,
        AtomicReference<Location> location,
        AtomicInteger loadedChecks,
        AtomicInteger removeCalls
    ) {
        private static MarkerHarness create(boolean failShow, boolean failSpawn) {
            UUID worldId = UUID.nameUUIDFromBytes("marker-world".getBytes(StandardCharsets.UTF_8));
            UUID playerId = UUID.nameUUIDFromBytes("marker-owner".getBytes(StandardCharsets.UTF_8));
            UUID displayId = UUID.nameUUIDFromBytes("marker-display".getBytes(StandardCharsets.UTF_8));
            List<String> calls = new ArrayList<>();
            AtomicReference<String> text = new AtomicReference<>();
            AtomicReference<Location> location = new AtomicReference<>();
            AtomicInteger loadedChecks = new AtomicInteger();
            AtomicInteger removeCalls = new AtomicInteger();
            AtomicReference<World> worldRef = new AtomicReference<>();

            TextDisplay display = proxy(TextDisplay.class, (method, arguments) -> {
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
                        calls.add("remove");
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
                        calls.add("spawn:start");
                        if (failSpawn) {
                            throw new IllegalStateException("forced spawn failure");
                        }
                        location.set((Location) arguments[0]);
                        assertSame(TextDisplay.class, arguments[1]);
                        @SuppressWarnings("unchecked")
                        Consumer<TextDisplay> configurator = (Consumer<TextDisplay>) arguments[2];
                        configurator.accept(display);
                        calls.add("spawn:return");
                        return display;
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
                        calls.add("show:owner");
                        assertSame(display, arguments[1]);
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
                location,
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

        private Location spawnLocation() {
            return location.get();
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
