package dev.jlo.kitsune.protection;

import dev.jlo.kitsune.api.protection.AccessDecision;
import dev.jlo.kitsune.api.protection.BlockAccessProvider;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectionRegistryTest {

    @Test
    void denyWinsRegardlessOfRegistrationOrder() {
        Player player = fakePlayer("deny-order-player");
        Block block = fakeBlock(UUID.randomUUID(), 1, 64, 2);

        for (int round = 0; round < 2; round++) {
            List<String> callOrder = new ArrayList<>();
            RecordingProvider first = recordingProvider(round == 0 ? AccessDecision.DENY : AccessDecision.ALLOW, "first", callOrder);
            RecordingProvider second = recordingProvider(round == 0 ? AccessDecision.ALLOW : AccessDecision.DENY, "second", callOrder);

            ProtectionRegistry registry = new ProtectionRegistry(List.of(first, second));
            assertFalse(registry.canAccess(player, block));

            if (round == 0) {
                assertEquals(List.of("first"), callOrder, "first provider deny -> stop before second");
            } else {
                assertEquals(List.of("first", "second"), callOrder, "allow before deny still ends denied");
            }

            assertSame(player, first.player());
            assertSame(block, first.block());
            assertSame(round == 1 ? player : null, second.player());
            assertSame(round == 1 ? block : null, second.block());
        }
    }

    @Test
    void allowPathsRemainUnblockedWhenNoProviderDenies() {
        Player player = fakePlayer("allow-player");
        Block block = fakeBlock(UUID.randomUUID(), 2, 64, 3);

        for (int round = 0; round < 2; round++) {
            List<String> callOrder = new ArrayList<>();
            RecordingProvider first = recordingProvider(AccessDecision.ALLOW, "first", callOrder);
            RecordingProvider second = recordingProvider(AccessDecision.NOT_APPLICABLE, "second", callOrder);
            RecordingProvider third = recordingProvider(AccessDecision.ALLOW, "third", callOrder);

            ProtectionRegistry registry = new ProtectionRegistry(round == 0
                ? List.of(first, second, third)
                : List.of(third, second, first)
            );
            assertTrue(registry.canAccess(player, block), "all non-deny decisions must allow");
            assertEquals(List.of(round == 0 ? "first" : "third", "second", round == 0 ? "third" : "first"), callOrder);
            assertSame(player, first.player());
            assertSame(player, second.player());
            assertSame(player, third.player());
            assertSame(block, first.block());
            assertSame(block, second.block());
            assertSame(block, third.block());
        }
    }

    @Test
    void allNotApplicableDecisionsAllowOrdinaryRoots() {
        Player player = fakePlayer("all-not-applicable-player");
        Block block = fakeBlock(UUID.randomUUID(), 3, 64, 4);

        List<String> callOrder = new ArrayList<>();
        RecordingProvider first = recordingProvider(AccessDecision.NOT_APPLICABLE, "first", callOrder);
        RecordingProvider second = recordingProvider(AccessDecision.NOT_APPLICABLE, "second", callOrder);

        ProtectionRegistry registry = new ProtectionRegistry(List.of(first, second));

        assertTrue(registry.canAccess(player, block));
        assertEquals(List.of("first", "second"), callOrder);
        assertSame(player, first.player());
        assertSame(player, second.player());
        assertSame(block, first.block());
        assertSame(block, second.block());
    }

    @Test
    void providerExceptionFailsClosedForThatRoot() {
        Player player = fakePlayer("exception-player");
        Block block = fakeBlock(UUID.randomUUID(), 4, 64, 5);

        List<String> callOrder = new ArrayList<>();
        RecordingProvider failing = RecordingProvider.failing("failing", callOrder,
            new IllegalStateException("provider failed"));
        RecordingProvider allow = recordingProvider(AccessDecision.ALLOW, "allow", callOrder);

        ProtectionRegistry registry = new ProtectionRegistry(List.of(failing, allow));

        assertFalse(registry.canAccess(player, block));
        assertEquals(List.of("failing"), callOrder);
        assertSame(player, failing.player());
        assertSame(block, failing.block());
        assertEquals(0, allow.calls());
    }

    @Test
    void nullDecisionFailsClosedForThatRoot() {
        Player player = fakePlayer("null-decicion-player");
        Block block = fakeBlock(UUID.randomUUID(), 5, 64, 6);

        List<String> callOrder = new ArrayList<>();
        RecordingProvider nullDecision = RecordingProvider.of("null", callOrder, null);
        RecordingProvider deny = recordingProvider(AccessDecision.DENY, "deny", callOrder);

        ProtectionRegistry registry = new ProtectionRegistry(List.of(nullDecision, deny));

        assertFalse(registry.canAccess(player, block));
        assertEquals(List.of("null"), callOrder);
        assertSame(player, nullDecision.player());
        assertSame(block, nullDecision.block());
        assertEquals(0, deny.calls());
    }

    @Test
    void injectedProvidersAreCalledWithSuppliedPlayerAndBlockAndStopAfterDeny() {
        Player player = fakePlayer("short-circuit-player");
        Block block = fakeBlock(UUID.randomUUID(), 6, 64, 7);

        List<String> callOrder = new ArrayList<>();
        RecordingProvider before = recordingProvider(AccessDecision.ALLOW, "before", callOrder);
        RecordingProvider deny = recordingProvider(AccessDecision.DENY, "deny", callOrder);
        RecordingProvider after = recordingProvider(AccessDecision.ALLOW, "after", callOrder);

        ProtectionRegistry registry = new ProtectionRegistry(List.of(before, deny, after));

        assertFalse(registry.canAccess(player, block));
        assertEquals(List.of("before", "deny"), callOrder);

        assertSame(player, before.player());
        assertSame(block, before.block());
        assertSame(player, deny.player());
        assertSame(block, deny.block());
        assertEquals(0, after.calls());
    }

    @Test
    void productionRegistryReadsCurrentServiceRegistrationsForEveryCheck() {
        AtomicReference<List<RegisteredServiceProvider<BlockAccessProvider>>> registrations =
            new AtomicReference<>(List.of());
        ServicesManager services = proxy(ServicesManager.class, (method, arguments) -> switch (method.getName()) {
            case "getRegistrations" -> registrations.get();
            default -> defaultValue(method.getReturnType());
        });
        Plugin owner = proxy(Plugin.class, (method, arguments) -> defaultValue(method.getReturnType()));
        ProtectionRegistry registry = new ProtectionRegistry(
            services,
            Logger.getLogger("kitsune-protection-test")
        );
        Player player = fakePlayer("dynamic-provider-player");
        Block block = fakeBlock(UUID.randomUUID(), 7, 64, 8);
        List<String> callOrder = new ArrayList<>();
        RecordingProvider allow = recordingProvider(AccessDecision.ALLOW, "allow", callOrder);
        RecordingProvider deny = recordingProvider(AccessDecision.DENY, "deny", callOrder);

        registrations.set(List.of(new RegisteredServiceProvider<>(
            BlockAccessProvider.class,
            allow,
            ServicePriority.Normal,
            owner
        )));
        assertTrue(registry.canAccess(player, block));

        registrations.set(List.of(new RegisteredServiceProvider<>(
            BlockAccessProvider.class,
            deny,
            ServicePriority.Normal,
            owner
        )));
        assertFalse(registry.canAccess(player, block));
        assertEquals(List.of("allow", "deny"), callOrder);
    }

    private static RecordingProvider recordingProvider(AccessDecision decision, String label,
        List<String> callOrder) {
        return new RecordingProvider(label, decision, null, callOrder);
    }

    private static Player fakePlayer(String name) {
        UUID playerId = UUID.nameUUIDFromBytes(("player:" + name).getBytes());
        return proxy(Player.class, (method, arguments) -> switch (method.getName()) {
            case "getUniqueId" -> playerId;
            case "getName" -> name;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Block fakeBlock(UUID worldId, int x, int y, int z) {
        World world = proxy(World.class, (method, arguments) -> switch (method.getName()) {
            case "getUID" -> worldId;
            case "getName" -> "test-world";
            default -> defaultValue(method.getReturnType());
        });
        return proxy(Block.class, (method, arguments) -> switch (method.getName()) {
            case "getWorld" -> world;
            case "getX" -> x;
            case "getY" -> y;
            case "getZ" -> z;
            default -> defaultValue(method.getReturnType());
        });
    }

    private interface Invocation {
        Object invoke(Method method, Object[] arguments) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
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

    private static final class RecordingProvider implements BlockAccessProvider {
        private final String label;
        private final AccessDecision decision;
        private final RuntimeException failure;
        private final List<String> callOrder;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Player> observedPlayer = new AtomicReference<>();
        private final AtomicReference<Block> observedBlock = new AtomicReference<>();

        private static RecordingProvider of(String label, List<String> callOrder, AccessDecision decision) {
            return new RecordingProvider(label, decision, null, callOrder);
        }

        private static RecordingProvider failing(String label, List<String> callOrder, RuntimeException failure) {
            return new RecordingProvider(label, null, Objects.requireNonNull(failure), callOrder);
        }

        private RecordingProvider(String label,
                                 AccessDecision decision,
                                 RuntimeException failure,
                                 List<String> callOrder) {
            this.label = label;
            this.decision = decision;
            this.failure = failure;
            this.callOrder = callOrder;
        }

        @Override
        public AccessDecision canAccess(Player player, Block block) {
            calls.incrementAndGet();
            observedPlayer.set(player);
            observedBlock.set(block);
            callOrder.add(label);

            if (failure != null) {
                throw failure;
            }

            return decision;
        }

        private int calls() {
            return calls.get();
        }

        private Player player() {
            return observedPlayer.get();
        }

        private Block block() {
            return observedBlock.get();
        }
    }
}
