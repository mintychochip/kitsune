package dev.jlo.kitsune.item;

import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemDraft;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies normal nested-item traversal and traversal limits. */
class NestedItemWalkerTest {

    /** Represents a child node at a slot in a test tree. */
    record TestChild(int slot, TestTree node) {
        TestChild {
            if (slot < 0) throw new IllegalArgumentException("Slot must not be negative");
            Objects.requireNonNull(node, "Node must not be null");
        }

        String label() {
            return node.label;
        }
    }

    /** Minimal tree node used by the traversal tests. */
    static final class TestTree {
        final String label;
        final int amount = 1;
        final ItemDescriptor descriptor;
        final List<TestChild> children;
        final boolean failChildren;
        final byte[] fingerprint;

        private TestTree(String label, List<TestChild> children, boolean failChildren, byte[] fingerprint) {
            this.label = label;
            this.descriptor = ItemDescriptor.builder().materialKey(label).amount(amount).build();
            this.children = List.copyOf(children);
            this.failChildren = failChildren;
            this.fingerprint = fingerprint;
        }

        static TestTree leaf(String label) {
            return new TestTree(label, List.of(), false, digest("leaf-" + label));
        }

        static TestTree container(String label, List<TestChild> children) {
            return new TestTree(label, children, false, digest("container-" + label));
        }

        static TestTree container(String label, TestChild... children) {
            return container(label, Arrays.asList(children));
        }

        static TestTree failingContainer(String label) {
            return new TestTree(label, List.of(), true, digest("container-" + label));
        }

        static TestTree repeatedFingerprintChain(String label, int length) {
            byte[] fp = digest("repeat-" + label);
            TestTree prev = leaf(label);
            for (int i = 1; i < length; i++) {
                prev = new TestTree(label, List.of(new TestChild(0, prev)), false, fp);
            }
            return prev;
        }

        static TestTree chain(int depth) {
            TestTree node = leaf("leaf");
            for (int i = 0; i < depth; i++) {
                node = new TestTree("node-" + i, List.of(new TestChild(0, node)), false, digest("node-" + i));
            }
            return node;
        }

        static TestTree containerWithLeafCount(String label, int count) {
            List<TestChild> children = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                children.add(new TestChild(i, leaf(label + "-" + i)));
            }
            return container(label, children);
        }

        private static byte[] digest(String value) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(value.getBytes(StandardCharsets.UTF_8));
                return md.digest();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static NestedItemWalker<TestTree> walker(int maxDepth, int maxStacks) {
        return new NestedItemWalker<>(new TraversalLimits(maxDepth, maxStacks), new TestTreeTraversalAdapter());
    }

    @Test
    void emitsChestShulkerLeafPathInSlotOrder() {
        TestTree pickaxe = TestTree.leaf("pickaxe");
        TestTree shulker = TestTree.container("shulker", new TestChild(12, pickaxe));
        var result = walker(4, 4096).walk(new TraversalChild<>("Chest", 4, shulker));
        assertEquals(new ItemPath(List.of(new ItemPathStep("Chest", 4), new ItemPathStep("shulker", 12))),
            result.leaves().getFirst().path());
    }

    @Test
    void stopsAtDepthFourWithoutDroppingContainerItself() {
        TestTree nested = TestTree.chain(6);
        var result = walker(4, 4096).walk(new TraversalChild<>(nested.label, 0, nested));
        assertEquals(1, result.leaves().size());
        assertEquals(4, result.leaves().getFirst().path().steps().size());
        assertTrue(result.truncated());
    }

    @Test
    void stopsAfter4096VisitedStacks() {
        TestTree root = TestTree.containerWithLeafCount("bundle", 5000);
        var result = walker(4, 4096).walk(new TraversalChild<>(root.label, 0, root));
        assertEquals(4096, result.visitedStacks());
        assertTrue(result.truncated());
        assertEquals(1, result.leaves().size());
        assertEquals(root.label, result.leaves().getFirst().descriptor().materialKey());
    }

    @Test
    void providerExceptionFallsBackToContainerAsLeaf() {
        TestTree failing = TestTree.failingContainer("backpack");
        var result = walker(4, 4096).walk(new TraversalChild<>(failing.label, 2, failing));
        assertEquals("backpack", result.leaves().getFirst().descriptor().materialKey());
    }

    @Test
    void repeatedFingerprintDoesNotLoop() {
        TestTree repeated = TestTree.repeatedFingerprintChain("same", 8);
        var result = walker(4, 4096).walk(new TraversalChild<>(repeated.label, 0, repeated));
        assertEquals(2, result.visitedStacks());
        assertTrue(result.truncated());
    }

    @Test
    void deterministicSlotSorting() {
        TestTree child3 = TestTree.leaf("child3");
        TestTree child1 = TestTree.leaf("child1");
        TestTree child2 = TestTree.leaf("child2");
        TestTree root = TestTree.container("root",
            new TestChild(3, child3),
            new TestChild(1, child1),
            new TestChild(2, child2));
        var result = walker(4, 4096).walk(new TraversalChild<>(root.label, 0, root));
        assertEquals(List.of("child1", "child2", "child3"),
            result.leaves().stream().map(d -> d.descriptor().materialKey()).collect(Collectors.toList()));
    }

    @Test
    void siblingDuplicateFingerprintsRemainTraversable() {
        TestTree duplicate = TestTree.leaf("duplicate");
        TestTree root = TestTree.container("root",
            new TestChild(0, duplicate),
            new TestChild(1, duplicate));
        var result = walker(4, 4096).walk(new TraversalChild<>(root.label, 0, root));
        assertEquals(2, result.leaves().size());
        assertEquals(3, result.visitedStacks());
    }

    @Test
    void resultsAreImmutable() {
        TestTree leaf = TestTree.leaf("leaf");
        var result = walker(4, 4096).walk(new TraversalChild<>(leaf.label, 0, leaf));
        assertThrows(UnsupportedOperationException.class, () -> result.leaves().add(null));
        assertThrows(UnsupportedOperationException.class, () -> result.leaves().getFirst().path().steps().add(null));
    }

    @Test
    void constructorRejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new TraversalLimits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TraversalLimits(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TraversalLimits(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TraversalLimits(1, -1));
    }
}
