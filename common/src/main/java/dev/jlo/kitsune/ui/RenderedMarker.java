package dev.jlo.kitsune.ui;

import dev.jlo.kitsune.model.BlockKey;

import java.util.UUID;

public interface RenderedMarker {
    UUID id();

    BlockKey root();

    void remove();
}
