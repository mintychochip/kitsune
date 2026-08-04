package dev.jlo.kitsune.embedding.remote;

import dev.jlo.kitsune.api.embedding.EmbeddingCredentialResolver;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class EnvironmentCredentialResolver implements EmbeddingCredentialResolver {
    private final Function<String, String> environment;

    public EnvironmentCredentialResolver() {
        this(System::getenv);
    }

    public EnvironmentCredentialResolver(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "Environment lookup must not be null");
    }

    @Override
    public Optional<String> resolve(String reference) {
        if (reference == null || reference.isBlank() || !reference.startsWith("env:")) {
            return Optional.empty();
        }
        String name = reference.substring("env:".length()).trim();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        String value = environment.apply(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
