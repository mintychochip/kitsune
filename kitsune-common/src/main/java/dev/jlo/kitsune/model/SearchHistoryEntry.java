package dev.jlo.kitsune.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable record of one player's search request.
 *
 * @param playerId player who submitted the search
 * @param query search text
 * @param timestamp time at which the search was submitted
 * @param resultCount number of results returned
 */
public record SearchHistoryEntry(UUID playerId, String query, Instant timestamp, int resultCount) {
    /** Validates the entry fields and their supported values. */
    public SearchHistoryEntry {
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (resultCount < 0) {
            throw new IllegalArgumentException("resultCount must be non-negative");
        }
    }
}
