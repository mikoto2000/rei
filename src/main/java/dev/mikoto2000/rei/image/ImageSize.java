package dev.mikoto2000.rei.image;

public record ImageSize(int width, int height) {

  public ImageSize {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("画像サイズは正の整数で指定してください");
    }
  }

  public static ImageSize parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("画像サイズは <width>x<height> 形式で指定してください");
    }
    String[] parts = value.trim().split("x", -1);
    if (parts.length != 2) {
      throw new IllegalArgumentException("画像サイズは <width>x<height> 形式で指定してください");
    }
    try {
      return new ImageSize(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("画像サイズは <width>x<height> 形式で指定してください", e);
    }
  }

  @Override
  public String toString() {
    return width + "x" + height;
  }
}
