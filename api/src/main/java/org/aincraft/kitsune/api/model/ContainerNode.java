package org.aincraft.kitsune.api.model;

import org.aincraft.kitsune.api.indexing.SerializedItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a node in the container tree structure.
 * Each node represents either:
 * - A container (with children and items)
 * - A leaf container (only items, no nested containers)
 */
public class ContainerNode {
    private final String containerType;
    private final String color;
    private final String customName;
    private final int slotIndex;
    private final List<ContainerNode> children;
    private final List<SerializedItem> items;

    /**
     * Creates a new ContainerNode representing a container with nested items.
     */
    public ContainerNode(
            String containerType,
            String color,
            String customName,
            int slotIndex,
            List<ContainerNode> children,
            List<SerializedItem> items) {
        this.containerType = Objects.requireNonNull(containerType, "Container type cannot be null");
        this.color = color;
        this.customName = customName;
        this.slotIndex = slotIndex;
        this.children = children != null ? new ArrayList<>(children) : new ArrayList<>();
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    /**
     * Creates a new ContainerNode with no children (leaf node).
     */
    public ContainerNode(
            String containerType,
            String color,
            String customName,
            int slotIndex,
            List<SerializedItem> items) {
        this(containerType, color, customName, slotIndex, null, items);
    }

    public String getContainerType() {
        return containerType;
    }

    public String getColor() {
        return color;
    }

    public String getCustomName() {
        return customName;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public List<ContainerNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public List<SerializedItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public ContainerNode withChild(ContainerNode child) {
        List<ContainerNode> newChildren = new ArrayList<>(this.children);
        newChildren.add(child);
        return new ContainerNode(containerType, color, customName, slotIndex, newChildren, items);
    }

    public ContainerNode withItem(SerializedItem item) {
        List<SerializedItem> newItems = new ArrayList<>(this.items);
        newItems.add(item);
        return new ContainerNode(containerType, color, customName, slotIndex, children, newItems);
    }

    /**
     * Flattens all items in this tree with their full container paths.
     *
     * @return List of ItemWithPath containing serialized items and their nested paths
     */
    public List<ItemWithPath> flattenWithPaths() {
        List<ItemWithPath> result = new ArrayList<>();
        flattenWithPaths(result, new ArrayList<>());
        return result;
    }

    private void flattenWithPaths(List<ItemWithPath> result, List<ContainerNode> currentPath) {
        for (SerializedItem item : items) {
            result.add(new ItemWithPath(item, new ArrayList<>(currentPath)));
        }

        for (ContainerNode child : children) {
            List<ContainerNode> childPath = new ArrayList<>(currentPath);
            childPath.add(child);
            child.flattenWithPaths(result, childPath);
        }
    }

    /**
     * Represents an item with its full path through nested containers.
     */
    public static class ItemWithPath {
        private final SerializedItem item;
        private final List<ContainerNode> path;

        public ItemWithPath(SerializedItem item, List<ContainerNode> path) {
            this.item = Objects.requireNonNull(item, "Item cannot be null");
            this.path = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(path, "Path cannot be null")));
        }

        public SerializedItem getItem() {
            return item;
        }

        public List<ContainerNode> getPath() {
            return path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemWithPath that = (ItemWithPath) o;
            return Objects.equals(item, that.item) && Objects.equals(path, that.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(item, path);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ContainerNode that = (ContainerNode) o;
        return slotIndex == that.slotIndex &&
               Objects.equals(containerType, that.containerType) &&
               Objects.equals(color, that.color) &&
               Objects.equals(customName, that.customName) &&
               Objects.equals(children, that.children) &&
               Objects.equals(items, that.items);
    }

    @Override
    public int hashCode() {
        return Objects.hash(containerType, color, customName, slotIndex, children, items);
    }

    @Override
    public String toString() {
        return "ContainerNode{" +
                "containerType='" + containerType + '\'' +
                ", color='" + color + '\'' +
                ", customName='" + customName + '\'' +
                ", slotIndex=" + slotIndex +
                ", children=" + children.size() +
                ", items=" + items.size() +
                '}';
    }
}