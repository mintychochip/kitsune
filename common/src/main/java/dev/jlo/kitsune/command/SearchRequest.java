package dev.jlo.kitsune.command;

import java.util.Arrays;

/** Parsed and normalized arguments for a Kitsune search command. */
public record SearchRequest(String query, boolean verbose) {
    /** Creates a request, normalizing whitespace and rejecting an empty query. */
    public SearchRequest {
        query = query.strip().replaceAll("\\s+", " ");
        if (query.isEmpty()) throw new IllegalArgumentException("Query must not be empty");
    }

    /**
     * Parses command arguments, accepting an optional {@code --verbose} flag.
     *
     * @param args command arguments containing the optional flag and query words
     * @return the parsed search request
     * @throws IllegalArgumentException if arguments are missing, contain an unknown flag,
     *         or produce an empty query
     */
    public static SearchRequest parse(String[] args) {
        if (args.length == 0) throw new IllegalArgumentException("Usage: /kitsune [--verbose] <query>");
        boolean verbose = args[0].equals("--verbose");
        int start = verbose ? 1 : 0;
        if (start == args.length) throw new IllegalArgumentException("Usage: /kitsune [--verbose] <query>");
        for (int i = start; i < args.length; i++) {
            if (args[i].startsWith("--")) throw new IllegalArgumentException("Unknown or misplaced flag: " + args[i]);
        }
        return new SearchRequest(String.join(" ", Arrays.copyOfRange(args, start, args.length)), verbose);
    }
}
