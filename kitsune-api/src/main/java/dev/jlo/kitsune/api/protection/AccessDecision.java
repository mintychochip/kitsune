package dev.jlo.kitsune.api.protection;

/**
 * The outcome of an access check.
 */
public enum AccessDecision {
    /** Access is explicitly permitted. */
    ALLOW,
    /** Access is explicitly denied. */
    DENY,
    /** This provider does not apply to the requested access. */
    NOT_APPLICABLE
}
