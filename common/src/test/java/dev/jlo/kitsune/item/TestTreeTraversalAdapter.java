package dev.jlo.kitsune.item;

import dev.jlo.kitsune.model.ItemDescriptor;
import java.util.List;
import java.util.stream.Collectors;

import static dev.jlo.kitsune.item.NestedItemWalkerTest.TestTree;

class TestTreeTraversalAdapter implements TraversalAdapter<TestTree> {

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
        if (node.failChildren) {
            throw new IllegalStateException("Simulated provider failure");
        }
        return node.children.stream()
            .map(child -> new TraversalChild<>(node.label, child.slot(), child.node()))
            .collect(Collectors.toList());
    }
}
