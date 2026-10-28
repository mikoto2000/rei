package dev.mikoto2000.rei.temporal.holiday;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 全Projectで共有する内閣府CSVカレンダー。読込中も直前の正常データを参照できる。 */
public final class JapaneseHolidayService {
  private static final Logger LOG = LoggerFactory.getLogger(JapaneseHolidayService.class);
  private static final int MAX_BYTES = 2 * 1024 * 1024;
  private final Path path;
  private final Set<Integer> confirmedYears;
  private final Clock clock;
  private final CsvReader reader;
  private final ReentrantLock reloadLock = new ReentrantLock();
  private volatile State state = new State(null, null, null, null, "Not loaded");

  /** LLMへ公開してもよい日付判定。UNKNOWNの休日フラグはnull。 */
  public record HolidayInfo(
      LocalDate date,
      HolidayStatus status,
      String name,
      DayOfWeek dayOfWeek,
      boolean isWeekend,
      Boolean isNationalHolidayOrDayOff,
      String basis,
      String source,
      String unknownReason,
      boolean dataCurrent) {}

  /** 休日の欠落を通常日と誤認しないための、完全性未確認の期間。 */
  public record UnknownPeriod(LocalDate from, LocalDate to, String reason) {}

  /** 一回のスナップショットから取得した一覧とデータ不足。 */
  public record HolidayList(
      LocalDate from,
      LocalDate to,
      List<HolidayInfo> holidays,
      List<UnknownPeriod> unknownPeriods,
      boolean dataCurrent) {
    public HolidayList {
      holidays = List.copyOf(holidays);
      unknownPeriods = List.copyOf(unknownPeriods);
    }
  }

  /** 内部診断専用。絶対パスとファイル例外はLLMツールには渡さない。 */
  public record HolidayDataStatus(
      Path csvPath,
      boolean fileExists,
      boolean loaded,
      Instant lastSuccess,
      Instant lastAttempt,
      LocalDate firstDate,
      LocalDate lastDate,
      Set<Integer> recordedYears,
      Set<Integer> completeYears,
      int entryCount,
      String lastError,
      boolean current) {
    public HolidayDataStatus {
      recordedYears = Set.copyOf(recordedYears);
      completeYears = Set.copyOf(completeYears);
    }
  }

  private record Fingerprint(
      boolean exists, FileTime modified, long size, String key, String error) {}

  private record Data(
      NavigableMap<LocalDate, String> dates,
      Set<Integer> years,
      Set<Integer> completeYears,
      Fingerprint fingerprint,
      Instant loadedAt) {}

  private record State(
      Data data, Fingerprint attemptedFile, Instant attemptedAt, Instant successAt, String error) {}

  @FunctionalInterface
  interface CsvReader {
    byte[] read(Path path) throws IOException;
  }

  /** Clockは診断日時に使用し、祝日そのものはLocalDateで問い合わせる。 */
  public JapaneseHolidayService(Path path, Set<Integer> confirmedYears, Clock clock) {
    this(path, confirmedYears, clock, JapaneseHolidayService::readBounded);
  }

  JapaneseHolidayService(Path path, Set<Integer> confirmedYears, Clock clock, CsvReader reader) {
    this.path = path.toAbsolutePath().normalize();
    this.confirmedYears = Set.copyOf(confirmedYears);
    this.clock = Objects.requireNonNull(clock);
    this.reader = Objects.requireNonNull(reader);
    reload();
  }

  private static byte[] readBounded(Path path) throws IOException {
    try (var input = Files.newInputStream(path)) {
      byte[] bytes = input.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) throw new IOException("Holiday CSV exceeds 2 MiB");
      return bytes;
    }
  }

  private Fingerprint fingerprint() {
    try {
      var attributes = Files.readAttributes(path, BasicFileAttributes.class);
      return new Fingerprint(
          true,
          attributes.lastModifiedTime(),
          attributes.size(),
          String.valueOf(attributes.fileKey()),
          attributes.isRegularFile() ? null : "CSV is not a regular file");
    } catch (java.nio.file.NoSuchFileException e) {
      return new Fingerprint(false, null, 0, null, "CSV is missing");
    } catch (IOException | SecurityException e) {
      return new Fingerprint(
          false, null, 0, null, "Cannot inspect CSV: " + e.getClass().getSimpleName());
    }
  }

  private State refresh() {
    var file = fingerprint();
    if (!file.equals(state.attemptedFile()) && reloadLock.tryLock()) {
      try {
        if (!file.equals(state.attemptedFile())) load(file);
      } finally {
        reloadLock.unlock();
      }
    }
    return state;
  }

  /** メタデータが同一でも必ず再読込する。正常なスナップショットだけを公開する。 */
  public HolidayDataStatus reload() {
    reloadLock.lock();
    try {
      load(fingerprint());
      return status(state, fingerprint());
    } finally {
      reloadLock.unlock();
    }
  }

  private void load(Fingerprint before) {
    Instant attempt = clock.instant();
    State previous = state;
    try {
      if (before.error() != null) throw new IOException(before.error());
      if (before.size() > MAX_BYTES) throw new IOException("Holiday CSV exceeds 2 MiB");
      var dates = HolidayCsv.parse(reader.read(path));
      if (!before.equals(fingerprint()))
        throw new IOException("CSV changed during loading; retry after replacement");
      var years = new TreeSet<Integer>();
      dates.keySet().forEach(date -> years.add(date.getYear()));
      var complete = new TreeSet<>(years);
      complete.retainAll(confirmedYears);
      Instant success = clock.instant();
      var data = new Data(dates, Set.copyOf(years), Set.copyOf(complete), before, success);
      state = new State(data, before, attempt, success, null);
    } catch (IOException | SecurityException e) {
      String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      state = new State(previous.data(), before, attempt, previous.successAt(), error);
      LOG.warn(
          "Holiday CSV reload failed; retaining previous data: {}", e.getClass().getSimpleName());
    }
  }

  /** 全体の診断情報。呼出し時にファイル更新も確認する。 */
  public HolidayDataStatus getHolidayDataStatus() {
    State current = refresh();
    return status(current, fingerprint());
  }

  private boolean isCurrent(State snapshot, Fingerprint file) {
    return snapshot.data() != null
        && snapshot.error() == null
        && snapshot.data().fingerprint().equals(file);
  }

  private HolidayDataStatus status(State snapshot, Fingerprint file) {
    Data data = snapshot.data();
    return new HolidayDataStatus(
        path,
        file.exists(),
        data != null,
        snapshot.successAt(),
        snapshot.attemptedAt(),
        data == null ? null : data.dates().firstKey(),
        data == null ? null : data.dates().lastKey(),
        data == null ? Set.of() : data.years(),
        data == null ? Set.of() : data.completeYears(),
        data == null ? 0 : data.dates().size(),
        snapshot.error(),
        isCurrent(snapshot, file));
  }

  /** CSVに存在する休日と、確認済みの年の通常日だけを確定する。 */
  public HolidayInfo getHolidayInfo(LocalDate date) {
    Objects.requireNonNull(date, "date");
    State snapshot = refresh();
    return info(date, snapshot, isCurrent(snapshot, fingerprint()));
  }

  private HolidayInfo info(LocalDate date, State snapshot, boolean current) {
    Data data = snapshot.data();
    String name = data == null ? null : data.dates().get(date);
    HolidayStatus status =
        name != null
            ? classify(date, data.dates())
            : data != null && data.completeYears().contains(date.getYear())
                ? HolidayStatus.NOT_HOLIDAY
                : HolidayStatus.UNKNOWN;
    String reason =
        status == HolidayStatus.UNKNOWN
            ? data == null
                ? "NO_VALID_CSV"
                : data.years().contains(date.getYear())
                    ? "YEAR_COMPLETENESS_UNCONFIRMED"
                    : "YEAR_NOT_RECORDED"
            : null;
    return new HolidayInfo(
        date,
        status,
        name,
        date.getDayOfWeek(),
        date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY,
        status == HolidayStatus.UNKNOWN ? null : status != HolidayStatus.NOT_HOLIDAY,
        name != null
            ? status == HolidayStatus.UNCLASSIFIED_DAY_OFF
                ? "CSV_ENTRY_CLASSIFICATION_UNCERTAIN"
                : "CSV_ENTRY_WITH_HOLIDAY_LAW_CLASSIFICATION"
            : status == HolidayStatus.NOT_HOLIDAY
                ? "ABSENT_IN_CONFIRMED_COMPLETE_YEAR"
                : "INSUFFICIENT_DATA",
        "CABINET_OFFICE_CSV",
        reason,
        current);
  }

  /** 応答を両端込み366日以内に抑える。収録日だけでなく不足期間も返す。 */
  public HolidayList listHolidays(LocalDate from, LocalDate to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    long days = ChronoUnit.DAYS.between(from, to);
    if (days < 0 || days >= 366)
      throw new IllegalArgumentException("Range must be ordered and at most 366 days inclusive");
    State snapshot = refresh();
    boolean current = isCurrent(snapshot, fingerprint());
    Data data = snapshot.data();
    var holidays = new ArrayList<HolidayInfo>();
    if (data != null)
      data.dates()
          .subMap(from, true, to, true)
          .keySet()
          .forEach(date -> holidays.add(info(date, snapshot, current)));
    var unknown = new ArrayList<UnknownPeriod>();
    for (int year = from.getYear(); year <= to.getYear(); year++) {
      if (data == null || !data.completeYears().contains(year)) {
        LocalDate start = year == from.getYear() ? from : LocalDate.of(year, 1, 1);
        LocalDate end = year == to.getYear() ? to : LocalDate.of(year, 12, 31);
        unknown.add(
            new UnknownPeriod(
                start, end, data == null ? "NO_VALID_CSV" : "YEAR_COMPLETENESS_UNCONFIRMED"));
      }
    }
    return new HolidayList(from, to, holidays, unknown, current);
  }

  private static boolean namedHoliday(LocalDate date, Map<LocalDate, String> dates) {
    String name = dates.get(date);
    return name != null && !name.equals("休日");
  }

  private static HolidayStatus classify(LocalDate date, Map<LocalDate, String> dates) {
    if (!"休日".equals(dates.get(date))) return HolidayStatus.HOLIDAY;
    boolean modern = !date.isBefore(LocalDate.of(2007, 1, 1));
    boolean substitute = false;
    if (!date.isBefore(LocalDate.of(1973, 4, 12))) {
      LocalDate preceding = date.minusDays(1);
      if (modern) {
        while (namedHoliday(preceding, dates) && preceding.getDayOfWeek() != DayOfWeek.SUNDAY)
          preceding = preceding.minusDays(1);
      }
      substitute = namedHoliday(preceding, dates) && preceding.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
    boolean citizens =
        !date.isBefore(LocalDate.of(1985, 12, 27))
            && namedHoliday(date.minusDays(1), dates)
            && namedHoliday(date.plusDays(1), dates)
            && (modern || (date.getDayOfWeek() != DayOfWeek.SUNDAY && !substitute));
    if (substitute && !citizens) return HolidayStatus.SUBSTITUTE_HOLIDAY;
    if (citizens && !substitute) return HolidayStatus.CITIZENS_HOLIDAY;
    return HolidayStatus.UNCLASSIFIED_DAY_OFF;
  }
}
