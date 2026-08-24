package org.aincraft.kitsune.serialization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.aincraft.kitsune.Item;
import org.aincraft.kitsune.api.indexing.SerializedItem;
import org.aincraft.kitsune.api.model.ContainerNode;
import org.aincraft.kitsune.api.serialization.TagProviderRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Platform-agnostic item serialization logic.
 * Uses ItemAdapter to work with any platform's item type.
 */
public class ItemSerializationLogic {
    private final TagProviderRegistry tagRegistry;
    private final Gson gson;

    public ItemSerializationLogic(TagProviderRegistry tagRegistry) {
        this.tagRegistry = tagRegistry;
        this.gson = new GsonBuilder().disableHtmlEscaping().create();
    }

    /**
     * Serialize a list of items to SerializedItem records.
     */
    public <T> List<SerializedItem> serialize(ItemAdapter<T> adapter, List<T> items) {
        List<SerializedItem> result = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            if (adapter.isEmpty(item)) {
                continue;
            }

            Item wrapped = adapter.toItem(item);
            SerializedItem serialized = serializeItem(wrapped, i);
            result.add(serialized);
        }

        return result;
    }

    /**
     * Serialize items into a tree structure for nested containers.
     */
    public <T> ContainerNode serializeTree(ItemAdapter<T> adapter, List<T> items) {
        List<SerializedItem> serializedItems = new ArrayList<>();
        List<ContainerNode> children = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            if (adapter.isEmpty(item)) {
                continue;
            }

            Item wrapped = adapter.toItem(item);

            // Check for nested container
            if (wrapped.hasShulkerContents() || wrapped.hasBundle()) {
                ContainerNode childNode = serializeNestedContainer(wrapped, i);
                children.add(childNode);
            } else {
                SerializedItem serialized = serializeItem(wrapped, i);
                serializedItems.add(serialized);
            }
        }

        return new ContainerNode("inventory", null, null, 0, children, serializedItems);
    }

    /**
     * Serialize a nested container (shulker, bundle, etc.).
     */
    private ContainerNode serializeNestedContainer(Item item, int slotIndex) {
        String containerType = item.getContainerType();
        if (containerType == null) {
            containerType = "unknown";
        }

        List<SerializedItem> nestedItems = new ArrayList<>();
        List<?> contents = item.hasBundle() ? item.getBundleContents() : item.getContainerContents();

        for (Object obj : contents) {
            if (obj instanceof Item nestedItem) {
                SerializedItem serialized = serializeItem(nestedItem, nestedItems.size());
                nestedItems.add(serialized);
            }
        }

        return new ContainerNode(containerType, null, item.getCustomName(), slotIndex, nestedItems);
    }

    /**
     * Serialize a single item.
     */
    private SerializedItem serializeItem(Item item, int slotIndex) {
        String embeddingText = buildEmbeddingText(item);
        String storageJson = buildStorageJson(item, slotIndex);
        return new SerializedItem(embeddingText, storageJson);
    }

    /**
     * Build plain text for embedding.
     */
    private String buildEmbeddingText(Item item) {
        StringBuilder sb = new StringBuilder();

        sb.append(item.amount()).append("x ").append(item.getDisplayName());

        // Add material type
        sb.append(" (").append(formatMaterial(item.material())).append(")");

        // Add enchantments
        var enchants = item.enchantments();
        if (!enchants.isEmpty()) {
            sb.append(" enchanted with ");
            List<String> enchantNames = new ArrayList<>();
            enchants.forEach((name, level) -> enchantNames.add(name + " " + level));
            sb.append(String.join(", ", enchantNames));
        }

        // Add tags from providers
        if (tagRegistry != null) {
            Set<String> tags = tagRegistry.collectTags(item);
            if (!tags.isEmpty()) {
                sb.append(" tags: ").append(String.join(" ", tags));
            }
        }

        return sb.toString();
    }

    /**
     * Build JSON for storage.
     */
    private String buildStorageJson(Item item, int slotIndex) {
        JsonObject json = new JsonObject();

        json.addProperty("material", item.material());
        json.addProperty("amount", item.amount());
        json.addProperty("slot", slotIndex);
        json.addProperty("displayName", item.getDisplayName());

        String customName = item.getCustomNameSerialized();
        if (customName != null) {
            json.addProperty("customName", customName);
        }

        // Add enchantments
        var enchants = item.enchantments();
        if (!enchants.isEmpty()) {
            JsonObject enchantsJson = new JsonObject();
            enchants.forEach(enchantsJson::addProperty);
            json.add("enchantments", enchantsJson);
        }

        // Add tags
        if (tagRegistry != null) {
            Set<String> tags = tagRegistry.collectTags(item);
            if (!tags.isEmpty()) {
                JsonArray tagsArray = new JsonArray();
                tags.forEach(tagsArray::add);
                json.add("tags", tagsArray);
            }
        }

        // Add durability if present
        Item.DurabilityInfo durability = item.getDurability();
        if (durability != null) {
            JsonObject durJson = new JsonObject();
            durJson.addProperty("current", durability.current());
            durJson.addProperty("max", durability.max());
            durJson.addProperty("percent", durability.percent());
            json.add("durability", durJson);
        }

        return gson.toJson(json);
    }

    /**
     * Format material name for display.
     */
    private String formatMaterial(String material) {
        return material.toLowerCase().replace("_", " ");
    }
}
