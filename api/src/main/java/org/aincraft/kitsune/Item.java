package org.aincraft.kitsune;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;

public interface Item {

  /**
   * Durability information record for items with max damage. Percent is a value from 0-100
   * representing remaining durability percentage.
   */
  record DurabilityInfo(int current, int max, int percent) {

  }

  String getDisplayName();

  List<String> getLore();

  List<Component> lore();

  int amount();

  /**
   * Unwrap the platform-specific item for advanced access. TagProviders can use this when they need
   * platform-specific features. Use this to access all data components with full type safety.
   * Runtime validation ensures the returned object is of the expected type.
   *
   * @param <T> The expected type
   * @param expectedType The class of the expected type
   * @return The underlying platform item cast to the expected type
   * @throws ClassCastException if the item is not an instance of the expected type
   */
  <T> T unwrap(Class<T> expectedType) throws IllegalArgumentException;

  /**
   * Check if this item is unbreakable.
   *
   * @return true if the unbreakable flag is set, false otherwise
   */
  boolean isUnbreakable();

  /**
   * Check if this material has gravity (falls when unsupported). Examples: sand, gravel, anvil.
   *
   * @return true if has gravity, false otherwise
   */
  boolean hasGravity();

  /**
   * Check if this block is solid (occupies full space, can be built upon). Only meaningful for
   * block materials.
   *
   * @return true if the block is solid, false otherwise or if not a block
   */
  boolean isSolid();

  /**
   * Check if this is a block type.
   */
  boolean isBlock();

  /**
   * Get enchantments as name -> level map. Keys are enchantment key names (e.g., "sharpness",
   * "efficiency").
   *
   * @return Map of enchantment keys to levels (never null)
   */
  Map<String, Integer> enchantments();

  /**
   * Check if this block will occlude other blocks (prevents light/rendering). Only meaningful for
   * block materials.
   *
   * @return true if occluding, false otherwise or if not a block
   */
  boolean isOccluding();

  /**
   * Get the material/type name (e.g., "DIAMOND_SWORD").
   */
  String material();

  /**
   * Check if this item has shulker box contents.
   */
  boolean hasShulkerContents();

  /**
   * Check if this item has bundle contents.
   */
  boolean hasBundle();

  /**
   * Create a hover event showing this item's information. Platform implementations provide rich
   * item display with enchants, lore, etc.
   *
   * @return HoverEvent for this item
   */
  HoverEvent<?> asHoverEvent();

  /**
   * Get durability information for this item. Returns null if the item is not damageable.
   *
   * @return DurabilityInfo with current damage, max damage, and percent remaining, or null
   */
  DurabilityInfo getDurability();

  /**
   * Get the rarity of this item. Returns the rarity name in lowercase (e.g., "common", "uncommon",
   * "rare", "epic").
   *
   * @return Rarity name lowercase, or null if no rarity
   */
  String getRarity();

  /**
   * Get the creative category for this item. Only applicable for items with a defined creative
   * category.
   *
   * @return Category name lowercase, or null if no category
   */
  String getCreativeCategory();

  /**
   * Get items contained in a bundle. Returns an empty list if this is not a bundle or has no
   * contents.
   *
   * @return List of items in bundle (never null)
   */
  List<?> getBundleContents();

  /**
   * Get items contained in a container (shulker, chest, etc.). Returns an empty list if not a
   * container or has no contents.
   *
   * @return List of items in container (never null)
   */
  List<?> getContainerContents();

  /**
   * Get the container type string if this item is a container. Examples: "shulker_box", "bundle",
   * "chest", "barrel", "hopper".
   *
   * @return Container type string, or null if not a container
   */
  String getContainerType();

  /**
   * Get custom name as plain text (MiniMessage formatting removed). Returns null if item has no
   * custom name.
   *
   * @return Plain text custom name, or null if none
   */
  String getCustomName();

  /**
   * Get custom name as MiniMessage serialized string. Returns null if item has no custom name. Use
   * this for storing the original formatted custom name.
   *
   * @return MiniMessage serialized custom name, or null if none
   */
  String getCustomNameSerialized();

  Optional<Inventory> getInventory();
}
