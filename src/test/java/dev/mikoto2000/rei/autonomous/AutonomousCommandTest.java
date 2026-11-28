package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@Tag("integration")
class AutonomousCommandTest {
  @TempDir Path root;
  private AutonomousPublicSources sources;
  private final StringWriter output = new StringWriter();
  private final AutonomousGenerationService generation = mock(AutonomousGenerationService.class);

  @BeforeEach
  void start() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    sources = new AutonomousPublicSources(new StorageDatabase(root), Clock.systemUTC());
  }

  private int execute(String... arguments) {
    return new CommandLine(new AutonomousCommand(sources, () -> generation))
        .setOut(new PrintWriter(output, true))
        .setErr(new PrintWriter(output, true))
        .execute(arguments);
  }

  @Test
  void newSourceIsPrivateUntilExplicitlyPermittedAndAnEditRemovesPermission() {
    assertThat(execute("add-source", "--text", "公開するか検討中の話題")).isZero();
    var first = sources.list(20).getFirst();
    assertThat(first.publicUseAllowed()).isFalse();
    assertThat(sources.publicSnapshot()).isEmpty();
    assertThat(execute("permit-source", first.id(), "--revision", "1")).isZero();
    assertThat(sources.publicSnapshot()).hasSize(1);
    assertThat(execute("edit-source", first.id(), "--revision", "2", "--text", "編集した新しい話題"))
        .isZero();
    assertThat(sources.get(first.id()).revision()).isEqualTo(3);
    assertThat(sources.publicSnapshot()).isEmpty();
    verifyNoInteractions(generation);
  }

  @Test
  void explicitPublicRegistrationListingAndRevocationKeepPrivateActorOutOfSnapshot() {
    assertThat(execute("add-source", "--text", "公開用の話題", "--public-use")).isZero();
    var first = sources.list(20).getFirst();
    assertThat(sources.publicSnapshot()).hasSize(1);
    assertThat(execute("source", first.id())).isZero();
    assertThat(execute("sources")).isZero();
    assertThat(output.toString()).contains(first.id(), "公開用の話題");
    assertThat(execute("revoke-source", first.id(), "--revision", "1")).isZero();
    assertThat(sources.publicSnapshot()).isEmpty();
  }

  @Test
  void staleRevisionAndMissingExplicitFieldsFailWithoutChangingSources() {
    var source = sources.register("話題", true, "actor");
    assertThat(execute("revoke-source", source.id(), "--revision", "2")).isEqualTo(2);
    assertThat(execute("edit-source", source.id(), "--revision", "1")).isEqualTo(2);
    assertThat(execute("add-source")).isEqualTo(2);
    assertThat(sources.get(source.id())).isEqualTo(source);
  }

  @Test
  void manualGenerationPrintsReviewDraftIdentifierAndFailureHasNonzeroExit() {
    when(generation.manual())
        .thenReturn(
            new AutonomousGenerationService.Result(
                AutonomousGenerationService.Status.DRAFT_CREATED, "pending_review", "draft-one"));
    assertThat(execute("generate")).isZero();
    assertThat(output.toString()).contains("draft-one", "pending_review");
    when(generation.manual())
        .thenReturn(
            new AutonomousGenerationService.Result(
                AutonomousGenerationService.Status.FAILED, "generation_failed", null));
    assertThat(execute("generate")).isEqualTo(2);
  }

  @Test
  void disabledGenerationDoesNotRequireAModelAndHasNoPublishOperation() {
    var command =
        new CommandLine(new AutonomousCommand(sources, () -> null))
            .setOut(new PrintWriter(output, true))
            .setErr(new PrintWriter(output, true));
    assertThat(command.execute("generate")).isZero();
    assertThat(output.toString()).contains("disabled");
    assertThat(command.execute("publish")).isEqualTo(2);
    assertThat(command.execute("approve")).isEqualTo(2);
    verifyNoInteractions(generation);
  }
}
