package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore;
import dev.mikoto2000.rei.storage.SqliteAutonomousPublicSources;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@Tag("integration")
class AutonomousDraftCommandTest {
  @TempDir Path root;
  private AutonomousDraftStore drafts;
  private AutonomousPublicSources sources;
  private AutonomousDraft initial;
  private AutonomousGenerationService generation;
  private final StringWriter output = new StringWriter();

  @BeforeEach
  void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var clock = Clock.fixed(Instant.parse("2026-11-28T03:00:00Z"), ZoneOffset.UTC);
    var d = AutonomousActionProperties.Bluesky.defaults();
    var settings =
        new AutonomousActionProperties(
            true,
            new AutonomousActionProperties.Bluesky(
                true,
                d.cron(),
                d.zone(),
                1,
                1,
                d.cooldown(),
                d.draftExpiration(),
                3,
                d.regenerationCooldown(),
                d.systemPrompt()));
    var database = new StorageDatabase(root);
    drafts = new SqliteAutonomousDraftStore(database, clock, settings);
    sources = new SqliteAutonomousPublicSources(database, clock);
    var topic = sources.register("公開用の話題", true, "fixture");
    var claim =
        drafts
            .reserve("initial", AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example")
            .claim();
    initial =
        drafts
            .complete(claim, "最初の本文", "投稿理由", List.of("topic:" + topic.id() + ":1"))
            .orElseThrow();
    generation =
        new AutonomousGenerationService(
            drafts,
            sources,
            () -> initial.target(),
            snapshot ->
                "{\"decision\":\"PROPOSE_POST\",\"reason\":\"話題を共有する\",\"draft\":{\"text\":\"再生成した本文の全文\",\"sourceIds\":[\""
                    + snapshot.getFirst().id()
                    + "\"]}}");
  }

  private int execute(String... arguments) {
    output.getBuffer().setLength(0);
    return new CommandLine(new AutonomousCommand(sources, drafts, () -> generation))
        .setOut(new PrintWriter(output, true))
        .setErr(new PrintWriter(output, true))
        .execute(arguments);
  }

  private com.fasterxml.jackson.databind.JsonNode json() throws Exception {
    return StorageDatabase.JSON.readTree(output.toString());
  }

  @Test
  void listingAndDetailExposeTheCompleteReviewInformation() throws Exception {
    assertThat(execute("drafts", "--limit", "20")).isZero();
    assertThat(json().get(0).path("id").asText()).isEqualTo(initial.id());
    assertThat(execute("draft", initial.id())).isZero();
    var detail = json();
    assertThat(detail.path("content").path("text").asText()).isEqualTo("最初の本文");
    assertThat(detail.path("content").path("reason").asText()).isEqualTo("投稿理由");
    assertThat(detail.path("content").path("sourceIds").get(0).asText())
        .isEqualTo(initial.content().sourceIds().getFirst());
    assertThat(detail.path("target").path("handle").asText()).isEqualTo("rei.example");
    assertThat(detail.path("state").asText()).isEqualTo("PENDING_REVIEW");
    assertThat(detail.path("trigger").asText()).isEqualTo("MANUAL");
    assertThat(detail.path("createdAt").asText()).isEqualTo(initial.createdAt().toString());
    assertThat(detail.path("expiresAt").asText()).isEqualTo(initial.expiresAt().toString());
  }

  @Test
  void editingPrintsNewTextAndRevisionAndRejectsAnOldScreen() throws Exception {
    assertThat(execute("edit", initial.id(), "--revision", "1", "--text", "更新した本文の全文")).isZero();
    assertThat(json().path("content").path("text").asText()).isEqualTo("更新した本文の全文");
    assertThat(json().path("content").path("revision").asInt()).isEqualTo(2);
    assertThat(json().path("approval").isNull()).isTrue();
    assertThat(json().path("publication").isNull()).isTrue();
    assertThat(execute("edit", initial.id(), "--revision", "1", "--text", "古い画面の変更")).isEqualTo(2);
    assertThat(drafts.get(initial.id()).content().text()).isEqualTo("更新した本文の全文");
    assertThat(execute("edit", initial.id(), "--text", "revisionなし")).isEqualTo(2);
    assertThat(execute("edit", initial.id(), "--revision", "2")).isEqualTo(2);
  }

  @Test
  void regenerationPrintsTheNewBodyAndSkipKeepsThatBodyVisible() throws Exception {
    assertThat(execute("regenerate", initial.id(), "--revision", "1")).isZero();
    assertThat(json().path("status").asText()).isEqualTo("DRAFT_REGENERATED");
    assertThat(json().path("draft").path("content").path("text").asText()).isEqualTo("再生成した本文の全文");
    assertThat(json().path("draft").path("content").path("revision").asInt()).isEqualTo(2);
    assertThat(execute("regenerate", initial.id(), "--revision", "2")).isZero();
    assertThat(json().path("status").asText()).isEqualTo("SKIPPED");
    assertThat(json().path("reason").asText()).isEqualTo("regeneration_cooldown");
    assertThat(json().path("draft").path("content").path("text").asText()).isEqualTo("再生成した本文の全文");
  }

  @Test
  void regenerationFailureHasNonzeroExitWithoutLeakingProviderText() throws Exception {
    generation =
        new AutonomousGenerationService(
            drafts,
            sources,
            () -> initial.target(),
            snapshot -> {
              throw new IllegalStateException("PRIVATE_PROVIDER_ERROR");
            });
    assertThat(execute("regenerate", initial.id(), "--revision", "1")).isEqualTo(2);
    assertThat(json().path("status").asText()).isEqualTo("FAILED");
    assertThat(json().path("draft").path("content").path("text").asText()).isEqualTo("最初の本文");
    assertThat(output.toString()).doesNotContain("PRIVATE_PROVIDER_ERROR");
  }

  @Test
  void rejectionReasonIsPrivateAndTerminalControlsAreEscaped() throws Exception {
    var reason = "PRIVATE_REASON\u001b[31m";
    assertThat(execute("reject", initial.id(), "--revision", "1", "--reason", reason)).isZero();
    assertThat(json().path("state").asText()).isEqualTo("REJECTED");
    assertThat(json().path("rejectionReason").asText()).isEqualTo(reason);
    assertThat(json().path("rejectionReasonPublic").asBoolean()).isFalse();
    assertThat(output.toString()).doesNotContain("\u001b");
  }

  @Test
  void rejectionReasonCanBeOmitted() throws Exception {
    assertThat(execute("reject", initial.id(), "--revision", "1")).isZero();
    assertThat(json().path("rejectionReason").asText()).isEmpty();
    assertThat(json().path("rejectionReasonPublic").asBoolean()).isFalse();
  }

  @Test
  void holdDoesNotChangeStateRevisionOrExpiration() throws Exception {
    assertThat(execute("hold", initial.id())).isZero();
    assertThat(drafts.get(initial.id())).isEqualTo(initial);
    assertThat(json().path("state").asText()).isEqualTo("PENDING_REVIEW");
  }

  @Test
  void disabledGenerationStillAllowsReviewAndEditing() throws Exception {
    generation = null;
    assertThat(execute("regenerate", initial.id(), "--revision", "1")).isZero();
    assertThat(json().path("reason").asText()).isEqualTo("disabled");
    assertThat(json().path("draft").path("id").asText()).isEqualTo(initial.id());
    assertThat(execute("draft", initial.id())).isZero();
    assertThat(execute("edit", initial.id(), "--revision", "1", "--text", "確認して編集")).isZero();
  }

  @Test
  void explicitPublicationRequiresTheReviewedRevisionAndUsesOnlyTheSavedDraft() throws Exception {
    var writes = new java.util.concurrent.atomic.AtomicInteger();
    var transport =
        new AutonomousPublicationTransport() {
          public void validate(String text) {}

          public Receipt send(
              AutonomousPostRecord record, java.util.function.BooleanSupplier gate) {
            writes.incrementAndGet();
            assertThat(record.text()).isEqualTo(initial.content().text());
            assertThat(record.did()).isEqualTo(initial.target().did());
            return new Receipt(
                AutonomousPublicationStore.Outcome.PUBLISHED,
                record.uri(),
                "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a",
                "");
          }
        };
    var service =
        new AutonomousPublicationService(
            (AutonomousPublicationStore) drafts, () -> true, transport);
    java.util.function.Function<String[], Integer> invoke =
        args -> {
          output.getBuffer().setLength(0);
          return new CommandLine(
                  new AutonomousCommand(sources, drafts, () -> generation, () -> service))
              .setOut(new PrintWriter(output, true))
              .setErr(new PrintWriter(output, true))
              .execute(args);
        };
    assertThat(invoke.apply(new String[] {"publish", initial.id()})).isEqualTo(2);
    assertThat(invoke.apply(new String[] {"publish", initial.id(), "--revision", "2"}))
        .isEqualTo(2);
    assertThat(
            invoke.apply(
                new String[] {"publish", initial.id(), "--revision", "1", "--text", "差し替え"}))
        .isEqualTo(2);
    assertThat(writes.get()).isZero();
    assertThat(invoke.apply(new String[] {"publish", initial.id(), "--revision", "1"})).isZero();
    assertThat(json().path("state").asText()).isEqualTo("PUBLISHED");
    assertThat(json().path("approval").path("approvedBy").asText()).isEqualTo("shell");
    assertThat(writes.get()).isEqualTo(1);
    assertThat(invoke.apply(new String[] {"publish", initial.id(), "--revision", "1"}))
        .isEqualTo(2);
    assertThat(writes.get()).isEqualTo(1);
  }

  @Test
  void explicitShellReconciliationOnlyReadsAnUnknownPublication() throws Exception {
    var writes = new java.util.concurrent.atomic.AtomicInteger();
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var transport =
        new AutonomousPublicationTransport() {
          public void validate(String text) {}

          public Receipt send(
              AutonomousPostRecord record, java.util.function.BooleanSupplier gate) {
            writes.incrementAndGet();
            return new Receipt(
                AutonomousPublicationStore.Outcome.UNKNOWN, null, null, "response_lost");
          }

          public FoundRecord lookup(AutonomousPostRecord record) {
            reads.incrementAndGet();
            return new FoundRecord(
                record.uri(),
                "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a",
                record.recordJson());
          }
        };
    var service =
        new AutonomousPublicationService(
            (AutonomousPublicationStore) drafts, () -> true, transport);
    for (var action : List.of("publish", "reconcile")) {
      output.getBuffer().setLength(0);
      assertThat(
              new CommandLine(
                      new AutonomousCommand(sources, drafts, () -> generation, () -> service))
                  .setOut(new PrintWriter(output, true))
                  .execute(action, initial.id(), "--revision", "1"))
          .isZero();
      assertThat(json().path("state").asText())
          .isEqualTo(action.equals("publish") ? "UNKNOWN" : "PUBLISHED");
    }
    assertThat(writes.get()).isEqualTo(1);
    assertThat(reads.get()).isEqualTo(1);
  }

  @Test
  void publicationAndUnrelatedPermissionOptionsAreRejected() {
    assertThat(execute("publish", initial.id())).isEqualTo(2);
    assertThat(execute("approve", initial.id())).isEqualTo(2);
    assertThat(execute("reject", initial.id(), "--revision", "1", "--public-use")).isEqualTo(2);
    assertThat(
            execute("edit", initial.id(), "--revision", "1", "--text", "変更", "--reason", "無関係な理由"))
        .isEqualTo(2);
    assertThat(drafts.get(initial.id())).isEqualTo(initial);
  }
}
