package dev.mikoto2000.rei.computeruse;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * 画面操作の支援で受け渡す取得済み画面を表す。
 *
 * @param displays 画面一覧
 */
public record CapturedScreen(java.util.List<DisplayCapture> displays) {
  /** 受け取った情報からCapturedScreenを作成する。 */
  public CapturedScreen {
    displays = java.util.List.copyOf(displays);
    if (displays.isEmpty()
        || displays.stream().map(d -> d.geometry().id()).distinct().count() != displays.size())
      throw new IllegalArgumentException("Missing or duplicate displays");
  }

  /** 画面操作の支援を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public CapturedScreen(BufferedImage image, Rectangle bounds) {
    this(java.util.List.of(new DisplayCapture(new ScreenGeometry(bounds, bounds, true), image)));
  }

  /** 画面操作の支援で用いる画像を返す。 */
  public BufferedImage image() {
    return displays.getFirst().image();
  }

  /** 画面操作の支援で用いる許容範囲を返す。 */
  public Rectangle bounds() {
    return displays.getFirst().geometry().bounds();
  }

  /** 画面操作の支援で用いる表示を返す。 */
  public DisplayCapture display(String id) {
    if (id == null && displays.size() == 1) return displays.getFirst();
    return displays.stream()
        .filter(d -> d.geometry().id().equals(id))
        .findFirst()
        .orElseThrow(() -> new InvalidComputerDecision("Missing or unknown target displayId"));
  }
}
