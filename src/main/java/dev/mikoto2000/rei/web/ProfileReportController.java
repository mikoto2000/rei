package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.command.ProfileCommand;
import dev.mikoto2000.rei.event.ProfileEventLogStore;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/profile")
public final class ProfileReportController {
  private final ProfileCommand report;

  public record Text(String text) {}

  public ProfileReportController(ProfileEventLogStore store) {
    report = new ProfileCommand(store);
  }

  @GetMapping("/report")
  public Text summary() {
    return new Text(report.renderSummary());
  }

  @GetMapping("/path")
  public Text path() {
    return new Text(report.renderPath());
  }

  @GetMapping("/chart")
  public Text chart(
      @RequestParam(defaultValue = "60") long bucketSeconds,
      @RequestParam(defaultValue = "40") int width) {
    return new Text(report.renderChart(bucketSeconds, width));
  }

  @GetMapping("/mermaid")
  public Text mermaid(@RequestParam(defaultValue = "12") int limit) {
    return new Text(report.renderMermaid(limit));
  }
}
