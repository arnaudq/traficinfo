package org.traficinfo.parsers.onisr;

/** The participant and vehicle files use the same whitespace-separated numeric IDs. */
final class ONISRIdentifiers {
  private ONISRIdentifiers() {}

  static String normalize(String value) {
    StringBuilder normalized = new StringBuilder();
    value.codePoints().filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)).forEach(normalized::appendCodePoint);
    String id = normalized.toString();
    if (!id.matches("[0-9]+")) throw new IllegalArgumentException("Invalid ID: " + value);
    return id;
  }
}
