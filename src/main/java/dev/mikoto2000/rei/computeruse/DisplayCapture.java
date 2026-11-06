package dev.mikoto2000.rei.computeruse;

import java.awt.Point;
import java.awt.image.BufferedImage;

/**
 * 画面操作の支援で受け渡す表示取得記録を表す。
 *
 * @param geometry 画面上の位置と大きさ
 * @param image 画像
 */
public record DisplayCapture(ScreenGeometry geometry, BufferedImage image) {
  /** 受け取った情報からDisplayCaptureを作成する。 */
  public DisplayCapture {
    if (geometry == null || image == null)
      throw new IllegalArgumentException("Invalid display capture");
    image = copyImage(image);
  }

  /** 保持している画面を変更できないよう、画像の独立したコピーを返す。 */
  @Override
  public BufferedImage image() {
    return copyImage(image);
  }

  int imageWidth() {
    return image.getWidth();
  }

  int imageHeight() {
    return image.getHeight();
  }

  private static BufferedImage copyImage(BufferedImage source) {
    var model = source.getColorModel();
    var raster = model.createCompatibleWritableRaster(source.getWidth(), source.getHeight());
    source.copyData(raster);
    return new BufferedImage(model, raster, source.isAlphaPremultiplied(), null);
  }

  /** 画面操作の支援で用いるデスクトップPointを返す。 */
  public Point desktopPoint(ComputerAction.Target target) {
    if (target.x() < 0
        || target.y() < 0
        || target.x() >= image.getWidth()
        || target.y() >= image.getHeight())
      throw new InvalidComputerDecision("Target outside selected screenshot");
    var bounds = geometry.bounds();
    return new Point(
        bounds.x + (int) ((long) target.x() * bounds.width / image.getWidth()),
        bounds.y + (int) ((long) target.y() * bounds.height / image.getHeight()));
  }
}
