package dev.jlo.kitsune.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Neutral, persistent representation of an Adventure item hover payload.
 *
 * <p>Data-component values and the optional legacy tag are stored as SNBT so
 * platform code can reconstruct the exact {@code HoverEvent.ShowItem} without
 * rebuilding the item from the lossy semantic descriptor.
 *
 * @param itemKey item registry key
 * @param count stack count
 * @param legacyNbt optional legacy item tag as SNBT, or {@code null}
 * @param dataComponents data-component keys mapped to SNBT values
 * @param removedDataComponents data-component keys explicitly removed from the item
 */
public record ItemHoverPayload(
    String itemKey,
    int count,
    String legacyNbt,
    Map<String, String> dataComponents,
    Set<String> removedDataComponents
) {
    /** Maximum distinct component keys accepted in one payload. */
    public static final int MAX_COMPONENTS = 256;
    /** Maximum total Java characters accepted before UTF-8 codec bounds apply. */
    public static final int MAX_SERIALIZED_CHARACTERS = 4 * 1024 * 1024;

    public ItemHoverPayload {
        if (itemKey == null || itemKey.isBlank()) {
            throw new IllegalArgumentException("Item key must not be blank");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("Item count must be positive");
        }
        Objects.requireNonNull(dataComponents, "Data components must not be null");
        Objects.requireNonNull(removedDataComponents, "Removed data components must not be null");

        TreeMap<String, String> components = new TreeMap<>();
        for (Map.Entry<String, String> entry : dataComponents.entrySet()) {
            String key = requireKey(entry.getKey());
            String value = Objects.requireNonNull(entry.getValue(), "Component SNBT must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException("Component SNBT must not be blank");
            }
            components.put(key, value);
        }

        TreeSet<String> removed = new TreeSet<>();
        for (String key : removedDataComponents) {
            removed.add(requireKey(key));
        }
        for (String key : removed) {
            if (components.containsKey(key)) {
                throw new IllegalArgumentException("Component cannot be present and removed: " + key);
            }
        }
        if (Math.addExact(components.size(), removed.size()) > MAX_COMPONENTS) {
            throw new IllegalArgumentException("Too many item data components");
        }
        if (legacyNbt != null && legacyNbt.isBlank()) {
            throw new IllegalArgumentException("Legacy item NBT must not be blank");
        }

        long characters = itemKey.length();
        if (legacyNbt != null) characters += legacyNbt.length();
        for (Map.Entry<String, String> entry : components.entrySet()) {
            characters += (long) entry.getKey().length() + entry.getValue().length();
        }
        for (String key : removed) characters += key.length();
        if (characters > MAX_SERIALIZED_CHARACTERS) {
            throw new IllegalArgumentException("Item hover payload too large");
        }

        dataComponents = Collections.unmodifiableMap(components);
        removedDataComponents = Collections.unmodifiableSet(removed);
    }

    /** Creates a payload containing only the item key and count. */
    public static ItemHoverPayload base(String itemKey, int count) {
        return new ItemHoverPayload(itemKey, count, null, Map.of(), Set.of());
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Data component key must not be blank");
        }
        return key;
    }
}
