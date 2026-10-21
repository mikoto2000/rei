package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.activity.ActivityPeriodAnalysis;
import dev.mikoto2000.rei.activity.ActivityQueryLimitException;
import dev.mikoto2000.rei.activity.ActivityTimeline;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 作業状況期間をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ActivityPeriodController {
  /**
   * Web APIによる操作で受け渡すレポートを表す。
   *
   * @param schemaVersion データ形式形式のバージョン
   * @param scope 対象範囲
   * @param period 期間
   * @param anchorDate 基準位置日付
   * @param zone タイムゾーン
   * @param partial 部分的な結果
   * @param report レポート
   */
  public record Report(
      int schemaVersion,
      String scope,
      String period,
      LocalDate anchorDate,
      String zone,
      boolean partial,
      String report) {}

  /** 時系列の記録。 */
  private final ObjectProvider<ActivityTimeline> timeline;

  /** 指定された値や利用先を使って、ActivityPeriodControllerを初期化する。 */
  public ActivityPeriodController(ObjectProvider<ActivityTimeline> timeline) {
    this.timeline = timeline;
  }

  /** Web APIによる操作で用いる期間を返す。 */
  @GetMapping("/api/v1/activity/period")
  public Report period(
      @RequestParam(defaultValue = "WEEK") String period,
      @RequestParam(required = false) String date) {
    var selected = ActivityPeriodAnalysis.Period.valueOf(period.toUpperCase(Locale.ROOT));
    if (date != null) {
      if (date.length() != 10) throw new IllegalArgumentException("Use an ISO calendar date");
      date = LocalDate.parse(date).toString();
    }
    var source = timeline.getIfAvailable();
    if (source == null)
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Activity is disabled");
    try {
      var comparison = source.periodComparisonBounded(selected, date);
      String report =
          dev.mikoto2000.rei.event.CredentialRedactor.redact(
              source.formatPeriodComparison(comparison));
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (report.length() > 32768) throw new ActivityQueryLimitException();
      return new Report(
          1,
          "LOCAL_DEVICE_OBSERVATIONS",
          selected.name(),
          comparison.current().range().firstDate(),
          comparison.zone().toString(),
          comparison.current().range().partial(),
          report);
    } catch (DateTimeException invalid) {
      throw new IllegalArgumentException("Invalid Activity calendar date");
    } catch (ActivityQueryLimitException limit) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Activity analysis evidence limit exceeded");
    } catch (UnsupportedOperationException unsupported) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Bounded Activity analysis is unavailable");
    }
  }
}
