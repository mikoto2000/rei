package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.checkpoint.ResumeOverview;
import dev.mikoto2000.rei.checkpoint.ResumeOverviewService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 再開概要をWeb APIの操作として受け付ける。
 *
 * <p>Uses the same stateless Bearer authentication and registered-project scope as other project
 * APIs.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public class ResumeOverviewController {
  /** サービス。 */
  private final ResumeOverviewService service;

  /** 指定された値や利用先を使って、ResumeOverviewControllerを初期化する。 */
  public ResumeOverviewController(ResumeOverviewService service) {
    this.service = service;
  }

  /**
   * Web APIによる操作で受け渡す起動時を表す。
   *
   * @param enabled 有効状態
   * @param preferredZone preferredタイムゾーン
   * @param projects プロジェクト一覧
   */
  public record Startup(
      boolean enabled, String preferredZone, List<ResumeOverview.ProjectCount> projects) {}

  /** Web APIによる操作で用いる起動時を返す。 */
  @GetMapping("/resume-overview")
  public Startup startup() {
    return new Startup(
        service.enabled(),
        service.preferredZone(),
        service.enabled() ? service.counts() : List.of());
  }

  /** Web APIによる操作で用いるプロジェクトを返す。 */
  @GetMapping("/projects/{projectId}/resume-overview")
  public ResumeOverview project(@PathVariable String projectId) {
    return service.overview(projectId);
  }
}
