package dev.jlo.kitsune.search;

import dev.jlo.kitsune.session.SearchToken;

@FunctionalInterface
/** Determines whether a search token is still current. */
public interface SearchGuard {
    /** Returns whether the token represents an active search. */
    boolean isCurrent(SearchToken token);
}
