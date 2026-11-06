package dev.mikoto2000.rei.timing;

import java.util.concurrent.atomic.AtomicReference;

/** Reactorコンテキストで共有する、一回のモデル呼び出しの計測情報。 */
public final class TimingAttempt {
  /** 照合キー。 */
  public static final Class<TimingAttempt> KEY = TimingAttempt.class;

  private final String requestId;
  private final int number;
  private final AtomicReference<String> physicalSpan = new AtomicReference<>();

  /** 指定の要求と試行番号に対応する計測情報を作成する。 */
  public TimingAttempt(String requestId, int number) {
    this.requestId = requestId;
    this.number = number;
  }

  /** 要求の識別子を返す。 */
  public String requestId() {
    return requestId;
  }

  /** 試行番号を返す。 */
  public int number() {
    return number;
  }

  /** 現在の物理リクエストの計測区間を返す。 */
  public String physicalSpan() {
    return physicalSpan.get();
  }

  /** 現在の物理リクエストの計測区間を設定する。 */
  public void setPhysicalSpan(String span) {
    physicalSpan.set(span);
  }

  /** 次の試行に対応する計測情報を返す。 */
  public TimingAttempt next() {
    return new TimingAttempt(requestId, number + 1);
  }
}
