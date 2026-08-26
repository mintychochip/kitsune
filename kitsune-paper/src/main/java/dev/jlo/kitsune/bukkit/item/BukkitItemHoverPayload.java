package dev.jlo.kitsune.item;

import dev.jlo.kitsune.model.ItemHoverPayload;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.event.HoverEventSource;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Converts Paper's native ItemStack hover payload to and from the neutral index model. */
public final class BukkitItemHoverPayload {
    private BukkitItemHoverPayload() {}

    /**
     * Captures Paper's complete ShowItem payload for a stack.
     *
     * <p>Spigot-only test/runtime stacks do not implement {@link HoverEventSource};
     * those receive a material/count payload rather than a lossy reconstruction.
     */
    public static ItemHoverPayload capture(ItemStack stack) {
        Objects.requireNonNull(stack, "Item stack must not be null");
        if (stack instanceof HoverEventSource<?> source) {
            HoverEvent<?> event = source.asHoverEvent();
            if (event.action() != HoverEvent.Action.SHOW_ITEM
                || !(event.value() instanceof HoverEvent.ShowItem showItem)) {
                throw new IllegalStateException("Paper ItemStack returned a non-item hover event");
            }
            return capture(showItem);
        }
        return ItemHoverPayload.base(
            stack.getType().getKey().toString(),
            stack.getAmount()
        );
    }

    /** Captures every Adventure ShowItem field without interpreting component SNBT. */
    public static ItemHoverPayload capture(HoverEvent.ShowItem showItem) {
        Objects.requireNonNull(showItem, "ShowItem must not be null");
        Map<String, String> components = new LinkedHashMap<>();
        Set<String> removed = new LinkedHashSet<>();

        for (Map.Entry<Key, DataComponentValue> entry : showItem.dataComponents().entrySet()) {
            String key = entry.getKey().asString();
            DataComponentValue value = entry.getValue();
            if (value instanceof DataComponentValue.Removed) {
                removed.add(key);
            } else if (value instanceof DataComponentValue.TagSerializable tag) {
                components.put(key, tag.asBinaryTag().string());
            } else {
                throw new IllegalArgumentException(
                    "Unsupported Adventure data component value for " + key
                );
            }
        }

        return new ItemHoverPayload(
            showItem.item().asString(),
            showItem.count(),
            showItem.nbt() == null ? null : showItem.nbt().string(),
            components,
            removed
        );
    }

    /** Reconstructs an Adventure SHOW_ITEM event from the persisted payload. */
    public static HoverEvent<HoverEvent.ShowItem> toHoverEvent(
        ItemHoverPayload payload
    ) {
        Objects.requireNonNull(payload, "Item hover payload must not be null");
        HoverEvent.ShowItem showItem = HoverEvent.ShowItem.showItem(
            Key.key(payload.itemKey()),
            payload.count()
        );
        if (payload.legacyNbt() != null) {
            showItem = showItem.nbt(
                BinaryTagHolder.binaryTagHolder(payload.legacyNbt())
            );
        }

        if (!payload.dataComponents().isEmpty()
            || !payload.removedDataComponents().isEmpty()) {
            Map<Key, DataComponentValue> components = new LinkedHashMap<>();
            payload.dataComponents().forEach((key, value) -> components.put(
                Key.key(key),
                BinaryTagHolder.binaryTagHolder(value)
            ));
            payload.removedDataComponents().forEach(key -> components.put(
                Key.key(key),
                DataComponentValue.removed()
            ));
            showItem = showItem.dataComponents(components);
        }
        return HoverEvent.showItem(showItem);
    }
}
