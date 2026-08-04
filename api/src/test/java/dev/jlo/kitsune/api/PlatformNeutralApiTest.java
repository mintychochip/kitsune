package dev.jlo.kitsune.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class PlatformNeutralApiTest {
    private static final List<String> PUBLIC_TYPES = List.of(
        "dev.jlo.kitsune.model.BlockKey",
        "dev.jlo.kitsune.model.ChunkKey",
        "dev.jlo.kitsune.model.ContainerDraft",
        "dev.jlo.kitsune.model.ContainerSnapshot",
        "dev.jlo.kitsune.model.IndexedItem",
        "dev.jlo.kitsune.model.ItemDescriptor",
        "dev.jlo.kitsune.model.ItemDraft",
        "dev.jlo.kitsune.model.ItemPath",
        "dev.jlo.kitsune.model.ItemPathStep",
        "dev.jlo.kitsune.model.RootIdentity",
        "dev.jlo.kitsune.api.embedding.Embedding",
        "dev.jlo.kitsune.api.embedding.EmbeddingProvider",
        "dev.jlo.kitsune.api.item.ItemFeatureProvider",
        "dev.jlo.kitsune.api.item.NestedContentsProvider",
        "dev.jlo.kitsune.api.protection.AccessContext",
        "dev.jlo.kitsune.api.protection.AccessDecision",
        "dev.jlo.kitsune.api.protection.BlockAccessProvider"
    );

    private static final Set<String> PLATFORM_PREFIXES = Set.of(
        "org.bukkit.",
        "io.papermc.",
        "net.fabricmc.",
        "net.minecraftforge.",
        "net.neoforged.",
        "net.minecraft."
    );

    @Test
    void publicApiTypesExistAndExposeNoPlatformTypes() throws ClassNotFoundException {
        for (String name : PUBLIC_TYPES) {
            Class<?> type = Class.forName(name);
            assertNotNull(type);
            assertNeutral(type);
            for (Method method : type.getMethods()) {
                assertNeutral(method.getReturnType());
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertNeutral(parameter);
                }
            }
            RecordComponent[] components = type.getRecordComponents();
            if (components != null) {
                for (RecordComponent component : components) {
                    assertNeutral(component.getType());
                }
            }
        }
    }

    private static void assertNeutral(Class<?> type) {
        String name = type.getName();
        for (String prefix : PLATFORM_PREFIXES) {
            assertFalse(name.startsWith(prefix), () -> name + " leaks a platform type");
        }
    }
}
