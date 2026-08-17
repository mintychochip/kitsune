package dev.jlo.kitsune.embedding.remote;

import dev.jlo.kitsune.api.embedding.EmbeddingCredentialResolver;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves credential references of the form {@code env:&lt;NAME&gt;} from an
 * environment lookup.
 */
public final class EnvironmentCredentialResolver implements EmbeddingCredentialResolver {
    private final Function<String, String> environment;

    /**
     * Creates a resolver backed by the process environment.
     */
    public EnvironmentCredentialResolver() {
        this(System::getenv);
    }

    /**
     * Creates a resolver backed by the given environment lookup.
     *
     * @param environment function mapping an environment variable name to its
     *                    value, or {@code null} when unset
     * @throws NullPointerException if the lookup is null
     */
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
