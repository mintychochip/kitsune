package dev.jlo.kitsune.item;

import java.util.List;

import dev.jlo.kitsune.model.ItemDescriptor;

/** Adapts an external node type for nested-item traversal. */
public interface TraversalAdapter<T> {
    /** Returns the descriptor for a node. */
    ItemDescriptor describe(T node);

    /** Returns a stable fingerprint used to identify a node during traversal. */
    byte[] fingerprint(T node);

    /** Returns the child nodes of a node. */
    List<TraversalChild<T>> children(T node);

    /** Returns a non-null descriptor, rejecting adapters that return null. */
    default ItemDescriptor requireDescribe(T node) {
        return java.util.Objects.requireNonNull(describe(node), "Adapter descriptor must not be null");
    }

    /** Returns a non-null fingerprint, rejecting adapters that return null. */
    default byte[] requireFingerprint(T node) {
        byte[] fp = fingerprint(node);
        return java.util.Objects.requireNonNull(fp, "Adapter fingerprint must not be null");
    }
}
