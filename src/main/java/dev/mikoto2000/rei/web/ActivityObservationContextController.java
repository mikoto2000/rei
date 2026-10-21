package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.activity.ActivityQueryLimitException;
import dev.mikoto2000.rei.activity.ActivityWorkContextService;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.time.DateTimeException;
import java.time.LocalDate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 作業状況観測内容文脈をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ActivityObservationContextController {
  /**
   * Web APIによる操作で受け渡すレポートを表す。
   *
   * @param schemaVersion データ形式形式のバージョン
   * @param scope 対象範囲
   * @param projectId 所属プロジェクトの識別子
   * @param date 日付
   * @param zone タイムゾーン
   * @param partial 部分的な結果
   * @param missingContextRecords 未指定文脈記録一覧
   * @param linkedObservations linked観測内容一覧
   * @param report レポート
   */
  public record Report(
      int schemaVersion,
      String scope,
      String projectId,
      LocalDate date,
      String zone,
      boolean partial,
      int missingContextRecords,
      int linkedObservations,
      String report) {}

  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /** サービス一覧。 */
  private final ObjectProvider<ActivityWorkContextService> services;

  /** 指定された値や利用先を使って、ActivityObservationContextControllerを初期化する。 */
  public ActivityObservationContextController(
      ProjectRegistry projects, ObjectProvider<ActivityWorkContextService> services) {
    this.projects = projects;
    this.services = services;
  }

  /** Web APIによる操作で用いるレポートを返す。 */
  @GetMapping("/api/v1/projects/{projectId}/activity/observation-context")
  public Report report(
      @PathVariable String projectId, @RequestParam(defaultValue = "today") String date) {
    projects.resolveById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    var service = services.getIfAvailable();
    if (service == null)
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Activity is disabled");
    try {
      var links = service.observationLinksBounded(projectId, date);
      String report =
          dev.mikoto2000.rei.event.CredentialRedactor.redact(
              service.formatObservationBounded(links.links()));
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (report.length() > 32768) throw new ActivityQueryLimitException();
      return new Report(
          1,
          "PROJECT_OBSERVATION_CONTEXT",
          projectId,
          links.date(),
          links.zone(),
          links.partial(),
          links.missingContextRecords(),
          links.links().size(),
          report);
    } catch (DateTimeException invalid) {
      throw new IllegalArgumentException("Invalid Activity calendar date");
    } catch (ActivityQueryLimitException limit) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Activity context evidence limit exceeded");
    } catch (UnsupportedOperationException unsupported) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Bounded Activity context unavailable");
    }
  }
}
