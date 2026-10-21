package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す画面操作利用進捗通知内容を表す。
 *
 * <p>Small metadata only: no screenshots, clipboard contents, or typed text.
 *
 * @param step 手順
 * @param phase 段階
 * @param action 操作
 * @param target 対象
 * @param x 横方向の位置
 * @param y 縦方向の位置
 * @param confidence 確信度
 * @param reason 理由
 */
public record ComputerUseProgressPayload(
    int step,
    String phase,
    String action,
    String target,
    Integer x,
    Integer y,
    Double confidence,
    String reason)
    implements AgentEventPayload {
  /** 受け取った情報からComputerUseProgressPayloadを作成する。 */
  public ComputerUseProgressPayload {
    phase = clean(phase);
    action = clean(action);
    target = clean(target);
    reason = clean(reason);
  }

  /** 実行状況の通知で用いる変更なしを返す。 */
  private static String clean(String text) {
    if (text == null) return null;
    text = CredentialRedactor.redact(text).replaceAll("[\\p{Cntrl}]", " ");
    return text.substring(0, Math.min(200, text.length()));
  }
}
