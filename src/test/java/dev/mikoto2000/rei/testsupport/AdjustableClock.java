package dev.mikoto2000.rei.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** 検証条件を再現するためのAdjustable時刻の取得元。 */
public final class AdjustableClock extends Clock {
  /** 日時や期限の条件を再現するために使う検証時刻。 */
  private final AtomicReference<Instant> now;

  /** 検証に使うタイムゾーン。 */
  private final ZoneId zone;

  /** 検証条件に必要な値を受け取り、AdjustableClockを用意する。 */
  public AdjustableClock(Instant start) {
    this(new AtomicReference<>(start), ZoneOffset.UTC);
  }

  /** 検証条件に必要な値を受け取り、AdjustableClockを用意する。 */
  private AdjustableClock(AtomicReference<Instant> now, ZoneId zone) {
    this.now = now;
    this.zone = zone;
  }

  /** {@code advance} の条件を再現するため、検証用の操作や状態を扱う。 */
  public void advance(Duration duration) {
    now.updateAndGet(value -> value.plus(duration));
  }

  /** {@code instant} の条件を再現するための補助情報を返す。 */
  @Override
  public Instant instant() {
    return now.get();
  }

  /** 検証用に、機能の提供で参照するタイムゾーンを返す。 */
  @Override
  public ZoneId getZone() {
    return zone;
  }

  /** 検証用に、指定されたタイムゾーンを反映した情報を作成する。 */
  @Override
  public Clock withZone(ZoneId value) {
    return new AdjustableClock(now, value);
  }
}
