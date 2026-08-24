package org.aincraft.kitsune.api.serialization;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.aincraft.kitsune.Item;

/**
 * Registry for tag providers.
 * All providers run on all items and self-select based on their logic.
 * Thread-safe for concurrent access.
 */
public final class TagProviderRegistry {

  private final CopyOnWriteArrayList<TagProvider> providers = new CopyOnWriteArrayList<>();

  public static final TagProviderRegistry INSTANCE = new TagProviderRegistry();
  private TagProviderRegistry() {
  }

  /**
   * Register a tag provider.
   *
   * @param provider The tag provider
   * @throws NullPointerException if provider is null
   */
  public void register(TagProvider provider) {
    Objects.requireNonNull(provider, "provider cannot be null");
    providers.add(provider);
  }

  /**
   * Unregister a specific provider.
   *
   * @param provider The provider to remove
   */
  public void unregister(TagProvider provider) {
    Objects.requireNonNull(provider, "provider cannot be null");
    providers.remove(provider);
  }

  /**
   * Collect all tags for an item from all registered providers.
   *
   * @param item The item context
   * @return Set of all collected tags (never null)
   */
  public Set<String> collectTags(Item item) {
    Objects.requireNonNull(item, "item cannot be null");

    Tags tags = new Tags();
    for (var provider : providers) {
      try {
        provider.appendTags(tags, item);
      } catch (Exception e) {
        // Silently skip providers that throw exceptions
        // to prevent one bad provider from breaking indexing
      }
    }
    return tags.toSet();
  }
}
