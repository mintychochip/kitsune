package dev.jlo.kitsune.bukkit.protection;

import dev.jlo.kitsune.api.protection.AccessDecision;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the access decisions issued by {@link LocketteProtectionProvider} across
 * accessible, inaccessible, and failing Lockette lookups.
 */
class LocketteProtectionProviderTest {
    private static final Player PLAYER = proxy(Player.class);
    private static final Block BLOCK = blockProxy();

    /**
     * A container the player may access results in {@code ALLOW}.
     */
    @Test
    void accessibleContainerAllows() {
        var provider = new LocketteProtectionProvider(
            new FakeLocketteAccess(true)
        );

        assertEquals(
            AccessDecision.ALLOW,
            provider.canAccess(PLAYER, BLOCK)
        );
    }

    /**
     * A container the player may not access results in {@code DENY}.
     */
    @Test
    void inaccessibleContainerDenies() {
        var provider = new LocketteProtectionProvider(
            new FakeLocketteAccess(false)
        );

        assertEquals(
            AccessDecision.DENY,
            provider.canAccess(PLAYER, BLOCK)
        );
    }

    /**
     * A failing Lockette lookup results in {@code DENY}.
     */
    @Test
    void accessCheckFailureDenies() {
        var provider = new LocketteProtectionProvider(
            new ThrowingLocketteAccess()
        );

        assertEquals(
            AccessDecision.DENY,
            provider.canAccess(PLAYER, BLOCK)
        );
    }

    private record FakeLocketteAccess(
        boolean allowed
    ) implements LocketteProtectionProvider.LocketteAccess {
        @Override
        public boolean canAccessContainer(Player player, Location location) {
            return allowed;
        }
    }

    private record ThrowingLocketteAccess() implements LocketteProtectionProvider.LocketteAccess {
        @Override
        public boolean canAccessContainer(Player player, Location location) {
            throw new IllegalStateException("forced access failure");
        }
    }

    private static Block blockProxy() {
        return (Block) Proxy.newProxyInstance(
            Block.class.getClassLoader(),
            new Class<?>[]{Block.class},
            (_proxy, method, _args) -> {
                if (method.getName().equals("getLocation")) {
                    return null;
                }
                if (method.getName().equals("toString")) {
                    return "Block";
                }
                Class<?> returnType = method.getReturnType();
                if (!returnType.isPrimitive()) {
                    return null;
                }
                if (returnType == boolean.class) {
                    return false;
                }
                if (returnType == char.class) {
                    return '\0';
                }
                return 0;
            }
        );
    }

    private static <T> T proxy(Class<T> type) {
        Object value = Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (_proxy, method, _args) -> {
                if (method.getName().equals("toString")) {
                    return type.getSimpleName();
                }
                Class<?> returnType = method.getReturnType();
                if (!returnType.isPrimitive()) {
                    return null;
                }
                if (returnType == boolean.class) {
                    return false;
                }
                if (returnType == char.class) {
                    return '\0';
                }
                return 0;
            }
        );
        return type.cast(value);
    }
}
