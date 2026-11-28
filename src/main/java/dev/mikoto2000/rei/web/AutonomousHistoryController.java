package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.autonomous.AutonomousDraft;
import dev.mikoto2000.rei.autonomous.AutonomousHistory;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated, global operator history; independent of generation feature flags. */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/autonomous/history")
public final class AutonomousHistoryController {
  private final AutonomousHistory history;

  public AutonomousHistoryController(AutonomousHistory history) {
    this.history = history;
  }

  @GetMapping("/published")
  public List<AutonomousDraft> published(
      @RequestParam(name = "limit", defaultValue = "20") int limit) {
    return history.published(limit);
  }

  @GetMapping("/generation")
  public List<AutonomousHistory.Opportunity> generation(
      @RequestParam(name = "limit", defaultValue = "20") int limit) {
    return history.considerations(limit);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> invalid() {
    return ResponseEntity.badRequest()
        .body(Map.of("message", "Invalid autonomous history request"));
  }
}
