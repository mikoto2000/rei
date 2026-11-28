package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore;
import dev.mikoto2000.rei.storage.SqliteAutonomousPublicSources;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
class AutonomousRegenerationServiceTest {
  @TempDir Path root;
  private AutonomousDraftStore drafts;
  private AutonomousPublicSources sources;
  private AutonomousDraft initial;
  private AutonomousPublicSources.Source publicTopic;
  private final AutonomousTargetResolver targets = mock(AutonomousTargetResolver.class);

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
    var claim =
        drafts
            .reserve("initial", AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example")
            .claim();
    initial =
        drafts
            .complete(claim, "PRIVATE_BODY", "PRIVATE_REASON", List.of("old-source"))
            .orElseThrow();
    sources.register("PRIVATE_TOPIC", false, "PRIVATE_ACTOR");
    publicTopic = sources.register("PUBLIC_TOPIC", true, "PRIVATE_ACTOR");
    when(targets.resolve()).thenReturn(initial.target());
  }

  private AutonomousGenerationService service(AutonomousPostGenerator generator) {
    return new AutonomousGenerationService(drafts, sources, targets, generator);
  }

  private String proposal(List<AutonomousPublicSources.PublicSource> snapshot) {
    return "{\"decision\":\"PROPOSE_POST\",\"reason\":\"公開資料の話題\",\"draft\":{\"text\":\"再生成した新しい本文\",\"sourceIds\":[\""
        + snapshot.getFirst().id()
        + "\"]}}";
  }

  @Test
  void regenerationUsesOnlyPublicTopicsAndReturnsTheCompleteNewReviewSnapshot() {
    var result =
        service(
                snapshot -> {
                  assertThat(drafts.get(initial.id()).regenerationAttempts()).isEqualTo(1);
                  assertThat(snapshot)
                      .containsExactly(
                          new AutonomousPublicSources.PublicSource(
                              "topic:" + publicTopic.id() + ":1", "PUBLIC_TOPIC"));
                  return proposal(snapshot);
                })
            .regenerate(initial.id(), 1);
    assertThat(result.status()).isEqualTo(AutonomousGenerationService.Status.DRAFT_REGENERATED);
    assertThat(result.draftId()).isEqualTo(initial.id());
    assertThat(result.draft()).isEqualTo(drafts.get(initial.id()));
    assertThat(result.draft().content().text()).isEqualTo("再生成した新しい本文");
    assertThat(result.draft().content().revision()).isEqualTo(2);
    assertThat(result.draft().state()).isEqualTo(AutonomousDraft.State.PENDING_REVIEW);
    assertThat(result.draft().approval()).isNull();
    assertThat(result.draft().publication()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"skip", "provider", "invalid"})
  void unsuccessfulGenerationKeepsTheReviewedBodyAndDoesNotRetry(String mode) {
    var calls = new AtomicInteger();
    var service =
        service(snapshot -> {
          calls.incrementAndGet();
          return switch (mode) {
            case "skip" -> "{\"decision\":\"SKIP\",\"reason\":\"今は共有しない\"}";
            case "provider" -> throw new IllegalStateException("PRIVATE_PROVIDER_ERROR");
            case "invalid" -> "{\"decision\":\"PROPOSE_POST\",\"reason\":\"理由\",\"approval\":true}";
            default -> throw new AssertionError(mode);
          };
        });
    var result = service.regenerate(initial.id(), 1);
    assertThat(result.status())
        .isEqualTo(
            mode.equals("skip")
                ? AutonomousGenerationService.Status.SKIPPED
                : AutonomousGenerationService.Status.FAILED);
    assertThat(result.draft().content()).isEqualTo(initial.content());
    assertThat(result.draft().regenerationAttempts()).isEqualTo(1);
    assertThat(result.toString()).doesNotContain("PRIVATE_PROVIDER_ERROR");
    assertThat(service.regenerate(initial.id(), 1).reason()).isEqualTo("regeneration_cooldown");
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"edit", "reject", "stop"})
  void concurrentHumanChangesOrShutdownDiscardTheResultAndReturnCurrentState(String change) {
    var result =
        service(
                snapshot -> {
                  switch (change) {
                    case "edit" -> drafts.edit(initial.id(), 1, "人間が編集した本文");
                    case "reject" -> drafts.reject(initial.id(), 1, "PRIVATE_REJECTION");
                    case "stop" -> drafts.stop();
                    default -> throw new AssertionError(change);
                  }
                  return proposal(snapshot);
                })
            .regenerate(initial.id(), 1);
    assertThat(result.status()).isEqualTo(AutonomousGenerationService.Status.SKIPPED);
    assertThat(result.draft()).isEqualTo(drafts.get(initial.id()));
    assertThat(result.draft().content().text())
        .isEqualTo(change.equals("edit") ? "人間が編集した本文" : initial.content().text());
    assertThat(result.draft().regenerationAttempts()).isEqualTo(1);
  }

  @Test
  void staleRevisionIsRejectedBeforeAnyModelCall() {
    drafts.edit(initial.id(), 1, "編集済み");
    var generator = mock(AutonomousPostGenerator.class);
    assertThatThrownBy(() -> service(generator).regenerate(initial.id(), 1))
        .isInstanceOf(AutonomousConflictException.class);
    verifyNoInteractions(generator);
    assertThat(drafts.get(initial.id()).regenerationAttempts()).isZero();
  }

  @Test
  void absenceOfPublicTopicsSkipsWithoutCallingTheModel() {
    sources.permission(publicTopic.id(), 1, false, "operator");
    var generator = mock(AutonomousPostGenerator.class);
    var result = service(generator).regenerate(initial.id(), 1);
    assertThat(result.status()).isEqualTo(AutonomousGenerationService.Status.SKIPPED);
    assertThat(result.reason()).isEqualTo("no_public_sources");
    assertThat(result.draft().content()).isEqualTo(initial.content());
    assertThat(result.draft().regenerationAttempts()).isEqualTo(1);
    verifyNoInteractions(generator);
  }

  @Test
  void accountChangeDoesNotCallTheModelOrRetargetTheDraft() {
    when(targets.resolve())
        .thenReturn(new AutonomousDraft.Target("did:plc:other", "other.example"));
    var generator = mock(AutonomousPostGenerator.class);
    var result = service(generator).regenerate(initial.id(), 1);
    assertThat(result.reason()).isEqualTo("target_changed");
    assertThat(result.draft().target()).isEqualTo(initial.target());
    assertThat(result.draft().regenerationAttempts()).isZero();
    verifyNoInteractions(generator);
  }

  @Test
  void publicTargetResolutionFailureIsSanitizedAndDoesNotSpendAnLlmAttempt() {
    when(targets.resolve()).thenThrow(new IllegalStateException("PRIVATE_TARGET_ERROR"));
    var generator = mock(AutonomousPostGenerator.class);
    var result = service(generator).regenerate(initial.id(), 1);
    assertThat(result.status()).isEqualTo(AutonomousGenerationService.Status.FAILED);
    assertThat(result.reason()).isEqualTo("target_unavailable");
    assertThat(result.draft().regenerationAttempts()).isZero();
    assertThat(result.toString()).doesNotContain("PRIVATE_TARGET_ERROR");
    verifyNoInteractions(generator);
  }

  @Test
  void stoppedServiceDoesNotResolveTargetsOrCallTheModel() {
    drafts.stop();
    var generator = mock(AutonomousPostGenerator.class);
    assertThat(service(generator).regenerate(initial.id(), 1).reason()).isEqualTo("stopping");
    verifyNoInteractions(targets, generator);
  }
}
