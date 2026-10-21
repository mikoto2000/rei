package dev.mikoto2000.rei.activity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 日次要約のユースケースを実現する。 */
public final class DailySummaryService {
  /** 機能の構成。 */
  private final Supplier<SummaryThemeConfiguration> configuration;

  /** 書き込み先。 */
  private final DailySummaryWriter writer;

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(DailySummaryService.class);

  /** 指定された値や利用先を使って、DailySummaryServiceを初期化する。 */
  public DailySummaryService(Supplier<ProjectNameNormalizer> aliases, DailySummaryWriter writer) {
    this.configuration =
        () -> new SummaryThemeConfiguration(aliases.get(), SummaryThemeGroups.empty());
    this.writer = writer;
  }

  /** 指定された値や利用先を使って、DailySummaryServiceを初期化する。 */
  public DailySummaryService(ProjectAliasStore store, DailySummaryWriter writer) {
    this.configuration = store::snapshot;
    this.writer = writer;
  }

  /** 作業状況の把握で用いるローカルを返す。 */
  public static DailySummaryService local() {
    return new DailySummaryService(() -> new ProjectNameNormalizer(Map.of()), null);
  }

  /** 作業状況の把握で用いるプロジェクト名前一覧を返す。 */
  ProjectNameNormalizer projectNames() {
    return configuration.get().projects();
  }

  /** 対象の内容を、要点が分かる形にまとめる。 */
  public String summarize(
      LocalDate date,
      ActivityQueryRange range,
      ZoneId zone,
      double minimumConfidence,
      List<SummarySegment> segments) {
    if (segments.isEmpty()) return date + " の Activity は記録されていません。";
    log.debug("[summary-trace] source-segments date={} count={}", date, segments.size());
    var config = configuration.get();
    var aggregate =
        new DailySummaryAggregator(config.projects(), minimumConfidence, config.groups())
            .aggregate(date, range, zone, segments);
    if (aggregate.observedSeconds() == 0) return date + " の Activity は記録されていません。";
    var result = DailySummary.fallback(aggregate);
    String mode = "FALLBACK_DISABLED";
    if (writer != null)
      try {
        result = writer.write(aggregate).validated(aggregate);
        mode = "LLM_SUCCESS";
      } catch (Exception error) {
        mode = "FALLBACK";
        log.debug(
            "[summary-trace] writer-failure type={} cause={} timeout={}",
            error.getClass().getSimpleName(),
            error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName(),
            error.getMessage() != null
                && error.getMessage().startsWith("Timeout on blocking read"));
        log.warn(
            "Daily summary writing failed; using deterministic fallback ({})",
            error.getClass().getSimpleName());
      }
    log.debug("[summary-trace] writer-mode={}", mode);
    log.debug("[summary-trace] formatter-input result={}", result);
    return new DailySummaryFormatter().format(aggregate, result);
  }
}
