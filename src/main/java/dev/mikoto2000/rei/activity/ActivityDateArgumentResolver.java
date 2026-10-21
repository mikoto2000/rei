package dev.mikoto2000.rei.activity;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * 作業状況の把握で受け渡す作業状況日付引数対象の解決を表す。
 *
 * <p>Single-day arguments in the journal timezone; independent of command parsing.
 *
 * @param clock 判定や記録に使う時刻の取得元
 */
public record ActivityDateArgumentResolver(Clock clock) {
  /** 使用状況。 */
  public static final String USAGE = "日付は today、yesterday、または YYYY-MM-DD 形式で指定してください。";

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  public LocalDate resolve(String input) {
    var today = LocalDate.now(clock);
    LocalDate date;
    if (input == null || input.isEmpty() || input.equals("today")) date = today;
    else if (input.equals("yesterday")) date = today.minusDays(1);
    else {
      if (!input.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeException(USAGE);
      try {
        date = LocalDate.parse(input);
      } catch (DateTimeException e) {
        throw new DateTimeException(USAGE);
      }
    }
    if (date.isAfter(today)) throw new DateTimeException(date + " は未来の日付です。");
    return date;
  }
}
