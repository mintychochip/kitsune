package dev.jlo.kitsune.item;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.bukkit.inventory.ItemStack;

/**
 * Serializes Bukkit {@link ItemStack}s into deterministic byte fingerprints
 * for change comparison and content hashing.
 */
public final class BukkitItemSerialization {
    private BukkitItemSerialization() {}

    /**
     * Renders a stack to a canonical UTF-8 byte array.
     *
     * @param stack stack to serialize, must not be {@code null}
     * @return byte representation of the stack's serialized contents
     */
    public static byte[] bytes(ItemStack stack) {
        Objects.requireNonNull(stack, "Item stack must not be null");
        StringBuilder result = new StringBuilder();
        append(result, stack.serialize());
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void append(StringBuilder result, Object value) {
        if (value == null) {
            result.append("null");
        } else if (value instanceof Map<?, ?> map) {
            result.append('{');
            boolean first = true;
            for (var entry : new TreeMap<>(map).entrySet()) {
                if (!first) result.append(',');
                first = false;
                append(result, entry.getKey());
                result.append('=');
                append(result, entry.getValue());
            }
            result.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            result.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) result.append(',');
                first = false;
                append(result, item);
            }
            result.append(']');
        } else if (value.getClass().isArray()) {
            result.append('[');
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                if (index > 0) result.append(',');
                append(result, java.lang.reflect.Array.get(value, index));
            }
            result.append(']');
        } else {
            result.append(value);
        }
    }
}
