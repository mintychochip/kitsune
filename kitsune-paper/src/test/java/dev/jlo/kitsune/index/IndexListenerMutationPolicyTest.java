package dev.jlo.kitsune.index;

import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the click and drag mutation policy used to decide when a player
 * interaction dirties the affected container root in the index.
 */
class IndexListenerMutationPolicyTest {
    /**
     * A shift-click from the player inventory moving items counts as a
     * mutation of the top inventory.
     */
    @Test
    void playerInventoryShiftClickCanMutateTopInventory() {
        assertTrue(IndexListener.clickAffectsTop(
            27,
            27,
            InventoryAction.MOVE_TO_OTHER_INVENTORY
        ));
    }

    /**
     * Clicks confined to the player inventory do not count as mutations of the
     * top inventory.
     */
    @Test
    void playerInventoryOnlyClickDoesNotMutateTopInventory() {
        assertFalse(IndexListener.clickAffectsTop(
            27,
            27,
            InventoryAction.PICKUP_ALL
        ));
        assertFalse(IndexListener.clickAffectsTop(
            27,
            27,
            InventoryAction.HOTBAR_SWAP
        ));
    }

    /**
     * Non-mutating actions on the top inventory do not dirty the root.
     */
    @Test
    void nonMutatingTopClicksDoNotDirtyTheRoot() {
        assertFalse(IndexListener.clickAffectsTop(
            0,
            27,
            InventoryAction.NOTHING
        ));
        assertFalse(IndexListener.clickAffectsTop(
            0,
            27,
            InventoryAction.CLONE_STACK
        ));
    }

    /**
     * Mutations targeting the top inventory dirty the container root.
     */
    @Test
    void topInventoryMutationsDirtyTheRoot() {
        assertTrue(IndexListener.clickAffectsTop(
            26,
            27,
            InventoryAction.PICKUP_ALL
        ));
        assertTrue(IndexListener.clickAffectsTop(
            27,
            27,
            InventoryAction.COLLECT_TO_CURSOR
        ));
    }

    /**
     * A drag dirties the root only when raw slots intersect the top inventory.
     */
    @Test
    void dragOnlyDirtiesWhenRawSlotsIntersectTopInventory() {
        assertTrue(IndexListener.dragAffectsTop(Set.of(4, 28), 27));
        assertFalse(IndexListener.dragAffectsTop(Set.of(27, 28), 27));
    }

    /**
     * Opening an inventory declares no index-dirty handler.
     */
    @Test
    void inventoryMoveAndPickupUseTransferDirtyPath() {
        assertTrue(Arrays.stream(IndexListener.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().equals("onInventoryMoveItem")));
        assertTrue(Arrays.stream(IndexListener.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().equals("markTransferDirty")));
    }

    @Test
    void openingAnInventoryHasNoIndexDirtyHandler() {
        assertFalse(Arrays.stream(IndexListener.class.getDeclaredMethods())
            .anyMatch(method -> Arrays.stream(method.getParameterTypes())
                .anyMatch(InventoryOpenEvent.class::equals)));
    }
}
