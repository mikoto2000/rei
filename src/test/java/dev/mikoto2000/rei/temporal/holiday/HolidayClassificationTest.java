package dev.mikoto2000.rei.temporal.holiday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 公式CSVから選択した固定年で、移動・制度改正・特例を回帰検証する。 */
class HolidayClassificationTest {
  @TempDir Path directory;

  JapaneseHolidayService fixture() throws Exception {
    Path file = directory.resolve("syukujitsu.csv");
    try (var input =
        getClass().getResourceAsStream("/holidays/cabinet-office-selected-years.csv")) {
      Files.copy(java.util.Objects.requireNonNull(input), file);
    }
    return new JapaneseHolidayService(
        file,
        Set.of(
            1972, 1973, 1985, 1986, 1987, 1988, 1998, 2006, 2007, 2009, 2019, 2020, 2021, 2026,
            2027),
        Clock.systemUTC());
  }

  @ParameterizedTest
  @CsvSource({
    "1972-05-01,NOT_HOLIDAY",
    "1973-02-12,NOT_HOLIDAY",
    "1973-04-30,SUBSTITUTE_HOLIDAY",
    "1985-05-04,NOT_HOLIDAY",
    "1986-05-04,NOT_HOLIDAY",
    "1987-05-04,SUBSTITUTE_HOLIDAY",
    "1988-05-04,CITIZENS_HOLIDAY",
    "1998-05-06,NOT_HOLIDAY",
    "2006-05-04,CITIZENS_HOLIDAY",
    "2007-05-04,HOLIDAY",
    "2009-05-06,SUBSTITUTE_HOLIDAY",
    "2019-04-30,CITIZENS_HOLIDAY",
    "2019-05-01,HOLIDAY",
    "2019-05-02,CITIZENS_HOLIDAY",
    "2020-07-20,NOT_HOLIDAY",
    "2020-07-23,HOLIDAY",
    "2020-07-24,HOLIDAY",
    "2020-08-10,HOLIDAY",
    "2020-10-12,NOT_HOLIDAY",
    "2021-07-19,NOT_HOLIDAY",
    "2021-07-22,HOLIDAY",
    "2021-07-23,HOLIDAY",
    "2021-08-08,HOLIDAY",
    "2021-08-09,SUBSTITUTE_HOLIDAY",
    "2021-08-11,NOT_HOLIDAY",
    "2026-05-06,SUBSTITUTE_HOLIDAY",
    "2026-09-22,CITIZENS_HOLIDAY",
    "2026-11-23,HOLIDAY",
    "2026-12-31,NOT_HOLIDAY",
    "2027-03-22,SUBSTITUTE_HOLIDAY",
    "1971-12-31,UNKNOWN",
    "2028-05-03,UNKNOWN"
  })
  void matchesOfficialHistoricalRowsAndCompleteYearBoundaries(String date, HolidayStatus expected)
      throws Exception {
    assertEquals(expected, fixture().getHolidayInfo(LocalDate.parse(date)).status());
  }

  @Test
  void neverInventsMissingHolidaysOrLegalClassification() throws Exception {
    Path file =
        Files.writeString(
            directory.resolve("syukujitsu.csv"),
            HolidayCsvTest.HEADER
                + "\n1972/4/30,天皇誕生日\n1972/5/1,休日\n1985/5/3,憲法記念日\n1985/5/4,休日\n1985/5/5,こどもの日\n2026/5/6,休日\n",
            HolidayCsvTest.MS932);
    var service = new JapaneseHolidayService(file, Set.of(), Clock.systemUTC());
    for (String date : new String[] {"1972-05-01", "1985-05-04", "2026-05-06"}) {
      var info = service.getHolidayInfo(LocalDate.parse(date));
      assertEquals(HolidayStatus.UNCLASSIFIED_DAY_OFF, info.status());
      assertTrue(info.isNationalHolidayOrDayOff());
      assertEquals("休日", info.name());
    }
    assertEquals(HolidayStatus.UNKNOWN, service.getHolidayInfo(LocalDate.of(2026, 5, 7)).status());
  }
}
