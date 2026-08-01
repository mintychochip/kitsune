package dev.jlo.kitsune.protection;

import com.griefcraft.lwc.LWC;
import com.griefcraft.model.Protection;
import dev.jlo.kitsune.api.protection.AccessDecision;
import dev.jlo.kitsune.api.protection.BlockAccessProvider;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Objects;

public final class LwcProtectionProvider implements BlockAccessProvider {
    private final LwcAccess access;

    public LwcProtectionProvider() {
        this(new DirectLwcAccess());
    }

    LwcProtectionProvider(LwcAccess access) {
        this.access = Objects.requireNonNull(
            access,
            "LWC access must not be null"
        );
    }

    @Override
    public AccessDecision canAccess(Player player, Block block) {
        Objects.requireNonNull(player, "Player must not be null");
        Objects.requireNonNull(block, "Block must not be null");
        try {
            Object protection = access.findProtection(block);
            if (protection == null) {
                return AccessDecision.NOT_APPLICABLE;
            }
            return access.canAccess(player, protection)
                ? AccessDecision.ALLOW
                : AccessDecision.DENY;
        } catch (RuntimeException | LinkageError failure) {
            return AccessDecision.DENY;
        }
    }

    interface LwcAccess {
        Object findProtection(Block block);

        boolean canAccess(Player player, Object protection);
    }

    private static final class DirectLwcAccess implements LwcAccess {
        private final LWC lwc;

        private DirectLwcAccess() {
            lwc = Objects.requireNonNull(
                LWC.getInstance(),
                "LWC is not initialized"
            );
        }

        @Override
        public Object findProtection(Block block) {
            return lwc.findProtection(block);
        }

        @Override
        public boolean canAccess(Player player, Object protection) {
            return lwc.canAccessProtection(
                player,
                (Protection) protection
            );
        }
    }
}
