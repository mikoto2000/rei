package dev.mikoto2000.rei.computeruse;

import java.awt.Rectangle;

/**
 * 画面操作の支援で受け渡す画面画面上の位置と大きさを表す。
 *
 * @param id 識別子
 * @param bounds 許容範囲
 * @param virtualBounds 仮想許容範囲
 * @param primary 主要な
 * @param scaleX 横方向の倍率
 * @param scaleY 縦方向の倍率
 */
public record ScreenGeometry(
    String id,
    Rectangle bounds,
    Rectangle virtualBounds,
    boolean primary,
    double scaleX,
    double scaleY) {
  /** 画面操作の支援を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ScreenGeometry(Rectangle bounds, Rectangle virtualBounds, boolean primary) {
    this(primary ? "primary" : "secondary", bounds, virtualBounds, primary, 1, 1);
  }

  /** 受け取った情報からScreenGeometryを作成する。 */
  public ScreenGeometry {
    if (id == null
        || id.isBlank()
        || bounds.width <= 0
        || bounds.height <= 0
        || !Double.isFinite(scaleX)
        || !Double.isFinite(scaleY)
        || scaleX <= 0
        || scaleY <= 0) throw new IllegalArgumentException("Invalid display geometry");
    bounds = new Rectangle(bounds);
    virtualBounds = new Rectangle(virtualBounds);
  }

  /** 画面操作の支援で用いる許容範囲を返す。 */
  @Override
  public Rectangle bounds() {
    return new Rectangle(bounds);
  }

  /** 画面操作の支援で用いる仮想許容範囲を返す。 */
  @Override
  public Rectangle virtualBounds() {
    return new Rectangle(virtualBounds);
  }

  /** 条件を満たさない操作を拒否するため、主要なの前提条件を確認する。 */
  public void requirePrimary() {
    if (!primary || bounds.width < 1 || bounds.height < 1 || !virtualBounds.contains(bounds))
      throw new IllegalArgumentException("Only the current primary monitor is supported");
  }
}
