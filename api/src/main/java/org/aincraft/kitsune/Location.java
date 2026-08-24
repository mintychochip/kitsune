package org.aincraft.kitsune;

public interface Location {

  World getWorld();

  int blockX();

  int blockY();

  int blockZ();

  Block getBlock();

  /**
   * Returns coordinate string in format "(x, y, z)".
   */
  default String asCoordinates() {
    return String.format("(%d, %d, %d)", blockX(), blockY(), blockZ());
  }

  /**
   * Calculates 3D Euclidean distance to another location.
   * Returns Double.MAX_VALUE if worlds don't match.
   */
  default double distanceTo(Location other) {
    if (!getWorld().equals(other.getWorld())) {
      return Double.MAX_VALUE;
    }
    double dx = blockX() - other.blockX();
    double dy = blockY() - other.blockY();
    double dz = blockZ() - other.blockZ();
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }
}
