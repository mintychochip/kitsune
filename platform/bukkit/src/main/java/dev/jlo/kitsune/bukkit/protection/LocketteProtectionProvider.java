package dev.jlo.kitsune.bukkit.protection;

import dev.jlo.kitsune.api.protection.AccessContext;
import dev.jlo.kitsune.api.protection.AccessDecision;
import dev.jlo.kitsune.api.protection.BlockAccessProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Enforces Lockette container protection access via reflective access to the Lockette plugin API.
 */
public final class LocketteProtectionProvider implements BlockAccessProvider {
    private final LocketteAccess access;

    /**
     * Creates a provider that reflects into the live Lockette plugin API.
     */
    public LocketteProtectionProvider() {
        this(new ReflectiveLocketteAccess());
    }

    LocketteProtectionProvider(LocketteAccess access) {
        this.access = Objects.requireNonNull(access, "Lockette access must not be null");
    }

    /**
     * Resolves the player and block implied by the context and checks Lockette access.
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
     * Checks whether a player may access a specific block protected by Lockette.
     *
     * @param player player requesting access
     * @param block block to inspect
     * @return the resulting access decision
     */
    public AccessDecision canAccess(Player player, Block block) {
        Objects.requireNonNull(player, "Player must not be null");
        Objects.requireNonNull(block, "Block must not be null");
        try {
            return access.canAccessContainer(player, block.getLocation())
                ? AccessDecision.ALLOW
                : AccessDecision.DENY;
        } catch (RuntimeException | LinkageError failure) {
            return AccessDecision.DENY;
        }
    }

    interface LocketteAccess {
        boolean canAccessContainer(Player player, Location location);
    }

    private static final class ReflectiveLocketteAccess implements LocketteAccess {
        private final Method canAccessContainer;

        private ReflectiveLocketteAccess() {
            try {
                Class<?> locketteType = Class.forName("org.yi.acru.bukkit.Lockette.Lockette");
                canAccessContainer = locketteType.getMethod("canAccessContainer", Player.class, Location.class);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Lockette is not available", failure);
            }
        }

        @Override
        public boolean canAccessContainer(Player player, Location location) {
            return Boolean.TRUE.equals(invoke(canAccessContainer, null, player, location));
        }

        private static Object invoke(Method method, Object target, Object... arguments) {
            try {
                return method.invoke(target, arguments);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Lockette method is inaccessible", failure);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("Lockette call failed", cause);
            }
        }
    }
}
