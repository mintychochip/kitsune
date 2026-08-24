package org.aincraft.kitsune.api.model;

import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the path through nested containers to reach an item.
 * For example: [Red Shulker Box (slot 12), Bundle (slot 5)] means the item is inside
 * a Bundle at slot 5, which is inside a Red Shulker Box at slot 12 of the root container.
 *
 * ROOT represents a top-level item (not in any container).
 *
 * Supports both rich ContainerNode format and legacy string format for backward compatibility.
 */
public record ContainerPath(List<ContainerNode> containerRefs) {
    public static final ContainerPath ROOT = new ContainerPath(Collections.emptyList());

    public ContainerPath {
        Preconditions.checkNotNull(containerRefs, "Container refs cannot be null");
        // Make immutable
        containerRefs = Collections.unmodifiableList(new ArrayList<>(containerRefs));
    }

    /**
     * Returns true if this is the root path (no containers).
     */
    public boolean isRoot() {
        return containerRefs.isEmpty();
    }

    /**
     * Returns the depth (number of nested containers).
     */
    public int depth() {
        return containerRefs.size();
    }

    /**
     * Appends a container reference to the path.
     * @param node the container reference to push
     * @return a new ContainerPath with the node appended
     */
    public ContainerPath push(ContainerNode node) {
        Preconditions.checkNotNull(node, "Container node cannot be null");
        List<ContainerNode> newPath = new ArrayList<>(containerRefs);
        newPath.add(node);
        return new ContainerPath(newPath);
    }

    /**
     * Returns the innermost container node, or null if root.
     */
    @Nullable
    public ContainerNode getInnermost() {
        return containerRefs.isEmpty() ? null : containerRefs.get(containerRefs.size() - 1);
    }

    /**
     * Returns a display string like "in Red Shulker Box (slot 12) in Bundle (slot 5)" for the path.
     * For root, returns empty string.
     */
    public String toDisplayString() {
        if (containerRefs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContainerNode node : containerRefs) {
            if (sb.length() > 0) {
                sb.append(" in ");
            } else {
                sb.append("in ");
            }
            sb.append(nodeToDisplayString(node));
        }
        return sb.toString();
    }

    /**
     * Helper method to format a ContainerNode as a display string.
     * Format: "Red Shulker Box (slot 12)".
     */
    private static String nodeToDisplayString(ContainerNode node) {
        StringBuilder sb = new StringBuilder();

        // Add color if present
        if (node.getColor() != null && !node.getColor().isEmpty()) {
            sb.append(capitalize(node.getColor())).append(" ");
        }

        // Add container type or custom name
        if (node.getCustomName() != null && !node.getCustomName().isEmpty()) {
            sb.append(node.getCustomName());
        } else {
            sb.append(formatContainerType(node.getContainerType()));
        }

        // Add slot info
        sb.append(" (slot ").append(node.getSlotIndex()).append(")");

        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private static String formatContainerType(String type) {
        if (type == null) return "Container";
        return switch (type.toLowerCase()) {
            case "shulker_box", "shulker" -> "Shulker Box";
            case "bundle" -> "Bundle";
            default -> capitalize(type.replace("_", " "));
        };
    }

    /**
     * Serializes the path to JSON string.
     * Format: [{node1}, {node2}] or "[]" for root.
     * Uses new rich format with ContainerNode objects.
     */
    public String toJson() {
        JsonArray arr = new JsonArray();
        for (ContainerNode node : containerRefs) {
            JsonObject obj = new JsonObject();
            obj.addProperty("containerType", node.getContainerType());
            if (node.getColor() != null) obj.addProperty("color", node.getColor());
            if (node.getCustomName() != null) obj.addProperty("customName", node.getCustomName());
            obj.addProperty("slotIndex", node.getSlotIndex());
            arr.add(obj);
        }
        return arr.toString();
    }

    /**
     * Deserializes a path from JSON string.
     * Supports both new rich format and legacy string format for backward compatibility.
     *
     * @param json JSON string - either rich format [{...}] or legacy format ["str1", "str2"]
     * @return the deserialized ContainerPath
     * @throws IllegalArgumentException if JSON is invalid
     */
    public static ContainerPath fromJson(@Nullable String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) {
            return ROOT;
        }
        try {
            JsonElement elem = JsonParser.parseString(json);
            Preconditions.checkArgument(elem.isJsonArray(), "Expected JSON array for ContainerPath");
            JsonArray arr = elem.getAsJsonArray();

            if (arr.size() == 0) {
                return ROOT;
            }

            List<ContainerNode> nodes = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement item = arr.get(i);

                // Support both formats: legacy strings and new objects
                if (item.isJsonPrimitive()) {
                    // Legacy format: convert simple string to a ContainerNode
                    String name = item.getAsString();
                    nodes.add(new ContainerNode("container", null, name, i, null, null));
                } else if (item.isJsonObject()) {
                    // New format: parse as ContainerNode
                    JsonObject obj = item.getAsJsonObject();
                    String containerType = obj.has("containerType") ? obj.get("containerType").getAsString() : "container";
                    String color = obj.has("color") ? obj.get("color").getAsString() : null;
                    String customName = obj.has("customName") ? obj.get("customName").getAsString() : null;
                    int slotIndex = obj.has("slotIndex") ? obj.get("slotIndex").getAsInt() : 0;
                    nodes.add(new ContainerNode(containerType, color, customName, slotIndex, null, null));
                } else {
                    throw new IllegalArgumentException("Expected string or object in container path array at index " + i);
                }
            }
            return new ContainerPath(nodes);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON for ContainerPath: " + json, e);
        }
    }

    /**
     * Creates a ContainerPath from legacy string list for backward compatibility.
     * Each string becomes a container with that custom name.
     *
     * @param containerNames legacy container names
     * @return new ContainerPath
     */
    public static ContainerPath fromLegacyStrings(List<String> containerNames) {
        Preconditions.checkNotNull(containerNames, "Container names cannot be null");
        if (containerNames.isEmpty()) {
            return ROOT;
        }
        List<ContainerNode> nodes = new ArrayList<>();
        for (int i = 0; i < containerNames.size(); i++) {
            nodes.add(new ContainerNode("container", null, containerNames.get(i), i, null, null));
        }
        return new ContainerPath(nodes);
    }
}
