package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.completion.*;
import dev.mikoto2000.rei.core.project.*;

class SessionCompletionTest {
  @TempDir Path root;
  SessionMetadata row(String id, String project) {
    return new SessionMetadata(id, project, "A title", Instant.EPOCH, Instant.EPOCH);
  }
  CompletionContext context() {
    return new CompletionContext("", 0, List.of(""), 0, 0, "", List.of(), Set.of(), List.of(), root);
  }
  @Test void snapshotLoadsPersistedIdsAndDoesNotReadOnCompletion() throws Exception {
    var file = root.resolve("sessions.json");
    var repository = new FileSessionRepository(file);
    repository.accept(row("session-1", "p1"), () -> {});
    var restarted = new FileSessionRepository(file);
    Files.writeString(file, "unreadable JSON");
    var candidates = new SessionCompletionCandidates(restarted);
    assertThat(candidates.choices(context())).extracting(CompletionCandidate::value).containsExactly("session-1");
    assertThat(candidates.choices(context()).getFirst().description()).isEqualTo("A title");
  }
  @Test void failedAdmissionRollsBackSnapshot() {
    var repository = new FileSessionRepository(root.resolve("sessions.json"));
    assertThatThrownBy(() -> repository.accept(row("rejected", "p1"), () -> { throw new IllegalStateException(); }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.completionSnapshot()).isEmpty();
  }
  @Test void resumeOnlyOffersCurrentProjectSessionsWithoutRegisteringAProject() {
    var registry = new ProjectRegistry(root.resolve("projects.json"));
    var projects = new ProjectService(root, registry);
    var repository = new FileSessionRepository(root.resolve("sessions.json"));
    var candidates = new SessionCompletionCandidates.CurrentProject(repository, projects);
    assertThat(candidates.choices(context())).isEmpty();
    assertThat(root.resolve("projects.json")).doesNotExist();
    String project = registry.resolve(root).id();
    repository.accept(row("here", project), () -> {});
    repository.accept(row("elsewhere", "another-project"), () -> {});
    assertThat(candidates.choices(context())).extracting(CompletionCandidate::value).containsExactly("here");
  }
  @Test void projectSnapshotTracksRegistryWithoutReadingOnTab() throws Exception {
    Path file = root.resolve("projects.json");
    var registry = new ProjectRegistry(file);
    registry.resolve(root);
    var restarted = new ProjectRegistry(file);
    Files.writeString(file, "broken");
    assertThat(restarted.completionSnapshot()).extracting(ProjectContext::root).containsExactly(root.toRealPath());
  }
  @Test void springFactoryInjectsSessionMetadataIntoRealCommandModel() {
    var repository = new FileSessionRepository(root.resolve("sessions.json"));
    var registry = new ProjectRegistry(root.resolve("projects.json"));
    var projects = new ProjectService(root, registry);
    repository.accept(row("visible-session", registry.resolve(root).id()), () -> {});
    try (var spring = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      spring.registerBean(SessionRepository.class, () -> repository);
      spring.registerBean(ProjectService.class, () -> projects);
      spring.register(SessionCompletionCandidates.class, SessionCompletionCandidates.CurrentProject.class);
      spring.refresh();
      var cmd = new picocli.CommandLine(picocli.CommandLine.Model.CommandSpec.create(), new picocli.spring.PicocliSpringFactory(spring))
          .addSubcommand("history", HistoryCommand.class).addSubcommand("session", SessionCommand.class);
      for (String line : List.of("/history show ", "/session resume ", "/session switch ")) {
        var results = new ArrayList<org.jline.reader.Candidate>();
        new JLineCompletionAdapter(cmd, dev.mikoto2000.rei.core.command.ReiLineReaderFactory.completionEngine(), () -> root)
            .complete(null, dev.mikoto2000.rei.core.command.ReiLineReaderFactory.parser().parse(line, line.length()), results);
        assertThat(results).extracting(org.jline.reader.Candidate::value).containsExactly("visible-session");
      }
    }
  }
}
