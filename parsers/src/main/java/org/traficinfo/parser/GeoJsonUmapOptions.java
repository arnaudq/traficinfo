package org.traficinfo.parser;

public class GeoJsonUmapOptions {

  public enum Color {
    Blue,
    Green,
    Red;
  }
  private Color color;

  public Color getColor() {
    return color;
  }

  public void setColor(Color color) {
    this.color = color;
  }

  @Override
  public String toString() {
    return "GeoJsonUmapOptions{" +
      "color=" + color +
      '}';
  }
}
