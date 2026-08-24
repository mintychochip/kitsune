package org.aincraft.kitsune.model.tree;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.aincraft.kitsune.Location;
import org.aincraft.kitsune.api.model.ContainerNode;
import org.jetbrains.annotations.Nullable;

public final class SearchResultTreeNode {

    public enum NodeType {
        LOCATION,
        CONTAINER,
        ITEM
    }

    private final NodeType type;
    private final String displayName;
    private final List<SearchResultTreeNode> children;

    @Nullable private final Location location;
    @Nullable private final String containerType;
    @Nullable private final ContainerNode containerRef;
    @Nullable private final Integer containerScorePercent;
    @Nullable private final ItemResultData itemData;

    private SearchResultTreeNode(
            NodeType type,
            String displayName,
            @Nullable Location location,
            @Nullable String containerType,
            @Nullable ContainerNode containerRef,
            @Nullable Integer containerScorePercent,
            @Nullable ItemResultData itemData) {
        this.type = Preconditions.checkNotNull(type, "Node type cannot be null");
        this.displayName = Preconditions.checkNotNull(displayName, "Display name cannot be null");
        this.children = new ArrayList<>();
        this.location = location;
        this.containerType = containerType;
        this.containerRef = containerRef;
        this.containerScorePercent = containerScorePercent;
        this.itemData = itemData;

        switch (type) {
            case LOCATION:
                Preconditions.checkArgument(location != null, "LOCATION node requires location");
                Preconditions.checkArgument(
                        containerRef == null && containerScorePercent == null && itemData == null,
                        "LOCATION node must not have containerRef, score, or itemData");
                break;
            case CONTAINER:
                Preconditions.checkArgument(containerRef != null, "CONTAINER node requires containerRef");
                Preconditions.checkArgument(
                        location == null && containerType == null && itemData == null,
                        "CONTAINER node must not have location, containerType, or item data");
                break;
            case ITEM:
                Preconditions.checkArgument(itemData != null, "ITEM node requires itemData");
                Preconditions.checkArgument(
                        location == null && containerType == null &&
                        containerRef == null && containerScorePercent == null,
                        "ITEM node must not have location, containerType, or container data");
                break;
        }
    }

    public static SearchResultTreeNode locationNode(Location location) {
        return locationNode(location, null);
    }

    public static SearchResultTreeNode locationNode(Location location, @Nullable String containerType) {
        Preconditions.checkNotNull(location, "Location cannot be null");
        return new SearchResultTreeNode(
                NodeType.LOCATION,
                String.format("World: %s", location.asCoordinates()),
                location,
                containerType,
                null, null, null);
    }

    public static SearchResultTreeNode containerNode(ContainerNode ref) {
        Preconditions.checkNotNull(ref, "ContainerNode cannot be null");
        return new SearchResultTreeNode(
                NodeType.CONTAINER, formatContainerDisplay(ref), null, null, ref, null, null);
    }

    public static SearchResultTreeNode containerNodeWithScore(ContainerNode ref, int scorePercent) {
        Preconditions.checkNotNull(ref, "ContainerNode cannot be null");
        return new SearchResultTreeNode(
                NodeType.CONTAINER, formatContainerDisplay(ref), null, null, ref, scorePercent, null);
    }

    public static SearchResultTreeNode itemNode(ItemResultData data) {
        Preconditions.checkNotNull(data, "ItemResultData cannot be null");
        return new SearchResultTreeNode(NodeType.ITEM, data.getDisplayName(), null, null, null, null, data);
    }

    private static String formatContainerDisplay(ContainerNode node) {
        StringBuilder sb = new StringBuilder();
        String color = node.getColor();
        if (color != null && !color.isEmpty()) {
            sb.append(capitalize(color)).append(" ");
        }
        String customName = node.getCustomName();
        if (customName != null && !customName.isEmpty()) {
            sb.append(customName);
        } else {
            sb.append(formatContainerType(node.getContainerType()));
        }
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

    public NodeType getType() { return type; }
    public String getDisplayName() { return displayName; }

    public void addChild(SearchResultTreeNode child) {
        Preconditions.checkNotNull(child, "Child node cannot be null");
        children.add(child);
    }

    public List<SearchResultTreeNode> getChildren() { return Collections.unmodifiableList(children); }

    @Nullable public String getWorld() { return location != null ? location.getWorld().getName() : null; }
    @Nullable public String getCoords() { return location != null ? location.asCoordinates() : null; }
    @Nullable public Location getLocation() { return location; }
    @Nullable public String getContainerType() { return containerType; }
    @Nullable public ContainerNode getContainerRef() { return containerRef; }
    @Nullable public Integer getContainerScorePercent() { return containerScorePercent; }
    @Nullable public ItemResultData getItemData() { return itemData; }

    @Override
    public String toString() {
        return "SearchResultTreeNode{type=" + type + ", displayName='" + displayName + "', children=" + children.size() + '}';
    }
}
