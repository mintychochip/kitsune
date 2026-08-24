package org.aincraft.kitsune.model;

import com.google.common.base.Preconditions;
import java.util.List;
import org.aincraft.kitsune.Location;
import org.aincraft.kitsune.api.model.ContainerPath;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a search result for a container containing items of interest.
 *
 * Can optionally include the path through nested containers where the item was found.
 */
public record SearchResult(
    Location location,
    List<Location> allLocations,
    double score,
    String preview,
    String fullContent,
    @Nullable ContainerPath containerPath
) {
    // Compact constructor with validation
    public SearchResult {
        Preconditions.checkNotNull(location, "Location cannot be null");
        Preconditions.checkNotNull(preview, "Preview cannot be null");
        Preconditions.checkArgument(score >= 0 && score <= 1, "Score must be between 0 and 1");
        // allLocations defaults to single location if not provided
        if (allLocations == null) {
            allLocations = List.of(location);
        }
        // fullContent can be null for backwards compatibility
        // containerPath can be null for backwards compatibility
    }
}
