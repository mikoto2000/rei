package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.completion.CompletionCandidate;
import dev.mikoto2000.rei.core.completion.CompletionContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会話完了の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SessionCompletionTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** {@code row} の条件を再現するための補助情報を返す。 */
  SessionMetadata row(String id, String project) {
    return new SessionMetadata(id, project, "A title", Instant.EPOCH, Instant.EPOCH);
  }

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  CompletionContext context() {
    return new CompletionContext(
        "", 0, List.of(""), 0, 0, "", List.of(), Set.of(), List.of(), root);
  }

  /** ターミナルでの対話で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void snapshotLoadsPersistedIdsAndDoesNotReadOnCompletion() throws Exception {
    var file = root.resolve("sessions.json");
    var repository = new FileSessionRepository(file);
    repository.accept(row("session-1", "p1"), () -> {});
    var restarted = new FileSessionRepository(file);
    Files.writeString(file, "unreadable JSON");
    var candidates = new SessionCompletionCandidates(restarted);
    assertThat(candidates.choices(context()))
        .extracting(CompletionCandidate::value)
        .containsExactly("session-1");
    assertThat(candidates.choices(context()).getFirst().description()).isEqualTo("A title");
  }

  /** ターミナルでの対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedAdmissionRollsBackSnapshot() {
    var repository = new FileSessionRepository(root.resolve("sessions.json"));
    assertThatThrownBy(
            () ->
                repository.accept(
                    row("rejected", "p1"),
                    () -> {
                      throw new IllegalStateException();
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.completionSnapshot()).isEmpty();
  }

  /** ターミナルでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void resumeOnlyOffersCurrentProjectSessionsWithoutRegisteringAProject() {
    var registry = new ProjectRegistry(root.resolve("projects.json"));
    var projects = new ProjectService(root, registry);
    var repository = new FileSessionRepository(root.resolve("sessions.json"));
    var candidates = new SessionCompletionCandidates.CurrentProject(repository, projects);
    assertThat(candidates.choices(context())).isEmpty();
    assertThat(root.resolve("projects.json")).doesNotExist();
    String project = registry.resolve(root).id();
    repository.accept(row("here", project), () -> {});
    repository.accept(row("elsewhere", "another-project"), () -> {});
    assertThat(candidates.choices(context()))
        .extracting(CompletionCandidate::value)
        .containsExactly("here");
  }

  /** ターミナルでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void projectSnapshotTracksRegistryWithoutReadingOnTab() throws Exception {
    Path file = root.resolve("projects.json");
    var registry = new ProjectRegistry(file);
    registry.resolve(root);
    var restarted = new ProjectRegistry(file);
    Files.writeString(file, "broken");
    assertThat(restarted.completionSnapshot())
        .extracting(ProjectContext::root)
        .containsExactly(root.toRealPath());
  }

  /** ターミナルでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void springFactoryInjectsSessionMetadataIntoRealCommandModel() {
    var repository = new FileSessionRepository(root.resolve("sessions.json"));
    var registry = new ProjectRegistry(root.resolve("projects.json"));
    var projects = new ProjectService(root, registry);
    repository.accept(row("visible-session", registry.resolve(root).id()), () -> {});
    try (var spring =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      spring.registerBean(SessionRepository.class, () -> repository);
      spring.registerBean(ProjectService.class, () -> projects);
      spring.register(
          SessionCompletionCandidates.class, SessionCompletionCandidates.CurrentProject.class);
      spring.refresh();
      var cmd =
          new picocli.CommandLine(
                  picocli.CommandLine.Model.CommandSpec.create(),
                  new picocli.spring.PicocliSpringFactory(spring))
              .addSubcommand("history", HistoryCommand.class)
              .addSubcommand("session", SessionCommand.class);
      for (String line : List.of("/history show ", "/session resume ", "/session switch ")) {
        var results = new ArrayList<org.jline.reader.Candidate>();
        new JLineCompletionAdapter(
                cmd,
                dev.mikoto2000.rei.core.command.ReiLineReaderFactory.completionEngine(),
                () -> root)
            .complete(
                null,
                dev.mikoto2000.rei.core.command.ReiLineReaderFactory.parser()
                    .parse(line, line.length()),
                results);
        assertThat(results)
            .extracting(org.jline.reader.Candidate::value)
            .containsExactly("visible-session");
      }
    }
  }
}
