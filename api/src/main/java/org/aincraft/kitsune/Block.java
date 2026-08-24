package org.aincraft.kitsune;

import java.util.Optional;
import org.jetbrains.annotations.Nullable;

public interface Block {

  String type();

  String world();

  @Nullable
  Inventory inventory();

  boolean isAir();
}
