package dev.jlo.kitsune.protection;

import dev.jlo.kitsune.api.protection.AccessDecision;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the access decisions issued by {@link LwcProtectionProvider} across
 * absent, accessible, inaccessible, and failing LWC lookups.
 */
class LwcProtectionProviderContractTest {
    private static final Player PLAYER = proxy(Player.class);
    private static final Block BLOCK = proxy(Block.class);
    private static final Object PROTECTION = new Object();

    /**
     * Absence of any protection is reported as not applicable.
     */
    @Test
    void noProtectionIsNotApplicable() {
        var provider = new LwcProtectionProvider(
            new FakeLwcAccess(null, true)
        );

        assertEquals(
            AccessDecision.NOT_APPLICABLE,
            provider.canAccess(PLAYER, BLOCK)
        );
    }

    /**
     * A protection the player may access results in {@code ALLOW}.
     */
    @Test
    void accessibleProtectionAllows() {
        var provider = new LwcProtectionProvider(
            new FakeLwcAccess(PROTECTION, true)
        );

        assertEquals(
            AccessDecision.ALLOW,
            provider.canAccess(PLAYER, BLOCK)
        );
    }

    /**
     * A protection the player may not access results in {@code DENY}.
     */
    @Test
    void inaccessibleProtectionDenies() {
        var provider = new LwcProtectionProvider(
            new FakeLwcAccess(PROTECTION, false)
        );

        assertEquals(
            AccessDecision.DENY,
            provider.canAccess(PLAYER, BLOCK)
        );
    }

    /**
     * A failing protection lookup results in {@code DENY}.
     */
    @Test
    void protectionLookupFailureDenies() {
        var provider = new LwcProtectionProvider(
            new ThrowingLwcAccess(true)
        );

        assertEquals(
            AccessDecision.DENY,
            provider.canAccess(PLAYER, BLOCK)
        );
    }

    /**
     * A failing access check results in {@code DENY}.
     */
    @Test
    void accessCheckFailureDenies() {
        var provider = new LwcProtectionProvider(
            new ThrowingLwcAccess(false)
        );

        assertEquals(
            AccessDecision.DENY,
            provider.canAccess(PLAYER, BLOCK)
        );
    }

    private record FakeLwcAccess(
        Object protection,
        boolean allowed
    ) implements LwcProtectionProvider.LwcAccess {
        @Override
        public Object findProtection(Block block) {
            return protection;
        }

        @Override
        public boolean canAccess(Player player, Object protection) {
            return allowed;
        }
    }

    private record ThrowingLwcAccess(
        boolean throwDuringLookup
    ) implements LwcProtectionProvider.LwcAccess {
        @Override
        public Object findProtection(Block block) {
            if (throwDuringLookup) {
                throw new IllegalStateException("forced lookup failure");
            }
            return PROTECTION;
        }

        @Override
        public boolean canAccess(Player player, Object protection) {
            throw new IllegalStateException("forced access failure");
        }
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
