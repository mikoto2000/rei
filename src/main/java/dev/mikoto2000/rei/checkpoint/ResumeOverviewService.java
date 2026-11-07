package dev.mikoto2000.rei.checkpoint;

import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 再開概要のユースケースを実現する。
 *
 * <p>Read-only checkpoint projection. Deliberately has no Resume, reconciliation, Git or LLM
 * dependency.
 */
@org.springframework.stereotype.Service
public class ResumeOverviewService {
  /** preferredタイムゾーン。 */
  @org.springframework.beans.factory.annotation.Value("${rei.activity.zone:}")
  private String preferredZone = "";

  /** 保存と検索の窓口。 */
  private final BiFunction<String, Instant, PersistentCheckpointRepository.OverviewSelection>
      selectOverview;

  /** プロジェクト一覧。 */
  private final Supplier<List<ProjectContext>> registeredProjects;

  /** この機能の動作設定。 */
  private final BooleanSupplier overviewEnabled;

  private final IntSupplier failedMaxAgeDays;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、ResumeOverviewServiceを初期化する。 */
  public ResumeOverviewService(
      PersistentCheckpointRepository repository,
      ProjectService projects,
      ResumeOverviewProperties properties,
      Clock clock) {
    this.selectOverview = (project, cutoff) -> repository.overviewSelection(project, cutoff);
    this.registeredProjects = () -> projects.registeredProjects();
    this.overviewEnabled = () -> properties.isEnabled();
    this.failedMaxAgeDays = () -> properties.getFailedMaxAgeDays();
    this.clock = clock;
  }

  /** この機能を利用する設定が有効かどうかを返す。 */
  public boolean enabled() {
    return overviewEnabled.getAsBoolean();
  }

  /** 作業の中断と再開で用いるpreferredタイムゾーンを返す。 */
  public String preferredZone() {
    return preferredZone;
  }

  /** 作業の中断と再開で用いるタイムゾーンを返す。 */
  public ZoneId zone(ZoneId clientZone) {
    return preferredZone == null || preferredZone.isBlank() ? clientZone : ZoneId.of(preferredZone);
  }

  /** 作業の中断と再開で用いる概要を返す。 */
  public ResumeOverview overview(String projectId) {
    var project =
        registeredProjects.get().stream()
            .filter(p -> p.id().equals(projectId))
            .findFirst()
            .orElseThrow(
                () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    var selection =
        selectOverview.apply(
            project.id(), clock.instant().minus(Duration.ofDays(failedMaxAgeDays.getAsInt())));
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

  /** 作業の中断と再開で用いる件数を返す。 */
  public List<ResumeOverview.ProjectCount> counts() {
    var result = new ArrayList<ResumeOverview.ProjectCount>();
    for (var project : registeredProjects.get()) {
      var overview = overview(project.id());
      if (overview.count() > 0)
        result.add(
            new ResumeOverview.ProjectCount(
                project.id(), overview.projectName(), overview.count()));
    }
    return List.copyOf(result);
  }

  /** 作業の中断と再開で用いる起動時要約を返す。 */
  public String startupSummary() {
    return ResumeOverview.summary(counts());
  }
}
