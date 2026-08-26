package dev.jlo.kitsune.index;

/**
 * FTS5 document fields projected from an {@link dev.jlo.kitsune.model.ItemDescriptor}.
 *
 * <p>Each component is a space-joined, lowercase token string. Empty fields use {@code ""}.
 */
public record ItemSearchDocument(
    String material, String display, String tags, String enchantments, String lore) {

  public ItemSearchDocument {
    material = material == null ? "" : material;
    display = display == null ? "" : display;
    tags = tags == null ? "" : tags;
    enchantments = enchantments == null ? "" : enchantments;
    lore = lore == null ? "" : lore;
  }
}
