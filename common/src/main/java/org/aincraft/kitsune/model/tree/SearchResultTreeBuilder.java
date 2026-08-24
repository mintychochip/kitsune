package org.aincraft.kitsune.model.tree;

import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.aincraft.kitsune.Block;
import org.aincraft.kitsune.Inventory;
import org.aincraft.kitsune.Location;
import org.aincraft.kitsune.api.indexing.ItemLoader;
import org.aincraft.kitsune.api.model.ContainerPath;
import org.aincraft.kitsune.api.model.ContainerNode;
import org.aincraft.kitsune.Item;
import org.aincraft.kitsune.cache.ItemDataCache;
import org.aincraft.kitsune.model.SearchResult;
import org.aincraft.kitsune.util.ItemDataExtractor;
import org.jetbrains.annotations.Nullable;

public final class SearchResultTreeBuilder {

  private static final Gson GSON = new Gson();

  private SearchResultTreeBuilder() {}

  public static List<SearchResultTreeNode> buildTree(List<SearchResult> results, Logger logger) {
    return buildTree(results, logger, null, null);
  }

  public static List<SearchResultTreeNode> buildTree(
      List<SearchResult> results,
      Logger logger,
      @Nullable ItemLoader itemLoader) {
    return buildTree(results, logger, itemLoader, null);
  }

  public static List<SearchResultTreeNode> buildTree(
      List<SearchResult> results,
      Logger logger,
      @Nullable ItemLoader itemLoader,
      @Nullable ItemDataCache itemCache) {
    Preconditions.checkNotNull(results, "Results list cannot be null");

    LinkedHashMap<String, List<SearchResult>> locationGroups = new LinkedHashMap<>();
    for (SearchResult result : results) {
      String locationKey = buildLocationKey(result.location());
      locationGroups.computeIfAbsent(locationKey, k -> new ArrayList<>()).add(result);
    }

    List<SearchResultTreeNode> locationNodes = new ArrayList<>();
    for (List<SearchResult> resultsAtLocation : locationGroups.values()) {
      locationNodes.add(buildLocationNode(resultsAtLocation, logger, itemLoader, itemCache));
    }
    return locationNodes;
  }

  private static String buildLocationKey(Location location) {
    return String.format("%s:%d,%d,%d",
        location.getWorld().getName(),
        location.blockX(),
        location.blockY(),
        location.blockZ());
  }

  private static SearchResultTreeNode buildLocationNode(
      List<SearchResult> resultsAtLocation,
      Logger logger,
      @Nullable ItemLoader itemLoader,
      @Nullable ItemDataCache itemCache) {
    Preconditions.checkArgument(!resultsAtLocation.isEmpty(), "Results at location cannot be empty");

    SearchResult firstResult = resultsAtLocation.get(0);
    Location loc = firstResult.location();
    String containerType = loc.getBlock() != null ? loc.getBlock().type() : null;
    SearchResultTreeNode locationNode = SearchResultTreeNode.locationNode(loc, containerType);

    Map<Integer, Integer> containerScores = new HashMap<>();
    List<SearchResult> itemsToProcess = new ArrayList<>();

    for (SearchResult result : resultsAtLocation) {
      ContainerPath containerPath = result.containerPath();
      if (containerPath == null || containerPath.isRoot()) {
        if (isContainerType(result.preview())) {
          boolean hasChildResults = resultsAtLocation.stream()
              .anyMatch(r -> r.containerPath() != null &&
                  !r.containerPath().isRoot() &&
                  !r.containerPath().containerRefs().isEmpty() &&
                  r.containerPath().containerRefs().get(0).getSlotIndex() == extractSlotIndex(result, itemCache));
          if (hasChildResults) {
            containerScores.put(extractSlotIndex(result, itemCache), (int) Math.round(result.score() * 100));
            continue;
          }
        }
        itemsToProcess.add(result);
      } else {
        itemsToProcess.add(result);
      }
    }

    Map<String, SearchResultTreeNode> containerCache = new HashMap<>();

    for (SearchResult result : itemsToProcess) {
      ContainerPath containerPath = result.containerPath();
      int scorePercent = (int) Math.round(result.score() * 100);

      if (containerPath == null || containerPath.isRoot()) {
        ItemDataCache.ExtractedItemData itemData = itemCache != null
            ? itemCache.getOrExtract(result.fullContent(), logger)
            : extractItemDataLegacy(result.fullContent(), logger);

        int slotIndex = itemData.getSlotIndex();
        int amount = itemData.getAmount();
        Item item = null;

        Block block = loc.getBlock();
        if (!block.isAir()) {
          Inventory inventory = block.inventory();
          if (inventory != null) {
            item = inventory.getItem(slotIndex);
          }
        }

        if (itemLoader != null) {
          item = itemLoader.loadItem(loc, slotIndex, containerPath);
        }

        ItemResultData itemResultData = ItemResultData.ofRootItem(
            itemData.getDisplayName(), slotIndex, amount, scorePercent, item);
        locationNode.addChild(SearchResultTreeNode.itemNode(itemResultData));
      } else {
        SearchResultTreeNode parentNode = locationNode;
        List<ContainerNode> containerRefs = containerPath.containerRefs();

        for (int i = 0; i < containerRefs.size(); i++) {
          ContainerNode ref = containerRefs.get(i);
          String cacheKey = buildCacheKey(containerRefs, i);
          SearchResultTreeNode containerNode = containerCache.get(cacheKey);

          if (containerNode == null) {
            Integer score = (i == 0) ? containerScores.get(ref.getSlotIndex()) : null;
            containerNode = score != null
                ? SearchResultTreeNode.containerNodeWithScore(ref, score)
                : SearchResultTreeNode.containerNode(ref);
            containerCache.put(cacheKey, containerNode);
            parentNode.addChild(containerNode);
          }
          parentNode = containerNode;
        }

        ItemDataCache.ExtractedItemData itemData = itemCache != null
            ? itemCache.getOrExtract(result.fullContent(), logger)
            : extractItemDataLegacy(result.fullContent(), logger);

        int slotIndex = itemData.getSlotIndex();
        int amount = itemData.getAmount();
        Item item = null;

        if (itemLoader != null) {
          item = itemLoader.loadItem(loc, slotIndex, result.containerPath());
        }

        ItemResultData itemResultData = ItemResultData.ofNestedItem(
            itemData.getDisplayName(), slotIndex, amount, scorePercent, result.containerPath(), item);
        parentNode.addChild(SearchResultTreeNode.itemNode(itemResultData));
      }
    }

    return locationNode;
  }

  private static boolean isContainerType(String displayName) {
    if (displayName == null) return false;
    String lower = displayName.toLowerCase();
    return lower.contains("shulker") || lower.contains("bundle");
  }

  private static int extractSlotIndex(SearchResult result, ItemDataCache itemCache) {
    if (itemCache != null) {
      return itemCache.getOrExtract(result.fullContent(), null).getSlotIndex();
    }
    return ItemDataExtractor.extractSlotIndex(result.fullContent(), null);
  }

  private static ItemDataCache.ExtractedItemData extractItemDataLegacy(String fullContent, Logger logger) {
    if (fullContent == null || fullContent.isEmpty()) {
      return new ItemDataCache.ExtractedItemData("Unknown Item", -1, 1, ContainerPath.ROOT);
    }

    try {
      JsonElement elem = GSON.fromJson(fullContent, JsonElement.class);
      JsonObject itemObj = null;

      if (elem != null) {
        if (elem.isJsonObject()) {
          itemObj = elem.getAsJsonObject();
        } else if (elem.isJsonArray()) {
          JsonArray jsonArray = elem.getAsJsonArray();
          if (jsonArray.size() > 0 && jsonArray.get(0).isJsonObject()) {
            itemObj = jsonArray.get(0).getAsJsonObject();
          }
        }
      }

      if (itemObj != null) {
        String displayName = extractDisplayName(itemObj);
        int slotIndex = extractSlotIndex(itemObj);
        int amount = extractAmount(itemObj);
        ContainerPath containerPath = extractContainerPath(itemObj, logger);
        return new ItemDataCache.ExtractedItemData(displayName, slotIndex, amount, containerPath);
      }
    } catch (Exception e) {
      if (logger != null) logger.warning("extractItemDataLegacy: " + e.getMessage());
    }
    return new ItemDataCache.ExtractedItemData("Unknown Item", -1, 1, ContainerPath.ROOT);
  }

  private static String extractDisplayName(JsonObject itemObj) {
    if (itemObj.has("display_name")) {
      String displayName = itemObj.get("display_name").getAsString();
      if (displayName != null && !displayName.isEmpty()) return displayName;
    }
    if (itemObj.has("name")) {
      String name = itemObj.get("name").getAsString();
      if (name != null && !name.isEmpty()) return name;
    }
    if (itemObj.has("material")) {
      return formatMaterialName(itemObj.get("material").getAsString());
    }
    return "Unknown Item";
  }

  private static int extractSlotIndex(JsonObject itemObj) {
    return itemObj.has("slot") ? itemObj.get("slot").getAsInt() : -1;
  }

  private static int extractAmount(JsonObject itemObj) {
    if (itemObj.has("amount")) {
      int amount = itemObj.get("amount").getAsInt();
      return Math.max(amount, 1);
    }
    return 1;
  }

  private static ContainerPath extractContainerPath(JsonObject itemObj, Logger logger) {
    if (!itemObj.has("container_path")) return ContainerPath.ROOT;

    var pathElement = itemObj.get("container_path");
    if (pathElement.isJsonPrimitive() && pathElement.getAsJsonPrimitive().isString()) {
      String pathString = pathElement.getAsString();
      if (pathString.isEmpty()) return ContainerPath.ROOT;
      return parseLegacyContainerPath(pathString);
    }

    try {
      return ContainerPath.fromJson(pathElement.toString());
    } catch (Exception e) {
      if (logger != null) logger.warning("extractContainerPath: " + e.getMessage());
      return ContainerPath.ROOT;
    }
  }

  private static String formatMaterialName(String material) {
    if (material == null) return "Unknown Item";
    String[] words = material.toLowerCase().split("_");
    StringBuilder sb = new StringBuilder();
    for (String word : words) {
      if (!word.isEmpty()) {
        if (sb.length() > 0) sb.append(" ");
        sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
      }
    }
    return sb.toString();
  }

  private static String buildCacheKey(List<ContainerNode> containerRefs, int upToIndex) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i <= upToIndex; i++) {
      if (i > 0) sb.append(",");
      ContainerNode ref = containerRefs.get(i);
      sb.append(ref.getContainerType()).append("|").append(ref.getSlotIndex());
    }
    return sb.toString();
  }

  private static ContainerPath parseLegacyContainerPath(String pathString) {
    String[] parts = pathString.split(" → ");
    List<ContainerNode> refs = new ArrayList<>();

    for (int i = 0; i < parts.length; i++) {
      String part = parts[i].trim();
      String containerType = "container";
      String color = null;
      String customName = null;

      if (part.toLowerCase().contains("shulker_box") || part.toLowerCase().contains("shulker box")) {
        containerType = "shulker_box";
        String lowerPart = part.toLowerCase();
        int shulkerIdx = lowerPart.indexOf("shulker");
        if (shulkerIdx > 0) {
          color = part.substring(0, shulkerIdx).trim().toLowerCase();
        }
      } else if (part.toLowerCase().contains("bundle")) {
        containerType = "bundle";
      } else {
        customName = part;
      }
      refs.add(new ContainerNode(containerType, color, customName, i, null, null));
    }
    return new ContainerPath(refs);
  }
}
