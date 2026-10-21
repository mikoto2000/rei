package dev.mikoto2000.rei.application.session;

/**
 * 会話の継続管理に必要な会話タイトルを扱う。
 *
 * <p>Titles count Unicode code points, never UTF-16 units or encoded bytes.
 */
public final class SessionTitle {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private SessionTitle() {}

  /** 会話の継続管理で利用する情報を、受け取った形式から変換する。 */
  public static String from(String message) {
    return message.substring(
        0,
        message.offsetByCodePoints(0, Math.min(80, message.codePointCount(0, message.length()))));
  }
}
