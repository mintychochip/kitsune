package dev.jlo.kitsune.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.jlo.kitsune.embedding.SparseEmbedding;

class ModelImmutabilityTest {

    @Test
    void modelConstructorsDefensivelyCopyInputs() {
        List<ItemPathStep> steps = new ArrayList<>(List.of(new ItemPathStep("Chest", 4)));
        byte[] fingerprint = new byte[]{1, 2, 3};
        Map<String, Double> values = new HashMap<>(Map.of("token:diamond", 1.0));
        ItemPath path = new ItemPath(steps);
        ContainerSnapshot snapshot = new ContainerSnapshot(
            new BlockKey(UUID.randomUUID(), 0, 64, 0), "minecraft:chest", fingerprint, List.of());
        SparseEmbedding vector = SparseEmbedding.of("builtin:sparse-v1", 1, values);

        steps.clear();
        fingerprint[0] = 9;
        values.clear();

        assertEquals(1, path.steps().size());
        assertArrayEquals(new byte[]{1, 2, 3}, snapshot.fingerprint());
        assertEquals(1, vector.values().size());
        assertThrows(UnsupportedOperationException.class,
            () -> vector.values().put("token:mutate", 1.0));
    }

    @Test
    void itemDescriptorIsImmutableAfterBuild() {
        var builder = ItemDescriptor.builder()
            .materialKey("minecraft:stone")
            .amount(5)
            .addDisplayText("Stone")
            .addLore("A rock")
            .addEnchantment("minecraft:unbreaking", 3)
            .addAttribute("generic.armor", 2.0)
            .addTrait("solid")
            .addScalarMetadata("hardness", "1.5")
            .addCustomTag("tag1");
        var descriptor = builder.build();

        builder.addTrait("newtrait");
        assertEquals(Set.of("solid"), descriptor.traits());

        assertThrows(UnsupportedOperationException.class, () -> descriptor.displayText().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.lore().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.enchantments().put("k", 1));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.attributes().put("k", 1.0));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.traits().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.scalarMetadata().put("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.customTags().add("x"));
    }

    @Test
    void itemDescriptorEqualsIgnoresInsertionOrder() {
        var a = ItemDescriptor.builder().materialKey("m").amount(1).addTrait("b").addTrait("a").build();
        var b = ItemDescriptor.builder().materialKey("m").amount(1).addTrait("a").addTrait("b").build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void itemDescriptorSortedCollections() {
        var descriptor = ItemDescriptor.builder().materialKey("m").amount(1)
            .addDisplayText("z").addDisplayText("a")
            .addLore("l2").addLore("l1")
            .addEnchantment("e2", 2).addEnchantment("e1", 1)
            .addAttribute("a2", 2.0).addAttribute("a1", 1.0)
            .addTrait("t2").addTrait("t1")
            .addScalarMetadata("k2", "v2").addScalarMetadata("k1", "v1")
            .addCustomTag("c2").addCustomTag("c1")
            .build();
        assertEquals(List.of("a", "z"), descriptor.displayText());
        assertEquals(List.of("l1", "l2"), descriptor.lore());
        assertEquals(List.of("e1", "e2"), new ArrayList<>(descriptor.enchantments().keySet()));
        assertEquals(List.of("a1", "a2"), new ArrayList<>(descriptor.attributes().keySet()));
        assertEquals(List.of("t1", "t2"), descriptor.traits().stream().toList());
        assertEquals(List.of("k1", "k2"), new ArrayList<>(descriptor.scalarMetadata().keySet()));
        assertEquals(List.of("c1", "c2"), descriptor.customTags().stream().toList());
    }

    @Test
    void itemDescriptorValidatesInputs() {
        var builder = ItemDescriptor.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.materialKey(null));
        assertThrows(IllegalArgumentException.class, () -> builder.materialKey(""));
        assertThrows(IllegalArgumentException.class, () -> builder.amount(0));
        assertThrows(IllegalArgumentException.class, () -> builder.addEnchantment(null, 1));
        assertThrows(IllegalArgumentException.class, () -> builder.addEnchantment("", 1));
        assertThrows(IllegalArgumentException.class, () -> builder.addEnchantment("k", 0));
        assertThrows(IllegalArgumentException.class, () -> builder.addAttribute(null, 1.0));
        assertThrows(IllegalArgumentException.class, () -> builder.addAttribute("", 1.0));
        assertThrows(IllegalArgumentException.class, () -> builder.addAttribute("k", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> builder.addTrait(null));
        assertThrows(IllegalArgumentException.class, () -> builder.addTrait(""));
        assertThrows(IllegalArgumentException.class, () -> builder.addScalarMetadata(null, "v"));
        assertThrows(IllegalArgumentException.class, () -> builder.addScalarMetadata("", "v"));
        assertThrows(IllegalArgumentException.class, () -> builder.addCustomTag(null));
        assertThrows(IllegalArgumentException.class, () -> builder.addCustomTag(""));
    }

    @Test
    void blockKeyRenamesWorldIdAndAllowsNegativeY() {
        var key = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        assertEquals(new ChunkKey(key.worldId(), 0, 0), key.chunkKey());
        var negY = new BlockKey(UUID.randomUUID(), 0, -1, 0);
        assertEquals(new ChunkKey(negY.worldId(), 0, 0), negY.chunkKey());
    }

    @Test
    void chunkKeyRenamesWorldIdAndFloorsNegativeCoords() {
        var uuid = UUID.randomUUID();
        assertEquals(new ChunkKey(uuid, -1, -1), new ChunkKey(uuid, -1, -1));
        var key = new BlockKey(uuid, -17, 0, -17);
        assertEquals(new ChunkKey(uuid, -2, -2), key.chunkKey());
    }

    @Test
    void rootIdentityRenamesKeyAccessor() {
        var key = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        byte[] fp = new byte[]{1};
        var id = new RootIdentity(key, "minecraft:chest", fp, 1);
        assertSame(key, id.key());
    }

    @Test
    void rootIdentityStructuralEquals() {
        var key = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var a = new RootIdentity(key, "type", new byte[]{1}, 1);
        var b = new RootIdentity(key, "type", new byte[]{1}, 1);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void containerDraftStructuralEquals() {
        var key = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var a = new ContainerDraft(key, "type", new byte[]{1}, List.of(new ItemDraft(new ItemPath(List.of(new ItemPathStep("s", 1))), 1, ItemDescriptor.builder().materialKey("m").amount(1).build())));
        var b = new ContainerDraft(key, "type", new byte[]{1}, List.of(new ItemDraft(new ItemPath(List.of(new ItemPathStep("s", 1))), 1, ItemDescriptor.builder().materialKey("m").amount(1).build())));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void containerSnapshotStructuralEquals() {
        var key = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var a = new ContainerSnapshot(key, "type", new byte[]{1}, List.of());
        var b = new ContainerSnapshot(key, "type", new byte[]{1}, List.of());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void itemDraftValidatesPositiveAmount() {
        var path = new ItemPath(List.of(new ItemPathStep("s", 1)));
        var descriptor = ItemDescriptor.builder().materialKey("m").amount(1).build();
        assertThrows(IllegalArgumentException.class, () -> new ItemDraft(path, 0, descriptor));
    }

    @Test
    void indexedItemValidatesPositiveAmount() {
        var path = new ItemPath(List.of(new ItemPathStep("s", 1)));
        var descriptor = ItemDescriptor.builder().materialKey("m").amount(1).build();
        assertThrows(IllegalArgumentException.class, () -> new IndexedItem(path, 0, descriptor, SparseEmbedding.of("id", 1, Map.of())));
    }

    @Test
    void blockKeyRejectsNullWorldId() {
        assertThrows(NullPointerException.class, () -> new BlockKey(null, 0, 64, 0));
    }

    @Test
    void chunkKeyAccessorUsesWorldId() {
        var key = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var ck = key.chunkKey();
        assertEquals(key.worldId(), ck.worldId());
    }
}
