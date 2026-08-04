package dev.jlo.kitsune.api;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Locale;
import org.junit.jupiter.api.Test;

final class ApiDependencyBoundaryTest {
    @Test
    void apiTestClasspathContainsNoPlatformDependency() {
        String classpath = System.getProperty("java.class.path", "")
            .toLowerCase(Locale.ROOT);

        assertFalse(classpath.contains("paper-api"));
        assertFalse(classpath.contains("spigot-api"));
        assertFalse(classpath.contains("fabric-api"));
        assertFalse(classpath.contains("forge"));
        assertFalse(classpath.contains("neoforge"));
        assertFalse(classpath.contains("minecraft"));
    }
}
