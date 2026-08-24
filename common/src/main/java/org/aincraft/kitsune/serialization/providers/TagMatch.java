package org.aincraft.kitsune.serialization.providers;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * A utility class providing predicate factories for string matching operations.
 */
public final class TagMatch {

    private TagMatch() {
        // Prevent instantiation
    }

    /**
     * Creates a predicate that tests if a string ends with the given suffix.
     *
     * @param suffix the suffix to test for
     * @return a predicate that returns true if the string ends with the suffix
     */
    public static Predicate<String> endsWith(String suffix) {
        return str -> str.endsWith(suffix);
    }

    /**
     * Creates a predicate that tests if a string contains the given substring.
     *
     * @param substr the substring to test for
     * @return a predicate that returns true if the string contains the substring
     */
    public static Predicate<String> contains(String substr) {
        return str -> str.contains(substr);
    }

    /**
     * Creates a predicate that tests if a string equals the given value.
     *
     * @param value the value to test for
     * @return a predicate that returns true if the string equals the value
     */
    public static Predicate<String> equals(String value) {
        return str -> str.equals(value);
    }

    /**
     * Creates a predicate that tests if a string starts with the given prefix.
     *
     * @param prefix the prefix to test for
     * @return a predicate that returns true if the string starts with the prefix
     */
    public static Predicate<String> startsWith(String prefix) {
        return str -> str.startsWith(prefix);
    }

    /**
     * Creates a predicate that returns true if any of the given predicates match.
     *
     * @param predicates the predicates to test (varargs)
     * @return a predicate that returns true if any predicate matches
     */
    @SafeVarargs
    public static Predicate<String> any(Predicate<String>... predicates) {
        return str -> Arrays.stream(predicates).anyMatch(predicate -> predicate.test(str));
    }
}