package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class AutonomousGenerationServiceTest {
  @TempDir Path root;
  private AutonomousDraftStore drafts;
  private AutonomousPublicSources sources;
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-28T03:00:00Z"), ZoneOffset.UTC);
  private final AutonomousTargetResolver targets = mock(AutonomousTargetResolver.class);

  @BeforeEach
  void start() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var defaults = AutonomousActionProperties.Bluesky.defaults();
    var settings =
        new AutonomousActionProperties(
            true,
            new AutonomousActionProperties.Bluesky(
                true,
                defaults.cron(),
                defaults.zone(),
                1,
                1,
                defaults.cooldown(),
                defaults.draftExpiration(),
                defaults.maxRegenerations(),
                defaults.regenerationCooldown(),
                defaults.systemPrompt()));
    var database = new StorageDatabase(root);
    drafts = new dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore(database, clock, settings);
    sources = new dev.mikoto2000.rei.storage.SqliteAutonomousPublicSources(database, clock);
    when(targets.resolve())
        .thenReturn(new AutonomousDraft.Target("did:plc:example", "rei.example"));
  }

  private String proposal(List<AutonomousPublicSources.PublicSource> snapshot) {
    return "{\"decision\":\"PROPOSE_POST\",\"reason\":\"共有したい公開話題\",\"draft\":{\"text\":\"公開資料から考えた投稿案です。\",\"sourceIds\":[\""
        + snapshot.getFirst().id()
        + "\"]}}";
  }

  private AutonomousGenerationService service(AutonomousPostGenerator generator) {
    return new AutonomousGenerationService(drafts, sources, targets, generator);
  }

  @Test
  void manualOpportunityPersistsOnlyAnUnapprovedDraftAndUsesOnlyPermittedSources() {
    sources.register("PRIVATE_TOPIC", false, "private-actor");
    var permitted = sources.register("PUBLIC_TOPIC", true, "private-actor");
    var service =
        service(snapshot -> {
          assertThat(snapshot)
              .containsExactly(
                  new AutonomousPublicSources.PublicSource(
                      "topic:" + permitted.id() + ":1", "PUBLIC_TOPIC"));
          return proposal(snapshot);
        });
    var result = service.consider("manual-one", AutonomousDraft.Trigger.MANUAL);
    assertThat(result.status()).isEqualTo(AutonomousGenerationService.Status.DRAFT_CREATED);
    var saved = drafts.get(result.draftId());
    assertThat(saved.state()).isEqualTo(AutonomousDraft.State.PENDING_REVIEW);
    assertThat(saved.approval()).isNull();
    assertThat(saved.publication()).isNull();
    assertThat(saved.content().revision()).isOne();
  }

  @Test
  void missingPermittedSourcesSkipsWithoutCallingTheModel() {
    sources.register("PRIVATE_TOPIC", false, "actor");
    var generator = mock(AutonomousPostGenerator.class);
    var result = service(generator).consider("manual-empty", AutonomousDraft.Trigger.MANUAL);
    assertThat(result.status()).isEqualTo(AutonomousGenerationService.Status.SKIPPED);
    assertThat(result.reason()).isEqualTo("no_public_sources");
    verifyNoInteractions(generator);
    assertThat(drafts.list(20)).isEmpty();
  }

  @Test
  void skipIsNormalAndDoesNotRetryTheModel() {
    sources.register("PUBLIC", true, "actor");
    var count = new AtomicInteger();
    var result =
        service(
                snapshot -> {
                  count.incrementAndGet();
                  return "{\"decision\":\"SKIP\",\"reason\":\"今は共有する話題がありません\"}";
                })
            .consider("manual-skip", AutonomousDraft.Trigger.MANUAL);
    assertThat(result.status()).isEqualTo(AutonomousGenerationService.Status.SKIPPED);
    assertThat(count.get()).isOne();
    assertThat(drafts.list(20)).isEmpty();
  }

  @Test
  void invalidModelAuthorityIsFailedOnceWithoutPersistingADraft() {
    sources.register("PUBLIC", true, "actor");
    var count = new AtomicInteger();
    var result =
        service(
                snapshot -> {
                  count.incrementAndGet();
                  return "{\"decision\":\"PROPOSE_POST\",\"reason\":\"理由\",\"approval\":true}";
                })
            .consider("manual-invalid", AutonomousDraft.Trigger.MANUAL);
    assertThat(result.status()).isEqualTo(AutonomousGenerationService.Status.FAILED);
    assertThat(result.reason()).isEqualTo("generation_failed");
    assertThat(count.get()).isOne();
    assertThat(drafts.list(20)).isEmpty();
  }

  @Test
  void transportFailureDoesNotEscapeProviderTextOrAutomaticallyRetryOpportunity() {
    sources.register("PUBLIC", true, "actor");
    var count = new AtomicInteger();
    var service =
        service(snapshot -> {
          count.incrementAndGet();
          throw new IllegalStateException("PRIVATE_PROVIDER_ERROR");
        });
    assertThat(service.consider("manual-failure", AutonomousDraft.Trigger.MANUAL).reason())
        .isEqualTo("generation_failed");
    assertThat(service.consider("manual-failure", AutonomousDraft.Trigger.MANUAL).status())
        .isEqualTo(AutonomousGenerationService.Status.SKIPPED);
    assertThat(count.get()).isOne();
    assertThat(drafts.list(20)).isEmpty();
  }

  @Test
  void shutdownDuringGenerationDiscardsTheResultAndPreventsFutureCalls() {
    sources.register("PUBLIC", true, "actor");
    var service =
        service(snapshot -> {
          drafts.stop();
          return proposal(snapshot);
        });
    assertThat(service.consider("manual-stop", AutonomousDraft.Trigger.MANUAL).status())
        .isEqualTo(AutonomousGenerationService.Status.SKIPPED);
    assertThat(drafts.list(20)).isEmpty();
    var generator = mock(AutonomousPostGenerator.class);
    assertThat(
            service(generator).consider("manual-stopped", AutonomousDraft.Trigger.MANUAL).status())
        .isEqualTo(AutonomousGenerationService.Status.SKIPPED);
    verifyNoInteractions(generator);
  }

  @Test
  void duplicateOpportunityAndPendingDraftPreventExtraModelCalls() {
    sources.register("PUBLIC", true, "actor");
    var count = new AtomicInteger();
    var service =
        service(snapshot -> {
          count.incrementAndGet();
          return proposal(snapshot);
        });
    service.consider("manual-one", AutonomousDraft.Trigger.MANUAL);
    assertThat(service.consider("manual-one", AutonomousDraft.Trigger.MANUAL).reason())
        .isEqualTo("duplicate_opportunity");
    assertThat(service.consider("manual-two", AutonomousDraft.Trigger.MANUAL).status())
        .isEqualTo(AutonomousGenerationService.Status.SKIPPED);
    assertThat(count.get()).isOne();
  }

  @Test
  void concurrentManualCallsAdmitOnlyOneGeneration() throws Exception {
    sources.register("PUBLIC", true, "actor");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var count = new AtomicInteger();
    var service =
        service(snapshot -> {
          count.incrementAndGet();
          entered.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS))
              throw new AssertionError("test release timed out");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cancelled");
          }
          return proposal(snapshot);
        });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(() -> service.consider("manual-first", AutonomousDraft.Trigger.MANUAL));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(service.consider("manual-second", AutonomousDraft.Trigger.MANUAL).status())
            .isEqualTo(AutonomousGenerationService.Status.SKIPPED);
      } finally {
        release.countDown();
      }
      assertThat(first.get(10, TimeUnit.SECONDS).status())
          .isEqualTo(AutonomousGenerationService.Status.DRAFT_CREATED);
    }
    assertThat(count.get()).isOne();
  }
}
