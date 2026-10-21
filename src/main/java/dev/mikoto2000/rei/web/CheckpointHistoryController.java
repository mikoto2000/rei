package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.checkpoint.CheckpointHistoryReport;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 再開用の記録履歴をWeb APIの操作として受け付ける。
 *
 * <p>Global checkpoint capacity uses the same authenticated backend API as storage maintenance.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/checkpoints/history")
public final class CheckpointHistoryController {
  /** サービス。 */
  private final PersistentCheckpointService service;

  /** 指定された値や利用先を使って、CheckpointHistoryControllerを初期化する。 */
  public CheckpointHistoryController(PersistentCheckpointService service) {
    this.service = service;
  }

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {
    /** 受け取った情報からTextを作成する。 */
    public Text {
      text = dev.mikoto2000.rei.event.CredentialRedactor.redact(text);
    }
  }

  /**
   * Web APIによる操作で受け渡す反映要求を表す。
   *
   * @param apply 反映
   */
  public record ApplyRequest(boolean apply) implements StrictApiRequest {}

  /** 対象の現在の状態を、照会用の情報として返す。 */
  @GetMapping("/status")
  public Text status() {
    return new Text(CheckpointHistoryReport.usage(service.historyUsage()));
  }

  /** Web APIによる操作で用いる事前確認を返す。 */
  @GetMapping("/cleanup")
  public Text preview() {
    return new Text(CheckpointHistoryReport.cleanup(service.cleanupHistory(false)));
  }

  /** 指定された内容を、現在の対象へ反映する。 */
  @PostMapping("/cleanup")
  public Text apply(@RequestBody ApplyRequest request) {
    if (request == null || !request.apply())
      throw new IllegalArgumentException(
          "整理候補を /checkpoint cleanup で確認し、実行する場合は /checkpoint cleanup --apply を指定してください。");
    return new Text(CheckpointHistoryReport.cleanup(service.cleanupHistory(true)));
  }
}
