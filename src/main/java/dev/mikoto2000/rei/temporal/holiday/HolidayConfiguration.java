package dev.mikoto2000.rei.temporal.holiday;

import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 既存のグローバル保存先とClockを使い、全Project共有の祝日サービスを構成する。 */
@Configuration(proxyBeanMethods = false)
public class HolidayConfiguration {
  @Bean
  JapaneseHolidayService japaneseHolidayService(Clock clock,
      @Value("${rei.holidays.complete-years:}") String completeYears) {
    return new JapaneseHolidayService(ReiDataDirectory.current().resolve("syukujitsu.csv"), parseYears(completeYears), clock);
  }

  static Set<Integer> parseYears(String value) {
    if (value.isBlank()) return Set.of();
    var years = new HashSet<Integer>();
    for (String entry : value.split(",", -1)) {
      String token = entry.strip();
      if (!token.matches("[0-9]{4}(-[0-9]{4})?"))
        throw new IllegalArgumentException("rei.holidays.complete-years requires years or year ranges");
      String[] range = token.split("-");
      int from = Integer.parseInt(range[0]);
      int to = range.length == 1 ? from : Integer.parseInt(range[1]);
      if (from < 1 || to < from || to - from > 500)
        throw new IllegalArgumentException("rei.holidays.complete-years range is invalid or exceeds 501 years");
      for (int year = from; year <= to; year++) years.add(year);
      if (years.size() > 501) throw new IllegalArgumentException("Too many confirmed holiday years");
    }
    return Set.copyOf(years);
  }
}
