package dev.mikoto2000.rei.timing;

/**
 * 実行時間の把握で受け渡す時間計測試行を表す。
 *
 * <p>Reactor-context observation only; never changes prompts or request options.
 *
 * @param requestId 要求の識別子
 * @param number 数値
 * @param physicalSpan physical計測区間
 */
public record TimingAttempt(
    String requestId,
    int number,
    java.util.concurrent.atomic.AtomicReference<String> physicalSpan) {
  /** 照合キー。 */
  public static final Class<TimingAttempt> KEY = TimingAttempt.class;

  /** 実行時間の把握で用いる次を返す。 */
  public TimingAttempt next() {
    return new TimingAttempt(
        requestId, number + 1, new java.util.concurrent.atomic.AtomicReference<>());
  }
}
