package dev.mikoto2000.rei.computeruse;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * 画面操作の支援に必要なRobot操作の実行先を扱う。
 *
 * <p>Narrow native boundary, permitting adapter tests without constructing Robot.
 */
public interface RobotDriver {
  /** 画面操作の支援で用いる画面上の位置と大きさを返す。 */
  ScreenGeometry geometry() throws Exception;

  /** 画面操作の支援で用いる画面一覧を返す。 */
  default java.util.List<ScreenGeometry> displays() throws Exception {
    return java.util.List.of(geometry());
  }

  /** 条件に合う対象を利用するため、表示を選択する。 */
  default void selectDisplay(ScreenGeometry geometry) throws Exception {}

  /** 後から状況を確認できるよう、Robot操作の実行先を記録する。 */
  BufferedImage capture(Rectangle bounds) throws Exception;

  /** 画面操作の支援に関するmoveを処理する。 */
  void move(int x, int y);

  /** 画面操作の支援に関するmousePressを処理する。 */
  void mousePress(int mask);

  /** 画面操作の支援に関するmouse解放を処理する。 */
  void mouseRelease(int mask);

  /** 画面操作の支援に関するキーPressを処理する。 */
  void keyPress(int code);

  /** 画面操作の支援に関するキー解放を処理する。 */
  void keyRelease(int code);

  /** 画面操作の支援に関するwheelを処理する。 */
  void wheel(int amount);
}
