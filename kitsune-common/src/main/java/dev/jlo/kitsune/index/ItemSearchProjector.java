package dev.jlo.kitsune.index;

import dev.jlo.kitsune.embedding.FeatureVocabulary;
import dev.jlo.kitsune.model.ItemDescriptor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Projects {@link ItemDescriptor} instances into FTS5 document fields. */
public final class ItemSearchProjector {
  private ItemSearchProjector() {}

  public static ItemSearchDocument project(ItemDescriptor descriptor) {
    LinkedHashSet<String> material = tokens(descriptor.materialKey());
    LinkedHashSet<String> display = new LinkedHashSet<>();
    descriptor.displayText().forEach(text -> display.addAll(tokens(text)));
    LinkedHashSet<String> lore = new LinkedHashSet<>();
    descriptor.lore().forEach(text -> lore.addAll(tokens(text)));
    LinkedHashSet<String> enchantments = new LinkedHashSet<>();
    descriptor.enchantments().keySet().forEach(key -> enchantments.addAll(tokens(key)));
    descriptor.attributes().keySet().forEach(key -> enchantments.addAll(tokens(key)));
    LinkedHashSet<String> tags = new LinkedHashSet<>();
    descriptor.customTags().forEach(tag -> tags.addAll(tokens(tag)));
    descriptor.traits().forEach(trait -> tags.addAll(tokens(trait)));
    LinkedHashSet<String> all = new LinkedHashSet<>();
    all.addAll(material);
    all.addAll(display);
    all.addAll(lore);
    all.addAll(enchantments);
    all.addAll(tags);
    for (String token : List.copyOf(all)) {
      tags.addAll(FeatureVocabulary.aliasesFor(token));
    }
    return new ItemSearchDocument(
        join(material), join(display), join(tags), join(enchantments), join(lore));
  }

  private static LinkedHashSet<String> tokens(String text) {
    return new LinkedHashSet<>(FeatureVocabulary.tokenize(text));
  }

  private static String join(Set<String> tokens) {
    return String.join(" ", tokens);
  }
}
