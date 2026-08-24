package org.aincraft.kitsune.config;

import java.util.Set;

public interface Configuration {
  String getString(String path, String def);
  int getInt(String path, int def);
  boolean getBoolean(String path, boolean def);
  double getDouble(String path, double def);
  Set<String> getKeys(String path);
  String toString();
}
