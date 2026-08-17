package dev.jlo.kitsune.protection;

import dev.jlo.kitsune.api.protection.AccessContext;
import dev.jlo.kitsune.api.protection.AccessDecision;
import dev.jlo.kitsune.api.protection.BlockAccessProvider;
import dev.jlo.kitsune.model.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies protection decision precedence and fail-closed handling. */
class ProtectionRegistryTest {
    private static final AccessContext CONTEXT = new AccessContext(
        UUID.fromString("00000000-0000-0000-0000-000000000010"),
        "TestPlayer",
        new BlockKey(
            UUID.fromString("00000000-0000-0000-0000-000000000011"),
            1,
            64,
            2
        )
    );

    @Test
    void denyWinsOverAllowAndNotApplicable() {
        ProtectionRegistry registry = new ProtectionRegistry(List.of(
            context -> AccessDecision.ALLOW,
            context -> AccessDecision.NOT_APPLICABLE,
            context -> AccessDecision.DENY
        ));

        assertFalse(registry.canAccess(CONTEXT));
    }

    @Test
    void nonDenyDecisionsAllowAccess() {
        ProtectionRegistry registry = new ProtectionRegistry(List.of(
            context -> AccessDecision.NOT_APPLICABLE,
            context -> AccessDecision.ALLOW
        ));

        assertTrue(registry.canAccess(CONTEXT));
    }

    @Test
    void providerFailuresAndNullDecisionsFailClosed() {
        BlockAccessProvider throwing = context -> {
            throw new IllegalStateException("test failure");
        };
        BlockAccessProvider nullDecision = context -> null;

        assertFalse(new ProtectionRegistry(List.of(throwing)).canAccess(CONTEXT));
        assertFalse(new ProtectionRegistry(List.of(nullDecision)).canAccess(CONTEXT));
    }
}
