package org.aincraft.kitsune.storage;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks embedding provider metadata to detect provider changes.
 * Stores provider name and model in a properties file.
 * When provider changes, vectors become incompatible and must be purged.
 */
public final class ProviderMetadata {
    private static final String PROVIDER_KEY = "embedding.provider";
    private static final String MODEL_KEY = "embedding.model";
    private static final String FILENAME = "provider_metadata.properties";

    private final Logger logger;
    private final Path metadataPath;
    private String storedProvider;
    private String storedModel;

    public ProviderMetadata(Logger logger, Path dataFolder) {
        this.logger = Preconditions.checkNotNull(logger, "logger cannot be null");
        Preconditions.checkNotNull(dataFolder, "dataFolder cannot be null");
        this.metadataPath = dataFolder.resolve(FILENAME);
    }

    /**
     * Load stored metadata from file.
     */
    public void load() {
        if (!Files.exists(metadataPath)) {
            storedProvider = null;
            storedModel = null;
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(metadataPath)) {
            props.load(in);
            storedProvider = props.getProperty(PROVIDER_KEY);
            storedModel = props.getProperty(MODEL_KEY);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to load provider metadata", e);
            storedProvider = null;
            storedModel = null;
        }
    }

    /**
     * Save current provider info to file.
     */
    public void save(String provider, String model) {
        Preconditions.checkNotNull(provider, "provider cannot be null");
        Preconditions.checkNotNull(model, "model cannot be null");

        Properties props = new Properties();
        props.setProperty(PROVIDER_KEY, provider);
        props.setProperty(MODEL_KEY, model);

        try {
            Files.createDirectories(metadataPath.getParent());
            try (OutputStream out = Files.newOutputStream(metadataPath)) {
                props.store(out, "Kitsune Embedding Provider Metadata - DO NOT EDIT");
            }
            storedProvider = provider;
            storedModel = model;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to save provider metadata", e);
        }
    }

    /**
     * Check if provider has changed from stored metadata.
     * Returns empty if no stored metadata (first run).
     */
    public Optional<ProviderMismatch> checkMismatch(String currentProvider, String currentModel) {
        if (storedProvider == null || storedModel == null) {
            // First run or metadata was cleared
            return Optional.empty();
        }

        boolean providerChanged = !Objects.equals(storedProvider, currentProvider);
        boolean modelChanged = !Objects.equals(storedModel, currentModel);

        if (providerChanged || modelChanged) {
            return Optional.of(new ProviderMismatch(
                storedProvider, storedModel,
                currentProvider, currentModel
            ));
        }

        return Optional.empty();
    }

    /**
     * Delete the metadata file (used after purge).
     */
    public void delete() {
        try {
            Files.deleteIfExists(metadataPath);
            storedProvider = null;
            storedModel = null;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to delete provider metadata", e);
        }
    }

    public Optional<String> getStoredProvider() {
        return Optional.ofNullable(storedProvider);
    }

    public Optional<String> getStoredModel() {
        return Optional.ofNullable(storedModel);
    }

    /**
     * Represents a mismatch between stored and current provider.
     */
    public record ProviderMismatch(
        String storedProvider,
        String storedModel,
        String currentProvider,
        String currentModel
    ) {
        public String message() {
            return String.format(
                "Embedding provider changed from %s/%s to %s/%s. " +
                "Existing vectors are incompatible. Run '/kitsune purge' to clear old data.",
                storedProvider, storedModel, currentProvider, currentModel
            );
        }
    }
}
