package dev.jlo.kitsune.item;

import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemDraft;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class NestedItemWalker<T> {

    private final TraversalLimits limits;
    private final TraversalAdapter<T> adapter;

    public NestedItemWalker(TraversalLimits limits, TraversalAdapter<T> adapter) {
        this.limits = Objects.requireNonNull(limits, "Limits must not be null");
        this.adapter = Objects.requireNonNull(adapter, "Adapter must not be null");
    }

    public NestedItemWalkResult walk(TraversalChild<T> root) {
        Objects.requireNonNull(root, "Root must not be null");
        List<ItemDraft> leaves = new ArrayList<>();

        ItemDescriptor descriptor = adapter.requireDescribe(root.node());
        int amount = descriptor.amount();
        ItemDraft current = new ItemDraft(
            new ItemPath(List.of(new ItemPathStep(root.label(), root.slot()))),
            amount,
            descriptor
        );

        int[] visited = {1};

        Set<ByteArrayKey> branchVisited = new HashSet<>();
        branchVisited.add(new ByteArrayKey(adapter.requireFingerprint(root.node())));

        boolean truncated = descend(root.node(),
            new ItemPath(List.of(new ItemPathStep(root.label(), root.slot()))),
            1,
            branchVisited,
            leaves,
            current,
            visited);

        return new NestedItemWalkResult(leaves, visited[0], truncated);
    }

    private boolean descend(T node,
                            ItemPath path,
                            int depth,
                            Set<ByteArrayKey> branchVisited,
                            List<ItemDraft> leaves,
                            ItemDraft currentDraft,
                            int[] visited) {
        int subtreeLeafStart = leaves.size();
        List<TraversalChild<T>> children;
        try {
            children = adapter.children(node);
        } catch (Exception ex) {
            leaves.add(currentDraft);
            return true;
        }

        if (children == null) {
            leaves.add(currentDraft);
            return true;
        }
        if (children.isEmpty()) {
            leaves.add(currentDraft);
            return false;
        }

        if (depth >= limits.maximumDepth()) {
            leaves.add(currentDraft);
            return true;
        }

        for (TraversalChild<T> child : children) {
            if (child == null) {
                leaves.add(currentDraft);
                return true;
            }
        }

        List<TraversalChild<T>> sorted = new ArrayList<>(children);
        sorted.sort(Comparator.comparingInt(TraversalChild::slot));

        boolean truncated = false;
        for (TraversalChild<T> child : sorted) {
            if (visited[0] >= limits.maximumStacksPerRoot()) {
                rollbackTo(subtreeLeafStart, leaves);
                leaves.add(currentDraft);
                return true;
            }

            visited[0]++;

            byte[] fp;
            try {
                fp = adapter.requireFingerprint(child.node());
            } catch (Exception ex) {
                rollbackTo(subtreeLeafStart, leaves);
                leaves.add(currentDraft);
                return true;
            }
            ByteArrayKey key = new ByteArrayKey(fp);

            ItemDescriptor childDesc;
            try {
                childDesc = adapter.requireDescribe(child.node());
            } catch (Exception ex) {
                rollbackTo(subtreeLeafStart, leaves);
                leaves.add(currentDraft);
                return true;
            }
            int childAmount = childDesc.amount();
            ItemPath childPath = new ItemPath(
                append(path.steps(), child.label(), child.slot())
            );
            ItemDraft childDraft = new ItemDraft(
                childPath,
                childAmount,
                childDesc
            );

            if (branchVisited.contains(key)) {
                leaves.add(childDraft);
                truncated = true;
                continue;
            }

            if (depth + 1 >= limits.maximumDepth()) {
                leaves.add(childDraft);
                truncated = true;
                continue;
            }

            Set<ByteArrayKey> nextBranch = new HashSet<>(branchVisited);
            nextBranch.add(key);

            boolean childTruncated = descend(child.node(),
                childPath,
                depth + 1,
                nextBranch,
                leaves,
                childDraft,
                visited);
            if (childTruncated) {
                truncated = true;
            }
        }
        return truncated;
    }

    private static void rollbackTo(int size, List<?> list) {
        list.subList(size, list.size()).clear();
    }

    private static List<ItemPathStep> append(List<ItemPathStep> steps, String label, int slot) {
        List<ItemPathStep> next = new ArrayList<>(steps.size() + 1);
        next.addAll(steps);
        next.add(new ItemPathStep(label, slot));
        return List.copyOf(next);
    }

    private record ByteArrayKey(byte[] value) {
        ByteArrayKey {
            value = Objects.requireNonNull(value, "Fingerprint must not be null").clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ByteArrayKey that)) return false;
            return java.util.Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(value);
        }
    }
}
