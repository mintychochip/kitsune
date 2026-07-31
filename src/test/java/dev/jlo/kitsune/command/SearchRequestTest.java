package dev.jlo.kitsune.command;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchRequestTest {

    @Test
    void parsesInvocationScopedVerboseQuery() {
        assertEquals(new SearchRequest("diamond sword", true),
            SearchRequest.parse(new String[]{"--verbose", "diamond", "sword"}));
    }

    @Test
    void rejectsMisplacedFlagAndEmptyQuery() {
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class,
            () -> SearchRequest.parse(new String[]{"diamond", "--verbose"}));
        assertEquals("Unknown or misplaced flag: --verbose", ex1.getMessage());
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
            () -> SearchRequest.parse(new String[]{}));
        assertEquals("Usage: /kitsune [--verbose] <query>", ex2.getMessage());
        IllegalArgumentException ex3 = assertThrows(IllegalArgumentException.class,
            () -> SearchRequest.parse(new String[]{"--verbose"}));
        assertEquals("Usage: /kitsune [--verbose] <query>", ex3.getMessage());
    }

    @Test
    void normalizesWhitespace() {
        assertEquals(new SearchRequest("diamond sword", false),
            SearchRequest.parse(new String[]{"diamond", " ", "sword"}));
    }
}
