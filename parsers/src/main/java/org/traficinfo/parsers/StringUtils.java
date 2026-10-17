package org.traficinfo.parsers;

import java.util.Locale;

public final class StringUtils {

  private StringUtils() {
  }

  /**
   * Capitalizes words separated by whitespace or hyphens, preserving separators.
   * For example, "SAINT CLEMENT-DE-RIVIERE" becomes "Saint Clement-De-Riviere".
   * Returns null for null input.
   */
  public static String toCamelCase(String value) {
    if (value == null) {
      return null;
    }
    String lowerCase = value.toLowerCase(Locale.ROOT);
    StringBuilder result = new StringBuilder(lowerCase.length());
    boolean wordStart = true;
    for (int offset = 0; offset < lowerCase.length();) {
      int codePoint = lowerCase.codePointAt(offset);
      boolean separator = Character.isWhitespace(codePoint) || codePoint == '-';
      result.appendCodePoint(wordStart && !separator
          ? Character.toTitleCase(codePoint) : codePoint);
      wordStart = separator;
      offset += Character.charCount(codePoint);
    }
    return result.toString();
  }
}
