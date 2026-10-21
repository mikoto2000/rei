package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.command.ProfileCommand;
import dev.mikoto2000.rei.event.ProfileEventLogStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 利用者情報レポートをWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/profile")
public final class ProfileReportController {
  /** レポート。 */
  private final ProfileCommand report;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /** 指定された値や利用先を使って、ProfileReportControllerを初期化する。 */
  public ProfileReportController(ProfileEventLogStore store) {
    report = new ProfileCommand(store);
  }

  /** Web APIによる操作で用いる要約を返す。 */
  @GetMapping("/report")
  public Text summary() {
    return new Text(report.renderSummary());
  }

  /** Web APIによる操作で用いるパスを返す。 */
  @GetMapping("/path")
  public Text path() {
    return new Text(report.renderPath());
  }

  /** Web APIによる操作で用いるchartを返す。 */
  @GetMapping("/chart")
  public Text chart(
      @RequestParam(defaultValue = "60") long bucketSeconds,
      @RequestParam(defaultValue = "40") int width) {
    return new Text(report.renderChart(bucketSeconds, width));
  }

  /** Web APIによる操作で用いるmermaidを返す。 */
  @GetMapping("/mermaid")
  public Text mermaid(@RequestParam(defaultValue = "12") int limit) {
    return new Text(report.renderMermaid(limit));
  }
}
