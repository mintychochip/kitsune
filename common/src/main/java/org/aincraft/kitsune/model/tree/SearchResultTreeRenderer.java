package org.aincraft.kitsune.model.tree;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.aincraft.kitsune.Util;
import org.aincraft.kitsune.api.model.ContainerPath;
import org.aincraft.kitsune.api.model.ContainerNode;

public final class SearchResultTreeRenderer {

  private enum FormatTemplate {
    LOCATION_HEADER("<gold><name></gold><dark_gray> [<yellow><bold><coords></bold></yellow>]</dark_gray>"),
    CONTAINER_LINE("<white><connector></white><aqua><name></aqua><dark_gray> [<count> items]</dark_gray>"),
    ITEM_LINE("<white><connector></white><dark_gray>x<amount> </dark_gray><name><yellow> (<score>%)</yellow>"),
    ITEM_HOVER("<white><name></white>\n<gray>Match: </gray><yellow><score>%</yellow>\n<gray>Slot: </gray><yellow><slot></yellow><path>"),
    PATH_LINE("\n<gray>Path: </gray><yellow><path></yellow>");

    private final String format;

    FormatTemplate(String format) {
      this.format = format;
    }

    public Component render(TagResolver... tagResolvers) {
      return MINI.deserialize(format, tagResolvers);
    }

    public Component renderWithTextTag(String key, Object value) {
      return render(textTag(key, value));
    }

    public Component renderWithTextTags(Object... keyValuePairs) {
      TagResolver[] resolvers = new TagResolver[keyValuePairs.length / 2];
      for (int i = 0; i < keyValuePairs.length; i += 2) {
        resolvers[i / 2] = textTag(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
      }
      return render(resolvers);
    }
  }
  
  private static final String BRANCH = "├─ ";
  private static final String LAST_BRANCH = "└─ ";
  private static final String VERTICAL = "│  ";
  private static final String SPACE = "   ";

  public static final SearchResultTreeRenderer INSTANCE = new SearchResultTreeRenderer();
  private static final MiniMessage MINI = MiniMessage.miniMessage();

  private SearchResultTreeRenderer() {}

  private static TagResolver textTag(String key, Object value) {
    return TagResolver.resolver(key, Tag.inserting(Component.text(String.valueOf(value))));
  }

  private static TagResolver componentTag(String key, Component value) {
    return TagResolver.resolver(key, Tag.inserting(value));
  }

  public List<Component> render(List<SearchResultTreeNode> roots) {
    List<Component> lines = new ArrayList<>();

    for (int i = 0; i < roots.size(); i++) {
      SearchResultTreeNode root = roots.get(i);

      if (root.getType() == SearchResultTreeNode.NodeType.LOCATION) {
        String coords = root.getCoords();
        String containerType = root.getContainerType();
        String containerName = containerType != null && !containerType.isEmpty()
            ? Util.fromMaterialToTitleCase(containerType)
            : "Container";

        lines.add(FormatTemplate.LOCATION_HEADER.renderWithTextTags(
            "name", containerName,
            "coords", coords != null ? coords : "?"));

        List<SearchResultTreeNode> children = root.getChildren();
        for (int j = 0; j < children.size(); j++) {
          renderNode(children.get(j), "", j == children.size() - 1, lines);
        }

        if (i < roots.size() - 1) {
          lines.add(Component.empty());
        }
      }
    }
    return lines;
  }

  private void renderNode(SearchResultTreeNode node, String prefix, boolean isLast, List<Component> lines) {
    String connector = isLast ? LAST_BRANCH : BRANCH;
    String childPrefix = prefix + (isLast ? SPACE : VERTICAL);

    Component nodeComponent = switch (node.getType()) {
      case CONTAINER -> buildContainerComponent(node, connector);
      case ITEM -> buildItemComponent(node, connector);
      default -> Component.text(node.getDisplayName());
    };

    lines.add(Component.text(prefix).append(nodeComponent));

    List<SearchResultTreeNode> children = node.getChildren();
    for (int i = 0; i < children.size(); i++) {
      renderNode(children.get(i), childPrefix, i == children.size() - 1, lines);
    }
  }

  private Component buildContainerComponent(SearchResultTreeNode node, String connector) {
    ContainerNode ref = node.getContainerRef();
    if (ref == null) {
      return Component.text(connector + node.getDisplayName(), NamedTextColor.AQUA);
    }

    String containerName = formatContainerNameWithoutSlot(ref);
    int itemCount = countItems(node);

    return FormatTemplate.CONTAINER_LINE.render(
            textTag("connector", connector),
            textTag("name", containerName),
            textTag("count", itemCount))
        .hoverEvent(HoverEvent.showText(
            Component.text(String.format("Contains %d items", itemCount), NamedTextColor.GRAY)));
  }

  private Component buildItemComponent(SearchResultTreeNode node, String connector) {
    ItemResultData itemData = node.getItemData();
    if (itemData == null) {
      return Component.text(connector + node.getDisplayName(), NamedTextColor.WHITE);
    }

    return FormatTemplate.ITEM_LINE.render(
            textTag("connector", connector),
            textTag("amount", itemData.getAmount()),
            textTag("name", itemData.getDisplayName()),
            textTag("score", itemData.getScorePercent()))
        .hoverEvent(buildItemHoverEvent(itemData));
  }

  private HoverEvent<?> buildItemHoverEvent(ItemResultData itemData) {
    if (itemData.getItemContext() != null) {
      return itemData.getItemContext().asHoverEvent();
    }

    Component hoverText = FormatTemplate.ITEM_HOVER.render(
        textTag("name", itemData.getDisplayName()),
        textTag("score", itemData.getScorePercent()),
        textTag("slot", itemData.getSlotIndex()),
        componentTag("path", buildPathComponent(itemData)));

    return HoverEvent.showText(hoverText);
  }

  private Component buildPathComponent(ItemResultData itemData) {
    ContainerPath containerPath = itemData.getContainerPath();
    if (containerPath == null || containerPath.isRoot()) {
      return Component.text("\nLocation: Chest", NamedTextColor.GRAY);
    }
    return FormatTemplate.PATH_LINE.render(
        textTag("path", buildPathDisplay(containerPath)));
  }

  private String buildPathDisplay(ContainerPath path) {
    if (path.isRoot()) return "Chest";

    StringBuilder sb = new StringBuilder("Chest");
    for (ContainerNode ref : path.containerRefs()) {
      sb.append(" → ");
      String containerName = ref.getCustomName();
      if (containerName == null || containerName.isEmpty()) {
        String type = ref.getContainerType();
        String color = ref.getColor();
        if ("shulker_box".equals(type)) {
          containerName = (color != null && !color.isEmpty())
              ? capitalize(color) + " Shulker Box"
              : "Shulker Box";
        } else if ("bundle".equals(type)) {
          containerName = "Bundle";
        } else {
          containerName = formatContainerType(type);
        }
      }
      sb.append(containerName);
    }
    return sb.toString();
  }

  private String formatContainerNameWithoutSlot(ContainerNode ref) {
    StringBuilder sb = new StringBuilder();

    if (ref.getColor() != null && !ref.getColor().isBlank()) {
      sb.append(capitalize(ref.getColor())).append(" ");
    }

    sb.append(formatContainerType(ref.getContainerType()));

    if (ref.getCustomName() != null && !ref.getCustomName().isBlank()) {
      sb.append(" (\"").append(ref.getCustomName()).append("\")");
    }

    return sb.toString();
  }

  private String formatContainerType(String type) {
    if (type == null) return "Container";
    String[] parts = type.split("_");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) sb.append(" ");
      if (!parts[i].isEmpty()) {
        sb.append(capitalize(parts[i]));
      }
    }
    return sb.toString();
  }

  private String capitalize(String s) {
    if (s == null || s.isEmpty()) return s;
    return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
  }

  private int countItems(SearchResultTreeNode node) {
    int count = 0;
    for (SearchResultTreeNode child : node.getChildren()) {
      if (child.getType() == SearchResultTreeNode.NodeType.ITEM) {
        count++;
      } else if (child.getType() == SearchResultTreeNode.NodeType.CONTAINER) {
        count += countItems(child);
      }
    }
    return count;
  }
}
