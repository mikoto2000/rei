package dev.mikoto2000.rei.checkpoint;

import dev.mikoto2000.rei.core.project.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * Read-only checkpoint projection. Deliberately has no Resume, reconciliation, Git or LLM
 * dependency.
 */
@org.springframework.stereotype.Service
public class ResumeOverviewService {
  @org.springframework.beans.factory.annotation.Value("${rei.activity.zone:}")
  private String preferredZone = "";

  private final PersistentCheckpointRepository repository;
  private final ProjectService projects;
  private final ResumeOverviewProperties properties;
  private final Clock clock;

  public ResumeOverviewService(
      PersistentCheckpointRepository repository,
      ProjectService projects,
      ResumeOverviewProperties properties,
      Clock clock) {
    this.repository = repository;
    this.projects = projects;
    this.properties = properties;
    this.clock = clock;
  }

  public boolean enabled() {
    return properties.isEnabled();
  }

  public String preferredZone() {
    return preferredZone;
  }

  public ZoneId zone(ZoneId clientZone) {
    return preferredZone == null || preferredZone.isBlank() ? clientZone : ZoneId.of(preferredZone);
  }

  public ResumeOverview overview(String projectId) {
    var project =
        projects.registeredProjects().stream()
            .filter(p -> p.id().equals(projectId))
            .findFirst()
            .orElseThrow(
                () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    var selection =
        repository.overviewSelection(
            project.id(), clock.instant().minus(Duration.ofDays(properties.getFailedMaxAgeDays())));
    var latest = selection.latest();
    String key = project.id() + "|" + selection.count() + "|" + Objects.toString(latest, "");
    try {
      String fingerprint =
          HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(key.getBytes(StandardCharsets.UTF_8)));
      return new ResumeOverview(
          project.id(),
          ResumeOverview.safe(project.name(), 80),
          selection.count(),
          latest,
          fingerprint);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public List<ResumeOverview.ProjectCount> counts() {
    var result = new ArrayList<ResumeOverview.ProjectCount>();
    for (var project : projects.registeredProjects()) {
      var overview = overview(project.id());
      if (overview.count() > 0)
        result.add(
            new ResumeOverview.ProjectCount(
                project.id(), overview.projectName(), overview.count()));
    }
    return List.copyOf(result);
  }

  public String startupSummary() {
    return ResumeOverview.summary(counts());
  }
}
