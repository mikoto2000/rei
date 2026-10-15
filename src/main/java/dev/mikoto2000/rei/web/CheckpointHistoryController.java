package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.checkpoint.CheckpointHistoryReport;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.*;

/** Global checkpoint capacity uses the same authenticated backend API as storage maintenance. */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/checkpoints/history")
public final class CheckpointHistoryController {
  private final PersistentCheckpointService service;

  public CheckpointHistoryController(PersistentCheckpointService service) {
    this.service = service;
  }

  public record Text(String text) {
    public Text {
      text = dev.mikoto2000.rei.event.CredentialRedactor.redact(text);
    }
  }

  public record ApplyRequest(boolean apply) implements StrictApiRequest {}

  @GetMapping("/status")
  public Text status() {
    return new Text(CheckpointHistoryReport.usage(service.historyUsage()));
  }

  @GetMapping("/cleanup")
  public Text preview() {
    return new Text(CheckpointHistoryReport.cleanup(service.cleanupHistory(false)));
  }

  @PostMapping("/cleanup")
  public Text apply(@RequestBody ApplyRequest request) {
    if (request == null || !request.apply())
      throw new IllegalArgumentException(
          "整理候補を /checkpoint cleanup で確認し、実行する場合は /checkpoint cleanup --apply を指定してください。");
    return new Text(CheckpointHistoryReport.cleanup(service.cleanupHistory(true)));
  }
}
