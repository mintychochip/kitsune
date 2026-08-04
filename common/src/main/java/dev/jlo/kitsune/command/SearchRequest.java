package dev.jlo.kitsune.command;

import java.util.Arrays;

public record SearchRequest(String query, boolean verbose) {
    public SearchRequest {
        query = query.strip().replaceAll("\\s+", " ");
        if (query.isEmpty()) throw new IllegalArgumentException("Query must not be empty");
    }

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
