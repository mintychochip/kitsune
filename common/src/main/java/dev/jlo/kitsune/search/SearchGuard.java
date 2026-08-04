package dev.jlo.kitsune.search;

import dev.jlo.kitsune.session.SearchToken;

@FunctionalInterface
public interface SearchGuard {
    boolean isCurrent(SearchToken token);
}
