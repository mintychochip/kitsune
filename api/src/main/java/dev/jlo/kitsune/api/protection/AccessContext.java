package dev.jlo.kitsune.api.protection;

import dev.jlo.kitsune.model.BlockKey;
import java.util.Objects;
import java.util.UUID;

/**
 * Neutral identity and location data used by access providers.
 *
 * <p>Implementations must not require or retain native platform objects.
 */
public record AccessContext(UUID playerId, String playerName, BlockKey block) {
    public AccessContext {
        Objects.requireNonNull(playerId, "Player ID must not be null");
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("Player name must not be blank");
        }
        Objects.requireNonNull(block, "Block must not be null");
    }
}
