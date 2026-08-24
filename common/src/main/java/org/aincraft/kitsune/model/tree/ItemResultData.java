package org.aincraft.kitsune.model.tree;

import com.google.common.base.Preconditions;
import org.aincraft.kitsune.api.model.ContainerPath;
import org.aincraft.kitsune.Item;
import org.jetbrains.annotations.Nullable;

public final class ItemResultData {
    private final String displayName;
    private final int slotIndex;
    private final int amount;
    private final int scorePercent;
    @Nullable private final ContainerPath containerPath;
    @Nullable private final Item item;

    public ItemResultData(
            String displayName,
            int slotIndex,
            int amount,
            int scorePercent,
            @Nullable ContainerPath containerPath,
            @Nullable Item item) {
        this.displayName = Preconditions.checkNotNull(displayName, "Display name cannot be null");
        Preconditions.checkArgument(slotIndex >= 0, "Slot index must be non-negative");
        this.slotIndex = slotIndex;
        Preconditions.checkArgument(amount > 0, "Amount must be positive");
        this.amount = amount;
        Preconditions.checkArgument(scorePercent >= 0 && scorePercent <= 100, "Score percent must be between 0 and 100");
        this.scorePercent = scorePercent;
        this.containerPath = containerPath;
        this.item = item;
    }

    public String getDisplayName() { return displayName; }
    public int getSlotIndex() { return slotIndex; }
    public int getAmount() { return amount; }
    public int getScorePercent() { return scorePercent; }
    @Nullable public ContainerPath getContainerPath() { return containerPath; }
    @Nullable public Item getItemContext() { return item; }

    public static ItemResultData ofRootItem(String displayName, int slotIndex, int amount, int scorePercent) {
        return new ItemResultData(displayName, slotIndex, amount, scorePercent, null, null);
    }

    public static ItemResultData ofRootItem(
            String displayName, int slotIndex, int amount, int scorePercent, @Nullable Item item) {
        return new ItemResultData(displayName, slotIndex, amount, scorePercent, null, item);
    }

    public static ItemResultData ofNestedItem(
            String displayName, int slotIndex, int amount, int scorePercent, ContainerPath containerPath) {
        Preconditions.checkNotNull(containerPath, "Container path cannot be null for nested item");
        return new ItemResultData(displayName, slotIndex, amount, scorePercent, containerPath, null);
    }

    public static ItemResultData ofNestedItem(
            String displayName, int slotIndex, int amount, int scorePercent,
            ContainerPath containerPath, @Nullable Item item) {
        Preconditions.checkNotNull(containerPath, "Container path cannot be null for nested item");
        return new ItemResultData(displayName, slotIndex, amount, scorePercent, containerPath, item);
    }

    @Override
    public String toString() {
        return "ItemResultData{displayName='" + displayName + "', slotIndex=" + slotIndex +
                ", amount=" + amount + ", scorePercent=" + scorePercent + '}';
    }
}
