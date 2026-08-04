package dev.jlo.kitsune.api.protection;

/**
 * Provides block-level access decisions for a player.
 *
 * <p>Implementations must not retain values from the access context after the
 * call returns.
 */
public interface BlockAccessProvider {
    AccessDecision canAccess(AccessContext context);
}
