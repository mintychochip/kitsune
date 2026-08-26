package dev.jlo.kitsune.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jlo.kitsune.model.ItemDescriptor;
import org.junit.jupiter.api.Test;

public final class ItemSearchProjectorTest {
  private static final ItemDescriptor DESCRIPTOR =
      ItemDescriptor.builder()
          .materialKey("minecraft:oak_planks")
          .amount(1)
          .addDisplayText("Oak Planks")
          .addLore("A wooden board")
          .addEnchantment("minecraft:mending", 1)
          .addTrait("building")
          .addCustomTag("planks")
          .build();

  @Test
  void projectsMaterial() {
    String material = ItemSearchProjector.project(DESCRIPTOR).material();
    assertTrue(material.contains("minecraft"), "material contains 'minecraft': " + material);
    assertTrue(material.contains("oak"), "material contains 'oak': " + material);
    assertTrue(material.contains("planks"), "material contains 'planks': " + material);
  }

  @Test
  void projectsDisplay() {
    String display = ItemSearchProjector.project(DESCRIPTOR).display();
    assertTrue(display.contains("oak"), "display contains 'oak': " + display);
    assertTrue(display.contains("planks"), "display contains 'planks': " + display);
  }

  @Test
  void projectsLore() {
    String lore = ItemSearchProjector.project(DESCRIPTOR).lore();
    assertTrue(lore.contains("wooden"), "lore contains 'wooden': " + lore);
    assertTrue(lore.contains("board"), "lore contains 'board': " + lore);
  }

  @Test
  void projectsEnchantments() {
    String enchantments = ItemSearchProjector.project(DESCRIPTOR).enchantments();
    assertTrue(
        enchantments.contains("minecraft"), "enchantments contains 'minecraft': " + enchantments);
    assertTrue(
        enchantments.contains("mending"), "enchantments contains 'mending': " + enchantments);
  }

  @Test
  void projectsTags() {
    String tags = ItemSearchProjector.project(DESCRIPTOR).tags();
    assertTrue(tags.contains("building"), "tags contains 'building': " + tags);
    assertTrue(tags.contains("planks"), "tags contains 'planks': " + tags);
    assertTrue(tags.contains("durability"), "tags contains alias 'durability': " + tags);
  }

  @Test
  void equalDescriptorsYieldEqualDocuments() {
    ItemDescriptor copy =
        ItemDescriptor.builder()
            .materialKey("minecraft:oak_planks")
            .amount(1)
            .addDisplayText("Oak Planks")
            .addLore("A wooden board")
            .addEnchantment("minecraft:mending", 1)
            .addTrait("building")
            .addCustomTag("planks")
            .build();
    assertEquals(ItemSearchProjector.project(DESCRIPTOR), ItemSearchProjector.project(copy));
  }
}
