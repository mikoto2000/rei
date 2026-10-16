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
 * Uses the same stateless Bearer authentication and registered-project scope as other project APIs.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public class ResumeOverviewController {
  private final ResumeOverviewService service;

  public ResumeOverviewController(ResumeOverviewService service) {
    this.service = service;
  }

  public record Startup(
      boolean enabled, String preferredZone, List<ResumeOverview.ProjectCount> projects) {}

  @GetMapping("/resume-overview")
  public Startup startup() {
    return new Startup(
        service.enabled(),
        service.preferredZone(),
        service.enabled() ? service.counts() : List.of());
  }

  @GetMapping("/projects/{projectId}/resume-overview")
  public ResumeOverview project(@PathVariable String projectId) {
    return service.overview(projectId);
  }
}
