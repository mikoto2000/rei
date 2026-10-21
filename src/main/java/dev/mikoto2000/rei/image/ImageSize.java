package dev.mikoto2000.rei.image;

/**
 * 画像の生成で受け渡す画像大きさを表す。
 *
 * @param width 幅
 * @param height 高さ
 */
public record ImageSize(int width, int height) {

  /** 受け取った情報からImageSizeを作成する。 */
  public ImageSize {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("画像サイズは正の整数で指定してください");
    }
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
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

  /** 画像の生成の情報を、表示や診断で確認できる文字列表現にする。 */
  @Override
  public String toString() {
    return width + "x" + height;
  }
}
