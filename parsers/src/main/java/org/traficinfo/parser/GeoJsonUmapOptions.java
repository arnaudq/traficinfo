package org.traficinfo.parser;

public class GeoJsonUmapOptions {

  public enum Color {
    Blue,
    Green,
    Red;
  }
  private boolean showLabel;
  private boolean labelInteractive;
  private Color color;

  public boolean isShowLabel() {
    return showLabel;
  }

  public void setShowLabel(boolean showLabel) {
    this.showLabel = showLabel;
  }


  public boolean isLabelInteractive() {
    return labelInteractive;
  }

  public void setLabelInteractive(boolean labelInteractive) {
    this.labelInteractive = labelInteractive;
  }

  public Color getColor() {
    return color;
  }

  public void setColor(Color color) {
    this.color = color;
  }

  @Override
  public String toString() {
    return "GeoJsonUmapOptions{" +
      "showLabel=" + showLabel +
      ", labelInteractive=" + labelInteractive +
      ", color=" + color +
      '}';
  }
}
