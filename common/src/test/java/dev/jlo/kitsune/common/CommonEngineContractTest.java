package dev.jlo.kitsune.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jlo.kitsune.api.protection.AccessContext;
import dev.jlo.kitsune.api.protection.AccessDecision;
import dev.jlo.kitsune.api.protection.BlockAccessProvider;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.protection.ProtectionRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CommonEngineContractTest {
    @Test
    void protectionDecisionsUseNeutralContextAndDenyWins() {
        AccessContext context = new AccessContext(
            UUID.randomUUID(),
            "searcher",
            new BlockKey(UUID.randomUUID(), 10, 64, -4)
        );
        BlockAccessProvider allow = ignored -> AccessDecision.ALLOW;
        BlockAccessProvider deny = ignored -> AccessDecision.DENY;

        assertTrue(new ProtectionRegistry(List.of(allow)).canAccess(context));
        assertFalse(new ProtectionRegistry(List.of(allow, deny)).canAccess(context));
    }
}
