package dev.mikoto2000.rei.temporal;

/** 時刻と期間の解釈に必要なMonotonic時刻取得元を扱う。 */
public interface MonotonicTimeSource {
  /** 時刻と期間の解釈で用いるnano時刻を返す。 */
  long nanoTime();

  /** 時刻と期間の解釈で用いるシステムを返す。 */
  static MonotonicTimeSource system() {
    return System::nanoTime;
  }
}
