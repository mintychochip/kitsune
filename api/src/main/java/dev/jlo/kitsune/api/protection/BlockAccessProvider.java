package dev.jlo.kitsune.api.protection;

/**
 * Provides block-level access decisions for a player.
 *
 * <p>Implementations must not retain values from the access context after the
 * call returns.
 */
public interface BlockAccessProvider {
    /**
     * Evaluates whether the player in the context may access the referenced
     * block.
     *
     * @param context identity, location, and access details
     * @return the access decision
     */
    AccessDecision canAccess(AccessContext context);
}
