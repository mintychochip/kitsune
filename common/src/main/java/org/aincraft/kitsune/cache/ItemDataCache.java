package org.aincraft.kitsune.cache;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import org.aincraft.kitsune.api.model.ContainerPath;
import org.aincraft.kitsune.api.model.ContainerNode;

/**
 * Cache for extracted item data from JSON fullContent. Avoids redundant JSON parsing of the same
 * item data during search result processing.
 */
public class ItemDataCache {

  private static final Gson GSON = new Gson();

  // Cache key: hash of fullContent string
  // Cache value: ExtractedItemData containing all parsed information
  private final ConcurrentMap<Integer, ExtractedItemData> cache = new ConcurrentHashMap<>();

  /**
   * Extracted item data from JSON to avoid redundant parsing.
   */
  public static class ExtractedItemData {

    private final String displayName;
    private final int slotIndex;
    private final int amount;
    private final ContainerPath containerPath;

    public ExtractedItemData(String displayName, int slotIndex, int amount,
        ContainerPath containerPath) {
      this.displayName = displayName;
      this.slotIndex = slotIndex;
      this.amount = amount;
      this.containerPath = containerPath;
    }

    public String getDisplayName() {
      return displayName;
    }

    public int getSlotIndex() {
      return slotIndex;
    }

    public int getAmount() {
      return amount;
    }

    public ContainerPath getContainerPath() {
      return containerPath;
    }
  }

  // TODO: PERF - Hash collision risk with hashCode() as cache key
  // Current: Different JSON strings can produce same hashCode, causing wrong cache hits
  // Fix: Use stronger hash (XXH3/MurmurHash3) or include more distinguishing data
  /**
   * Get cached item data or extract and cache it.
   */
  public ExtractedItemData getOrExtract(String fullContent, Logger logger) {
    if (fullContent == null || fullContent.isEmpty()) {
      return new ExtractedItemData("Unknown Item", -1, 1, ContainerPath.ROOT);
    }

    int key = fullContent.hashCode();
    return cache.computeIfAbsent(key, k -> extractItemData(fullContent, logger));
  }

  /**
   * Extract item data from JSON content.
   * Supports both JsonObject and JsonArray (with single element) formats.
   */
  private ExtractedItemData extractItemData(String jsonContent, Logger logger) {
    try {
      JsonElement elem = GSON.fromJson(jsonContent, JsonElement.class);
      JsonObject itemObj = null;

      if (elem.isJsonArray()) {
        JsonArray jsonArray = elem.getAsJsonArray();
        if (jsonArray.size() > 0) {
          itemObj = jsonArray.get(0).getAsJsonObject();
        }
      } else if (elem.isJsonObject()) {
        itemObj = elem.getAsJsonObject();
      }

      if (itemObj != null) {
        // Extract display name
        String displayName = extractDisplayName(itemObj);

        // Extract slot index
        int slotIndex = extractSlotIndex(itemObj);

        // Extract amount
        int amount = extractAmount(itemObj);

        // Extract container path
        ContainerPath containerPath = extractContainerPath(itemObj, logger);

        return new ExtractedItemData(displayName, slotIndex, amount, containerPath);
      }
    } catch (Exception e) {
      if (logger != null) {
        logger.warning("extractItemData: failed to parse JSON: " + e.getMessage());
      }
    }

    return new ExtractedItemData("Unknown Item", -1, 1, ContainerPath.ROOT);
  }

  private String extractDisplayName(JsonObject itemObj) {
    if (itemObj.has("display_name")) {
      String displayName = itemObj.get("display_name").getAsString();
      if (displayName != null && !displayName.isEmpty()) {
        return displayName;
      }
    }
    if (itemObj.has("name")) {
      String name = itemObj.get("name").getAsString();
      if (name != null && !name.isEmpty()) {
        return name;
      }
    }
    if (itemObj.has("material")) {
      String material = itemObj.get("material").getAsString();
      return formatMaterialName(material);
    }
    return "Unknown Item";
  }

  private int extractSlotIndex(JsonObject itemObj) {
    if (itemObj.has("slot")) {
      return itemObj.get("slot").getAsInt();
    }
    return -1;
  }

  private int extractAmount(JsonObject itemObj) {
    if (itemObj.has("amount")) {
      int amount = itemObj.get("amount").getAsInt();
      return Math.max(amount, 1);
    }
    return 1;
  }

  private ContainerPath extractContainerPath(JsonObject itemObj, Logger logger) {
    if (itemObj.has("container_path")) {
      var pathElement = itemObj.get("container_path");

      // Handle string format (legacy)
      if (pathElement.isJsonPrimitive() && pathElement.getAsJsonPrimitive().isString()) {
        String pathString = pathElement.getAsString();
        if (pathString.isEmpty()) {
          return ContainerPath.ROOT;
        }
        return parseLegacyContainerPath(pathString);
      }

      // Handle JSON array format
      String pathJson = pathElement.toString();
      try {
        return ContainerPath.fromJson(pathJson);
      } catch (Exception e) {
        if (logger != null) {
          logger.warning("extractContainerPath: failed to parse container path: " + e.getMessage());
        }
        return ContainerPath.ROOT;
      }
    }
    return ContainerPath.ROOT;
  }

  private ContainerPath parseLegacyContainerPath(String pathString) {
    String[] parts = pathString.split(" → ");
    java.util.List<ContainerNode> refs = new java.util.ArrayList<>();

    for (int i = 0; i < parts.length; i++) {
      String part = parts[i].trim();

      String containerType = "container";
      String color = null;
      String customName = null;

      if (part.toLowerCase().contains("shulker") || part.toLowerCase().contains("shulker box")) {
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

  private static String formatMaterialName(String material) {
      if (material == null) {
          return "Unknown Item";
      }

    // Convert DIAMOND_PICKAXE to Diamond Pickaxe
    String[] words = material.toLowerCase().split("_");
    StringBuilder sb = new StringBuilder();
    for (String word : words) {
      if (!word.isEmpty()) {
          if (sb.length() > 0) {
              sb.append(" ");
          }
        sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
      }
    }
    return sb.toString();
  }

  /**
   * Clear all cached item data.
   */
  public void clear() {
    cache.clear();
  }

  /**
   * Get cache size.
   */
  public int size() {
    return cache.size();
  }
}