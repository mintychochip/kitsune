package org.aincraft.kitsune;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

/**
 * Paper plugin loader for Kitsune.
 * Downloads DJL libraries at runtime to avoid shading issues with native library loading.
 */
@SuppressWarnings("UnstableApiUsage")
public class KitsuneLoader implements PluginLoader {

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        // Add Maven Central mirror (required by Paper 1.21.11+)
        resolver.addRepository(new RemoteRepository.Builder(
            "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR
        ).build());

        // DJL API
        resolver.addDependency(new Dependency(
            new DefaultArtifact("ai.djl:api:0.31.1"), null
        ));

        // DJL HuggingFace Tokenizers (includes native library resolution)
        resolver.addDependency(new Dependency(
            new DefaultArtifact("ai.djl.huggingface:tokenizers:0.31.1"), null
        ));

        classpathBuilder.addLibrary(resolver);
    }
}
