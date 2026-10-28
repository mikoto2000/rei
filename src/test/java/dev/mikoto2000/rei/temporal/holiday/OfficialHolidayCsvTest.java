package dev.mikoto2000.rei.temporal.holiday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 利用者が取得済みの公式CSVに対して明示実行する、ネットワーク不要の手動検証。 */
@EnabledIfSystemProperty(named = "holiday.csv", matches = ".+")
class OfficialHolidayCsvTest {
  @Test
  void validatesDownloadedOfficialCsv() throws Exception {
    Path csv = Path.of(System.getProperty("holiday.csv"));
    var dates = HolidayCsv.parse(Files.readAllBytes(csv));
    assertTrue(dates.size() > 1000);
    var service = new JapaneseHolidayService(csv, Set.of(2026, 2027), Clock.systemUTC());
    assertTrue(service.getHolidayDataStatus().current());
    assertEquals("勤労感謝の日", service.getHolidayInfo(LocalDate.of(2026, 11, 23)).name());
    assertEquals(
        HolidayStatus.CITIZENS_HOLIDAY, service.getHolidayInfo(LocalDate.of(2026, 9, 22)).status());
    assertEquals(
        HolidayStatus.SUBSTITUTE_HOLIDAY,
        service.getHolidayInfo(LocalDate.of(2027, 3, 22)).status());
  }
}
