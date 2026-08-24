package org.aincraft.kitsune;

public interface Inventory {

  int size();

  Item getItem(int slot);
}
