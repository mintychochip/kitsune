package dev.jlo.kitsune.item;

import java.util.List;

import dev.jlo.kitsune.model.ItemDescriptor;

interface TraversalAdapter<T> {
    ItemDescriptor describe(T node);

    byte[] fingerprint(T node);

    List<TraversalChild<T>> children(T node);

    default ItemDescriptor requireDescribe(T node) {
        return java.util.Objects.requireNonNull(describe(node), "Adapter descriptor must not be null");
    }

    default byte[] requireFingerprint(T node) {
        byte[] fp = fingerprint(node);
        return java.util.Objects.requireNonNull(fp, "Adapter fingerprint must not be null");
    }
}
