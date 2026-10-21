package dev.mikoto2000.rei.core.completion;

/**
 * 入力候補の提示で受け渡す入力補完候補を表す。
 *
 * <p>Unquoted insertion text; escaping and presentation belong to the input adapter.
 *
 * @param value 値
 * @param display 表示
 * @param description 説明
 * @param kind 種類
 * @param appendSpace 追記Space
 */
public record CompletionCandidate(
    String value, String display, String description, String kind, boolean appendSpace) {
  /** 後続の処理で使う値を返す。 */
  public static CompletionCandidate value(String value, String kind) {
    return new CompletionCandidate(value, value, null, kind, true);
  }
}
