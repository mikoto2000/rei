package dev.mikoto2000.rei.computeruse;

/**
 * 画面操作の支援に必要な画面操作操作を扱う。
 *
 * <p>Exactly one decision; terminal and uncertain decisions never dispatch input.
 */
public sealed interface ComputerAction {
  /** 画面操作の支援におけるリスクの選択肢。 */
  enum Risk {
    /** 低い評価の区分。 */
    LOW,
    /** CONFIRM必須。 */
    CONFIRM_REQUIRED,
    /** 禁止する操作の区分。 */
    PROHIBITED
  }

  /**
   * 画面操作の支援で受け渡す対象を表す。
   *
   * @param displayId 表示識別子
   * @param x 横方向の位置
   * @param y 縦方向の位置
   * @param description 説明
   * @param normalizedX 表記を統一したX
   * @param normalizedY 表記を統一したY
   */
  record Target(
      String displayId, int x, int y, String description, Double normalizedX, Double normalizedY) {
    /** 画面操作の支援を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Target(String displayId, int x, int y, String description) {
      this(displayId, x, y, description, null, null);
    }

    /** 画面操作の支援を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Target(int x, int y, String description) {
      this(null, x, y, description);
    }
  }

  /** 画面操作の支援で用いるリスクを返す。 */
  default Risk risk() {
    return Risk.LOW;
  }

  /**
   * 画面操作の支援で受け渡すClickを表す。
   *
   * @param target 対象
   * @param confidence 確信度
   * @param risk リスク
   */
  record Click(Target target, double confidence, Risk risk) implements ComputerAction {}

  /**
   * 画面操作の支援で受け渡す数値Clickを表す。
   *
   * @param target 対象
   * @param confidence 確信度
   * @param risk リスク
   */
  record DoubleClick(Target target, double confidence, Risk risk) implements ComputerAction {}

  /**
   * 画面操作の支援で受け渡す種別テキストを表す。
   *
   * @param text テキスト
   * @param risk リスク
   */
  record TypeText(String text, Risk risk) implements ComputerAction {}

  /**
   * 画面操作の支援で受け渡すPressキーを表す。
   *
   * @param key 照合キー
   * @param risk リスク
   */
  record PressKey(String key, Risk risk) implements ComputerAction {}

  /**
   * 画面操作の支援で受け渡すScrollを表す。
   *
   * @param amount 量
   * @param risk リスク
   */
  record Scroll(int amount, Risk risk) implements ComputerAction {}

  /**
   * 画面操作の支援で受け渡す待機を表す。
   *
   * @param millis ミリ秒数
   */
  record Wait(long millis) implements ComputerAction {}

  /**
   * 画面操作の支援で受け渡す完了を表す。
   *
   * @param reason 理由
   */
  record Done(String reason) implements ComputerAction {}

  /**
   * 画面操作の支援で受け渡す失敗を表す。
   *
   * @param reason 理由
   */
  record Failed(String reason) implements ComputerAction {}

  /**
   * 画面操作の支援で受け渡す不確実なを表す。
   *
   * @param reason 理由
   */
  record Uncertain(String reason) implements ComputerAction {}
}
