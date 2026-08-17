package dev.jlo.kitsune.protection;

import dev.jlo.kitsune.api.protection.AccessContext;
import dev.jlo.kitsune.api.protection.AccessDecision;
import dev.jlo.kitsune.api.protection.BlockAccessProvider;
import dev.jlo.kitsune.model.BlockKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Enforces LWC chest protection access via reflective access to the LWC plugin API.
 */
public final class LwcProtectionProvider implements BlockAccessProvider {
    private final LwcAccess access;

    /**
     * Creates a provider that reflects into the live LWC plugin instance.
     */
    public LwcProtectionProvider() {
        this(new ReflectiveLwcAccess());
    }

    LwcProtectionProvider(LwcAccess access) {
        this.access = Objects.requireNonNull(access, "LWC access must not be null");
    }

    /**
     * Resolves the player and block implied by the context and checks LWC access.
     *
     * @param context access request context
     * @return the resulting access decision
     */
    @Override
    public AccessDecision canAccess(AccessContext context) {
        Objects.requireNonNull(context, "Access context must not be null");
        World world = Bukkit.getWorld(context.block().worldId());
        Player player = Bukkit.getPlayer(context.playerId());
        if (world == null || player == null) return AccessDecision.DENY;
        Block block = world.getBlockAt(context.block().x(), context.block().y(), context.block().z());
        return canAccess(player, block);
    }

    /**
     * Checks whether a player may access a specific block protected by LWC.
     *
     * @param player player requesting access
     * @param block block to inspect
     * @return the resulting access decision
     */
    public AccessDecision canAccess(Player player, Block block) {
        Objects.requireNonNull(player, "Player must not be null");
        Objects.requireNonNull(block, "Block must not be null");
        try {
            Object protection = access.findProtection(block);
            if (protection == null) return AccessDecision.NOT_APPLICABLE;
            return access.canAccess(player, protection) ? AccessDecision.ALLOW : AccessDecision.DENY;
        } catch (RuntimeException | LinkageError failure) {
            return AccessDecision.DENY;
        }
    }

    interface LwcAccess {
        Object findProtection(Block block);
        boolean canAccess(Player player, Object protection);
    }

    private static final class ReflectiveLwcAccess implements LwcAccess {
        private final Object lwc;
        private final Method findProtection;
        private final Method canAccessProtection;

        private ReflectiveLwcAccess() {
            try {
                Class<?> lwcType = Class.forName("com.griefcraft.lwc.LWC");
                lwc = lwcType.getMethod("getInstance").invoke(null);
                findProtection = lwcType.getMethod("findProtection", Block.class);
                canAccessProtection = findProtection.getDeclaringClass().getMethod(
                    "canAccessProtection", Player.class, Class.forName("com.griefcraft.model.Protection"));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("LWC is not available", failure);
            }
        }

        @Override
        public Object findProtection(Block block) {
            return invoke(findProtection, lwc, block);
        }

        @Override
        public boolean canAccess(Player player, Object protection) {
            return Boolean.TRUE.equals(invoke(canAccessProtection, lwc, player, protection));
        }

        private static Object invoke(Method method, Object target, Object... arguments) {
            try {
                return method.invoke(target, arguments);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("LWC method is inaccessible", failure);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("LWC call failed", cause);
            }
        }
    }
}
