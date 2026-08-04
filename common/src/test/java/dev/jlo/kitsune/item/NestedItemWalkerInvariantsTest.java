package dev.jlo.kitsune.item;

import dev.jlo.kitsune.model.ItemDescriptor;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NestedItemWalkerInvariantsTest {

    record TestChild(int slot, TestTree node) {
        TestChild {
            if (slot < 0) throw new IllegalArgumentException("Slot must not be negative");
            Objects.requireNonNull(node, "Node must not be null");
        }

        String label() {
            return node.label;
        }
    }

    static final class TestTree {
        final String label;
        final int amount = 1;
        final ItemDescriptor descriptor;
        final List<TestChild> children;
        final boolean failChildren;
        final boolean nullChildren;
        final byte[] fingerprint;

        private TestTree(String label, List<TestChild> children, boolean failChildren, boolean nullChildren, byte[] fingerprint) {
            this.label = label;
            this.descriptor = ItemDescriptor.builder().materialKey(label).amount(amount).build();
            this.children = List.copyOf(children);
            this.failChildren = failChildren;
            this.nullChildren = nullChildren;
            this.fingerprint = fingerprint;
        }

        static TestTree leaf(String label) {
            return new TestTree(label, List.of(), false, false, digest("leaf-" + label));
        }

        static TestTree container(String label, List<TestChild> children) {
            return new TestTree(label, children, false, false, digest("container-" + label));
        }

        static TestTree failingContainer(String label) {
            return new TestTree(label, List.of(), true, false, digest("container-" + label));
        }

        static TestTree nullChildren(String label) {
            return new TestTree(label, List.of(), false, true, digest("container-" + label));
        }

        static TestTree repeatedFingerprintChain(String label, int length) {
            byte[] fp = digest("repeat-" + label);
            TestTree prev = leaf(label);
            for (int i = 1; i < length; i++) {
                prev = new TestTree(label, List.of(new TestChild(0, prev)), false, false, fp);
            }
            return prev;
        }

        private static byte[] digest(String value) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(value.getBytes(StandardCharsets.UTF_8));
                return md.digest();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static class TypedAdapter implements TraversalAdapter<TestTree> {
        @Override
        public ItemDescriptor describe(TestTree node) {
            return node.descriptor;
        }

        @Override
        public byte[] fingerprint(TestTree node) {
            return node.fingerprint != null ? node.fingerprint : new byte[0];
        }

        @Override
        public List<TraversalChild<TestTree>> children(TestTree node) {
            if (node.failChildren) throw new IllegalStateException("fail");
            if (node.nullChildren) return null;
            List<TraversalChild<TestTree>> children = new ArrayList<>();
            for (var child : node.children) {
                children.add(new TraversalChild<>(node.label, child.slot(), child.node()));
            }
            return children;
        }
    }

    private static NestedItemWalker<TestTree> walker(int maxDepth, int maxStacks) {
        return new NestedItemWalker<>(new TraversalLimits(maxDepth, maxStacks), new TypedAdapter());
    }

    @Test
    void maxStacksOneOnLeafReturnsSingleVisit() {
        TestTree leaf = TestTree.leaf("leaf");
        var result = walker(1, 1).walk(new TraversalChild<>(leaf.label, 0, leaf));
        assertEquals(1, result.visitedStacks());
        assertFalse(result.truncated());
        assertEquals(1, result.leaves().size());
    }

    @Test
    void nullChildrenListEmitsRootAndTruncated() {
        TestTree root = TestTree.nullChildren("box");
        var result = walker(4, 4096).walk(new TraversalChild<>(root.label, 0, root));
        assertEquals(1, result.leaves().size());
        assertTrue(result.truncated());
        assertEquals(root.label, result.leaves().getFirst().descriptor().materialKey());
    }

    @Test
    void listContainingNullEmitsRootAndTruncated() {
        var adapter = new NullEntryAdapter();
        var walker = new NestedItemWalker<>(new TraversalLimits(4, 4096), adapter);
        TestTree root = TestTree.leaf("root");
        var result = walker.walk(new TraversalChild<>(root.label, 0, root));
        assertEquals(1, result.leaves().size());
        assertTrue(result.truncated());
        assertEquals(root.label, result.leaves().getFirst().descriptor().materialKey());
    }

    @Test
    void childFingerprintThrowsEmitsRootAndTruncated() {
        TestTree root = TestTree.container("root", List.of(
            new TestChild(0, TestTree.leaf("good")),
            new TestChild(1, TestTree.leaf("bad"))
        ));
        var adapter = new TypedAdapter() {
            @Override
            public byte[] fingerprint(TestTree node) {
                if ("bad".equals(node.label)) throw new IllegalStateException("fingerprint fail");
                return super.fingerprint(node);
            }
        };
        var walker = new NestedItemWalker<>(new TraversalLimits(4, 4096), adapter);
        var result = walker.walk(new TraversalChild<>(root.label, 0, root));
        assertEquals(1, result.leaves().size());
        assertTrue(result.truncated());
        assertEquals(root.label, result.leaves().getFirst().descriptor().materialKey());
        assertEquals(3, result.visitedStacks());
    }

    @Test
    void childDescribeThrowsEmitsRootAndTruncated() {
        TestTree root = TestTree.container("root", List.of(new TestChild(0, TestTree.leaf("child"))));
        var adapter = new TypedAdapter() {
            @Override
            public ItemDescriptor describe(TestTree node) {
                if ("child".equals(node.label)) throw new IllegalStateException("describe fail");
                return super.describe(node);
            }
        };
        var walker = new NestedItemWalker<>(new TraversalLimits(4, 4096), adapter);
        var result = walker.walk(new TraversalChild<>(root.label, 0, root));
        assertEquals(1, result.leaves().size());
        assertTrue(result.truncated());
        assertEquals(root.label, result.leaves().getFirst().descriptor().materialKey());
        assertEquals(2, result.visitedStacks());
    }

    @Test
    void traversalLimitsDefaults() {
        assertEquals(4, TraversalLimits.defaults().maximumDepth());
        assertEquals(4096, TraversalLimits.defaults().maximumStacksPerRoot());
    }

    @Test
    void buildBoundedTruncatesTextWithoutSplittingSurrogates() {
        String bounded = "a".repeat(255) + "\uD83D\uDE80" + "z";
        ItemDescriptor descriptor = ItemDescriptor.builder()
            .materialKey(bounded)
            .amount(1)
            .addDisplayText(bounded)
            .addLore(bounded)
            .addEnchantment(bounded, 1)
            .addAttribute(bounded, 1.0)
            .addTrait(bounded)
            .addScalarMetadata(bounded, bounded)
            .addCustomTag(bounded)
            .buildBounded(256, 256);

        String expected = "a".repeat(255) + "\uD83D\uDE80";
        assertEquals(expected, descriptor.displayText().getFirst());
        assertEquals(256, descriptor.displayText().getFirst().codePointCount(0, descriptor.displayText().getFirst().length()));
        assertEquals(expected, descriptor.lore().getFirst());
        assertEquals(256, descriptor.lore().getFirst().codePointCount(0, descriptor.lore().getFirst().length()));
        assertEquals(expected, descriptor.materialKey());
        assertEquals(expected, descriptor.enchantments().keySet().iterator().next());
        assertEquals(expected, descriptor.attributes().keySet().iterator().next());
        assertEquals(expected, descriptor.traits().iterator().next());
        assertEquals(expected, descriptor.scalarMetadata().keySet().iterator().next());
        assertEquals(expected, descriptor.scalarMetadata().values().iterator().next());
        assertEquals(expected, descriptor.customTags().iterator().next());
        assertFalse(hasUnpairedSurrogate(descriptor.displayText().getFirst()));
        assertFalse(hasUnpairedSurrogate(descriptor.lore().getFirst()));
    }

    @Test
    void buildBoundedCapsCollectionsTo256Deterministically() {
        ItemDescriptor.Builder builder = ItemDescriptor.builder()
            .materialKey("stone")
            .amount(1);

        for (int i = 0; i < 257; i++) {
            builder = builder.addDisplayText("line-" + i);
            builder = builder.addLore("lore-" + i);
            builder = builder.addEnchantment("ench-" + i, i + 1);
            builder = builder.addAttribute("attribute-" + i, i);
            builder = builder.addTrait("trait-" + i);
            builder = builder.addScalarMetadata("key-" + i, "value-" + i);
            builder = builder.addCustomTag("tag-" + i);
        }

        ItemDescriptor descriptor = builder.buildBounded(256, 256);

        assertEquals(256, descriptor.displayText().size());
        assertEquals(256, descriptor.lore().size());
        assertEquals(256, descriptor.enchantments().size());
        assertEquals(256, descriptor.attributes().size());
        assertEquals(256, descriptor.traits().size());
        assertEquals(256, descriptor.scalarMetadata().size());
        assertEquals(256, descriptor.customTags().size());

        assertSorted(descriptor.displayText());
        assertSorted(descriptor.lore());
        assertSorted(new ArrayList<>(descriptor.enchantments().keySet()));
        assertSorted(new ArrayList<>(descriptor.attributes().keySet()));
        assertSorted(new ArrayList<>(descriptor.traits()));
        assertSorted(new ArrayList<>(descriptor.scalarMetadata().keySet()));
        assertSorted(new ArrayList<>(descriptor.customTags()));
    }

    @Test
    void defaultBuildRemainsUnbounded() {
        ItemDescriptor.Builder builder = ItemDescriptor.builder()
            .materialKey("stone")
            .amount(1);
        for (int i = 0; i < 257; i++) {
            builder = builder.addDisplayText("line-" + i);
            builder = builder.addLore("lore-" + i);
            builder = builder.addEnchantment("ench-" + i, i + 1);
            builder = builder.addAttribute("attribute-" + i, i);
            builder = builder.addTrait("trait-" + i);
            builder = builder.addScalarMetadata("key-" + i, "value-" + i);
            builder = builder.addCustomTag("tag-" + i);
        }
        ItemDescriptor descriptor = builder.build();

        assertEquals(257, descriptor.displayText().size());
        assertEquals(257, descriptor.lore().size());
        assertEquals(257, descriptor.enchantments().size());
        assertEquals(257, descriptor.attributes().size());
        assertEquals(257, descriptor.traits().size());
        assertEquals(257, descriptor.scalarMetadata().size());
        assertEquals(257, descriptor.customTags().size());
    }

    private static boolean hasUnpairedSurrogate(String value) {
        boolean high = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) high = true;
            else if (Character.isLowSurrogate(c)) high = false;
            else high = false;
        }
        return high;
    }

    private static void assertSorted(List<String> values) {
        for (int i = 1; i < values.size(); i++) {
            assertTrue(values.get(i - 1).compareTo(values.get(i)) <= 0, "Not sorted at index " + i);
        }
    }

    static final class NullEntryAdapter implements TraversalAdapter<TestTree> {
        @Override
        public ItemDescriptor describe(TestTree node) {
            return node.descriptor;
        }

        @Override
        public byte[] fingerprint(TestTree node) {
            return node.fingerprint != null ? node.fingerprint : new byte[0];
        }

        @Override
        public List<TraversalChild<TestTree>> children(TestTree node) {
            List<TraversalChild<TestTree>> children = new ArrayList<>();
            children.add(new TraversalChild<>(node.label, 0, TestTree.leaf("child")));
            children.add(null);
            return children;
        }
    }
}
