package dev.mikoto2000.rei.temporal.holiday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JapaneseHolidayServiceTest {
  @TempDir Path directory;
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-28T15:00:00Z"), ZoneOffset.UTC);

  Path csv(String rows) throws Exception {
    return Files.writeString(
        directory.resolve("syukujitsu.csv"),
        HolidayCsvTest.HEADER + "\r\n" + rows,
        HolidayCsvTest.MS932);
  }

  JapaneseHolidayService service(Path file, Integer... years) {
    return new JapaneseHolidayService(file, Set.of(years), clock);
  }

  @Test
  void recordsFailureEvenWhenIoExceptionHasNoMessage() throws Exception {
    Path file = csv("2026/11/23,勤労感謝の日\n");
    var fail = new java.util.concurrent.atomic.AtomicBoolean();
    var service = new JapaneseHolidayService(file, Set.of(2026), clock, path -> {
      if (fail.get()) throw new java.io.IOException();
      return Files.readAllBytes(path);
    });
    fail.set(true);
    var status = service.reload();
    assertFalse(status.current());
    assertNotNull(status.lastError());
    assertEquals(1, status.entryCount());
  }

  @Test
  void cachesQueriesButExplicitReloadReadsSameMetadataAgain() throws Exception {
    Path file = csv("2026/11/23,勤労感謝の日\n");
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var service = new JapaneseHolidayService(file, Set.of(2026), clock, path -> {
      reads.incrementAndGet();
      return Files.readAllBytes(path);
    });
    for (int i = 0; i < 20; i++) service.getHolidayInfo(LocalDate.of(2026,11,23));
    assertEquals(1, reads.get());
    assertTrue(service.reload().current());
    assertEquals(2, reads.get());
    assertEquals(366, java.time.temporal.ChronoUnit.DAYS.between(
        service.listHolidays(LocalDate.of(2026,1,1), LocalDate.of(2027,1,1)).from(),
        service.listHolidays(LocalDate.of(2026,1,1), LocalDate.of(2027,1,1)).to()) + 1);
  }

  @Test
  void detectsCreationAfterStartupAndMissingYearsInsideRecordedRange() throws Exception {
    Path file = directory.resolve("syukujitsu.csv");
    var service = service(file, 2025,2026,2027);
    csv("2025/1/1,元日\n2027/1/1,元日\n");
    assertEquals(HolidayStatus.HOLIDAY, service.getHolidayInfo(LocalDate.of(2027,1,1)).status());
    assertEquals(HolidayStatus.UNKNOWN, service.getHolidayInfo(LocalDate.of(2026,11,24)).status());
    assertEquals(Set.of(2025,2027), service.getHolidayDataStatus().completeYears());
  }

  @Test
  void rejectsFilesThatChangeWhileBeingReadAndNeverPublishesTheirPartialData() throws Exception {
    Path file = csv("2026/11/23,勤労感謝の日\n");
    var change = new java.util.concurrent.atomic.AtomicBoolean();
    var service = new JapaneseHolidayService(file, Set.of(2026), clock, path -> {
      byte[] result = Files.readAllBytes(path);
      if (change.getAndSet(false)) Files.writeString(path,
          HolidayCsvTest.HEADER + "\n2026/1/1,元日\n2026/11/23,勤労感謝の日\n", HolidayCsvTest.MS932);
      return result;
    });
    change.set(true);
    var failed = service.reload();
    assertFalse(failed.current());
    assertEquals(1, failed.entryCount());
    assertTrue(failed.lastError().contains("changed during loading"));
    assertEquals(2, service.getHolidayDataStatus().entryCount());
  }

  @Test
  void refusesOversizeOrNonRegularSourcesWithoutFailingStartup() throws Exception {
    var notFile = service(directory, 2026);
    assertFalse(notFile.getHolidayDataStatus().loaded());
    Path large = directory.resolve("large.csv");
    try (var output = new java.io.RandomAccessFile(large.toFile(), "rw")) {
      output.setLength(2 * 1024 * 1024 + 1);
    }
    var oversize = service(large, 2026);
    assertFalse(oversize.getHolidayDataStatus().loaded());
    assertTrue(oversize.getHolidayDataStatus().lastError().contains("2 MiB"));
  }

  @Test
  void missingDataIsUnknownAndDoesNotPreventConstruction() {
    var service = service(directory.resolve("syukujitsu.csv"), 2026);
    assertEquals(
        HolidayStatus.UNKNOWN, service.getHolidayInfo(LocalDate.of(2026, 11, 23)).status());
    assertNull(service.getHolidayInfo(LocalDate.of(2026, 11, 23)).isNationalHolidayOrDayOff());
    assertFalse(service.getHolidayDataStatus().fileExists());
    assertNotNull(service.getHolidayDataStatus().lastError());
  }

  @Test
  void separatesCalendarYearCoverageFromDateExtremaAndWeekend() throws Exception {
    var service =
        service(
            csv(
                "2026/9/21,敬老の日\n2026/9/22,休日\n2026/9/23,秋分の日\n2026/11/23,勤労感謝の日\n2027/3/21,春分の日\n2027/3/22,休日\n"),
            2026,
            2027);
    assertEquals("勤労感謝の日", service.getHolidayInfo(LocalDate.of(2026, 11, 23)).name());
    assertEquals(
        HolidayStatus.CITIZENS_HOLIDAY, service.getHolidayInfo(LocalDate.of(2026, 9, 22)).status());
    assertEquals(
        HolidayStatus.SUBSTITUTE_HOLIDAY,
        service.getHolidayInfo(LocalDate.of(2027, 3, 22)).status());
    assertEquals(
        HolidayStatus.NOT_HOLIDAY, service.getHolidayInfo(LocalDate.of(2026, 12, 31)).status());
    var saturday = service.getHolidayInfo(LocalDate.of(2026, 11, 28));
    assertTrue(saturday.isWeekend());
    assertFalse(saturday.isNationalHolidayOrDayOff());
    assertEquals(
        HolidayStatus.NOT_HOLIDAY, service.getHolidayInfo(LocalDate.of(2026, 11, 24)).status());
    assertEquals(HolidayStatus.UNKNOWN, service.getHolidayInfo(LocalDate.of(2028, 5, 3)).status());
  }

  @Test
  void unconfirmedYearsHaveKnownHolidaysButUnknownAbsentDates() throws Exception {
    var service = service(csv("2026/1/1,元日\n"));
    assertEquals(HolidayStatus.HOLIDAY, service.getHolidayInfo(LocalDate.of(2026, 1, 1)).status());
    assertEquals(
        HolidayStatus.UNKNOWN, service.getHolidayInfo(LocalDate.of(2026, 11, 24)).status());
    assertTrue(service.getHolidayDataStatus().completeYears().isEmpty());
  }

  @Test
  void reloadsOnlyCompleteValidDataAndRetainsLastGoodSnapshotOnFailure() throws Exception {
    var file = csv("2026/11/23,勤労感謝の日\n");
    var service = service(file, 2026);
    csv("2026/11/23,勤労感謝の日\ninvalid\n");
    assertFalse(service.reload().current());
    assertEquals(1, service.getHolidayDataStatus().entryCount());
    assertEquals(
        HolidayStatus.HOLIDAY, service.getHolidayInfo(LocalDate.of(2026, 11, 23)).status());
    assertFalse(service.getHolidayInfo(LocalDate.of(2026, 11, 23)).dataCurrent());
    csv("2026/1/1,元日\n2026/11/23,勤労感謝の日\n");
    assertEquals("元日", service.getHolidayInfo(LocalDate.of(2026, 1, 1)).name());
    assertTrue(service.getHolidayDataStatus().current());
    assertNull(service.getHolidayDataStatus().lastError());
    assertEquals(clock.instant(), service.getHolidayDataStatus().lastSuccess());
    Files.delete(file);
    assertFalse(service.getHolidayDataStatus().current());
    assertEquals(2, service.getHolidayDataStatus().entryCount());
  }

  @Test
  void reportsUnknownIntervalsAndRejectsUnboundedOrReversedRanges() throws Exception {
    var service = service(csv("2026/11/23,勤労感謝の日\n2027/1/1,元日\n"), 2026);
    var result = service.listHolidays(LocalDate.of(2026, 11, 1), LocalDate.of(2027, 1, 31));
    assertEquals(2, result.holidays().size());
    assertEquals(LocalDate.of(2027, 1, 1), result.unknownPeriods().getFirst().from());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.listHolidays(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 1, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.listHolidays(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 2)));
  }

  @Test
  void concurrentReadersKeepOldSnapshotDuringReloadAndPermissionErrorsAreRecoverable()
      throws Exception {
    Path file = csv("2026/11/23,勤労感謝の日\n");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var block = new java.util.concurrent.atomic.AtomicBoolean();
    var deny = new java.util.concurrent.atomic.AtomicBoolean();
    var service =
        new JapaneseHolidayService(
            file,
            Set.of(2026),
            clock,
            path -> {
              if (deny.get()) throw new java.nio.file.AccessDeniedException(path.toString());
              if (block.get()) {
                entered.countDown();
                try {
                  if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("timeout");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new java.io.IOException(e);
                }
              }
              return Files.readAllBytes(path);
            });
    csv("2026/1/1,元日\n2026/11/23,勤労感謝の日\n");
    block.set(true);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var loading = executor.submit(service::reload);
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(1, service.getHolidayDataStatus().entryCount());
        assertEquals(
            HolidayStatus.HOLIDAY, service.getHolidayInfo(LocalDate.of(2026, 11, 23)).status());
        assertNull(service.getHolidayInfo(LocalDate.of(2026, 1, 1)).name());
      } finally {
        release.countDown();
      }
      assertEquals(2, loading.get(5, TimeUnit.SECONDS).entryCount());
    }
    deny.set(true);
    assertFalse(service.reload().current());
    assertEquals(2, service.getHolidayDataStatus().entryCount());
    var neverRead =
        new JapaneseHolidayService(
            file,
            Set.of(2026),
            clock,
            path -> {
              throw new java.nio.file.AccessDeniedException(path.toString());
            });
    assertEquals(
        HolidayStatus.UNKNOWN, neverRead.getHolidayInfo(LocalDate.of(2026, 11, 23)).status());
  }
}
