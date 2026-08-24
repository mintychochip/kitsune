package org.aincraft.kitsune.serialization;

import com.google.common.base.Preconditions;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.aincraft.kitsune.BukkitInventory;
import org.aincraft.kitsune.Inventory;
import org.aincraft.kitsune.Item;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bukkit/Paper implementation of Item. Extracts item data using Paper's DataComponent API
 * (1.21+).
 */
public final class BukkitItem implements Item {

  private final ItemStack itemStack;
  private final Material material;

  public BukkitItem(ItemStack itemStack) {
    this.itemStack = Objects.requireNonNull(itemStack, "itemStack cannot be null");
    this.material = itemStack.getType();
  }

  @Override
  public Map<String, Integer> enchantments() {
    Map<String, Integer> result = new HashMap<>();

    // Regular enchantments
    if (itemStack.hasData(DataComponentTypes.ENCHANTMENTS)) {
      ItemEnchantments enchants = itemStack.getData(DataComponentTypes.ENCHANTMENTS);
      if (enchants != null) {
        for (Map.Entry<Enchantment, Integer> entry : enchants.enchantments().entrySet()) {
          result.put(entry.getKey().getKey().getKey(), entry.getValue());
        }
      }
    }

    // Stored enchantments (enchanted books)
    if (itemStack.hasData(DataComponentTypes.STORED_ENCHANTMENTS)) {
      ItemEnchantments stored = itemStack.getData(DataComponentTypes.STORED_ENCHANTMENTS);
      if (stored != null) {
        for (Map.Entry<Enchantment, Integer> entry : stored.enchantments().entrySet()) {
          result.put(entry.getKey().getKey().getKey(), entry.getValue());
        }
      }
    }

    return result.isEmpty() ? Collections.emptyMap() : result;
  }

  @Override
  public boolean isBlock() {
    return material.isBlock();
  }

  @Override
  public String getDisplayName() {
    if (itemStack.hasData(DataComponentTypes.CUSTOM_NAME)) {
      Component customName = itemStack.getData(DataComponentTypes.CUSTOM_NAME);
      if (customName != null) {
        return PlainTextComponentSerializer.plainText().serialize(customName);
      }
    }
    return formatMaterialName(material.name());
  }

  @Override
  public List<String> getLore() {
    List<Component> components = itemStack.lore();
    if (components == null || components.isEmpty()) {
      return Collections.emptyList();
    }

    List<String> loreLines = new ArrayList<>();
    PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
    for (Component component : components) {
      loreLines.add(serializer.serialize(component));
    }
    return loreLines;
  }

  @Override
  public List<Component> lore() {
    return itemStack.lore();
  }

  @Override
  public int amount() {
    return itemStack.getAmount();
  }

  @Override
  public <T> T unwrap(Class<T> expectedType) {
    Preconditions.checkArgument(ItemStack.class.equals(expectedType));
    return expectedType.cast(itemStack);
  }

  @Override
  public boolean isUnbreakable() {
    return itemStack.hasData(DataComponentTypes.UNBREAKABLE);
  }

  @Override
  public boolean isSolid() {
    return material.isSolid();
  }

  @Override
  public boolean isOccluding() {
    return material.isOccluding();
  }

  @Override
  public boolean hasGravity() {
    return material.hasGravity();
  }

  @Override
  public String material() {
    return itemStack.getType().name();
  }

  @Override
  public boolean hasShulkerContents() {
    return itemStack.hasData(DataComponentTypes.CONTAINER);
  }

  @Override
  public boolean hasBundle() {
    return itemStack.hasData(DataComponentTypes.BUNDLE_CONTENTS);
  }

  @Override
  public HoverEvent<?> asHoverEvent() {
    return itemStack.asHoverEvent();
  }

  @Override
  public DurabilityInfo getDurability() {
    if (!itemStack.hasData(DataComponentTypes.MAX_DAMAGE)) {
      return null;
    }

    int maxDamage = itemStack.getData(DataComponentTypes.MAX_DAMAGE);
    int damage = itemStack.getDataOrDefault(DataComponentTypes.DAMAGE, 0);
    int percent = (int) ((1.0 - (double) damage / maxDamage) * 100);

    return new DurabilityInfo(damage, maxDamage, percent);
  }

  @Override
  public String getRarity() {
    if (itemStack.hasData(DataComponentTypes.RARITY)) {
      ItemRarity rarity = itemStack.getData(DataComponentTypes.RARITY);
      if (rarity != null) {
        return rarity.name().toLowerCase();
      }
    }
    return null;
  }

  @Override
  public String getCreativeCategory() {
    var creativeCategory = material.getCreativeCategory();
    if (creativeCategory != null) {
      return creativeCategory.name().toLowerCase().replace("_", " ");
    }
    return null;
  }

  @Override
  public List<?> getBundleContents() {
    if (itemStack.hasData(DataComponentTypes.BUNDLE_CONTENTS)) {
      BundleContents bundle = itemStack.getData(DataComponentTypes.BUNDLE_CONTENTS);
      if (bundle != null && !bundle.contents().isEmpty()) {
        return new ArrayList<>(bundle.contents());
      }
    }
    return Collections.emptyList();
  }

  @Override
  public List<?> getContainerContents() {
    if (itemStack.hasData(DataComponentTypes.CONTAINER)) {
      ItemContainerContents container = itemStack.getData(DataComponentTypes.CONTAINER);
      if (container != null && !container.contents().isEmpty()) {
        return new ArrayList<>(container.contents());
      }
    }
    return Collections.emptyList();
  }

  @Override
  public String getContainerType() {
    if (itemStack.hasData(DataComponentTypes.CONTAINER)) {
      return getContainerTypeFromMaterial(material);
    }
    if (itemStack.hasData(DataComponentTypes.BUNDLE_CONTENTS)) {
      return "bundle";
    }
    return null;
  }

  /**
   * Format material name from UPPER_CASE to Title Case.
   */
  private String formatMaterialName(String materialName) {
    String[] words = materialName.toLowerCase().split("_");
    StringBuilder result = new StringBuilder();
    for (String word : words) {
      if (!result.isEmpty()) {
        result.append(" ");
      }
      if (!word.isEmpty()) {
        result.append(Character.toUpperCase(word.charAt(0)))
            .append(word.substring(1));
      }
    }
    return result.toString();
  }

  /**
   * Get container type string from material.
   */
  private String getContainerTypeFromMaterial(Material material) {
    String materialName = material.name();

    if (materialName.contains("SHULKER_BOX")) {
      return "shulker_box";
    }

    return switch (materialName) {
      case "CHEST", "TRAPPED_CHEST" -> "chest";
      case "BARREL" -> "barrel";
      case "HOPPER" -> "hopper";
      case "DISPENSER" -> "dispenser";
      case "DROPPER" -> "dropper";
      case "FURNACE", "BLAST_FURNACE", "SMOKER" -> "furnace";
      case "BREWING_STAND" -> "brewing_stand";
      default -> materialName.toLowerCase();
    };
  }

  @Override
  public String getCustomName() {
    if (itemStack.hasData(DataComponentTypes.CUSTOM_NAME)) {
      Component customName = itemStack.getData(DataComponentTypes.CUSTOM_NAME);
      if (customName != null) {
        return PlainTextComponentSerializer.plainText().serialize(customName);
      }
    }
    return null;
  }

  @Override
  public String getCustomNameSerialized() {
    if (itemStack.hasData(DataComponentTypes.CUSTOM_NAME)) {
      Component customName = itemStack.getData(DataComponentTypes.CUSTOM_NAME);
      if (customName != null) {
        return MiniMessage.miniMessage().serialize(customName);
      }
    }
    return null;
  }

  @Override
  public Optional<Inventory> getInventory() {
    // Check for shulker/container contents
    if (itemStack.hasData(DataComponentTypes.CONTAINER)) {
      ItemContainerContents container = itemStack.getData(DataComponentTypes.CONTAINER);
      if (container != null && !container.contents().isEmpty()) {
        return Optional.of(BukkitInventory.from(new ArrayList<>(container.contents())));
      }
    }
    // Check for bundle contents
    if (itemStack.hasData(DataComponentTypes.BUNDLE_CONTENTS)) {
      BundleContents bundle = itemStack.getData(DataComponentTypes.BUNDLE_CONTENTS);
      if (bundle != null && !bundle.contents().isEmpty()) {
        return Optional.of(BukkitInventory.from(new ArrayList<>(bundle.contents())));
      }
    }
    return Optional.empty();
  }
}
