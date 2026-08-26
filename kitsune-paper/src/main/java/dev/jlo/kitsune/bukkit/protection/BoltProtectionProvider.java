package dev.jlo.kitsune.bukkit.protection;

import dev.jlo.kitsune.api.protection.AccessContext;
import dev.jlo.kitsune.api.protection.AccessDecision;
import dev.jlo.kitsune.api.protection.BlockAccessProvider;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.popcraft.bolt.BoltAPI;

import java.util.Objects;

/**
 * Enforces Bolt block protection access through Bolt's public API.
 */
public final class BoltProtectionProvider implements BlockAccessProvider {
    private final BoltAccess access;

    /**
     * Creates a provider backed by the supplied live Bolt API.
     *
     * @param bolt Bolt API instance
     */
    public BoltProtectionProvider(BoltAPI bolt) {
        this(new ApiBoltAccess(Objects.requireNonNull(bolt, "Bolt API must not be null")));
    }

    BoltProtectionProvider(BoltAccess access) {
        this.access = Objects.requireNonNull(access, "Bolt access must not be null");
    }

    /**
     * Resolves the player and block implied by the context and checks Bolt access.
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
     * Checks whether a player may access a specific block protected by Bolt.
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
            return access.canAccess(block, player) ? AccessDecision.ALLOW : AccessDecision.DENY;
        } catch (RuntimeException | LinkageError failure) {
            return AccessDecision.DENY;
        }
    }

    interface BoltAccess {
        Object findProtection(Block block);

        boolean canAccess(Block block, Player player);
    }

    private record ApiBoltAccess(BoltAPI bolt) implements BoltAccess {
        @Override
        public Object findProtection(Block block) {
            return bolt.findProtection(block);
        }

        @Override
        public boolean canAccess(Block block, Player player) {
            return bolt.canAccess(block, player, "bolt.access");
        }
    }
}
