package org.aincraft.kitsune;

public class Util {
  private Util() {}

  public static String fromMaterialToTitleCase(String material) {
    StringBuilder sb = new StringBuilder(material.length());
    boolean capitalize = true;
    for (char ch : material.toCharArray()) {
      if (ch == '_') {
        sb.append(' ');
        capitalize = true;
      } else if (capitalize) {
        capitalize = false;
        sb.append(ch);
      } else {
        sb.append(Character.toLowerCase(ch));
      }
    }
    return sb.toString();
  }
}
