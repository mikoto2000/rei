package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class AutonomousHistoryStoreTest {
  private static final String CID = "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a";
  @TempDir Path root;
  private final MutableClock clock = new MutableClock();
  private SqliteAutonomousDraftStore store;

  private AutonomousActionProperties settings(boolean enabled) {
    var d = AutonomousActionProperties.Bluesky.defaults();
    return new AutonomousActionProperties(
        enabled,
        new AutonomousActionProperties.Bluesky(
            enabled,
            d.cron(),
            d.zone(),
            100,
            100,
            Duration.ofMillis(1),
            d.draftExpiration(),
            10,
            Duration.ofMillis(1),
            d.systemPrompt()));
  }

  @BeforeEach
  void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    store = reopened(true);
  }

  private SqliteAutonomousDraftStore reopened(boolean enabled) {
    return new SqliteAutonomousDraftStore(new StorageDatabase(root), clock, settings(enabled));
  }

  private AutonomousDraftStore.Claim claim() {
    clock.advance();
    return store
        .reserve(
            UUID.randomUUID().toString(),
            AutonomousDraft.Trigger.MANUAL,
            "did:plc:example",
            "rei.example")
        .claim();
  }

  private AutonomousDraft draft(String text) {
    return store
        .complete(claim(), text, "PRIVATE_DRAFT_REASON", List.of("topic:one:1"))
        .orElseThrow();
  }

  private AutonomousDraft publish(AutonomousDraft draft) {
    var claim = store.beginPublication(draft.id(), 1, "PRIVATE_OPERATOR");
    clock.advance();
    return store.finishPublication(
        claim, AutonomousPublicationStore.Outcome.PUBLISHED, claim.record().uri(), CID, "");
  }

  @Test
  void publishedHistoryContainsOnlyConfirmedLocalPublicationsAndWorksWhenDisabled() {
    var first = publish(draft("最初の公開本文"));
    draft("未公開の本文");
    var rejected = draft("見送った本文");
    store.reject(rejected.id(), 1, "PRIVATE_REJECTION");
    var unknown = draft("結果不明の本文");
    var uncertain = store.beginPublication(unknown.id(), 1, "operator");
    store.finishPublication(
        uncertain, AutonomousPublicationStore.Outcome.UNKNOWN, null, null, "response_lost");
    var second = publish(draft("次の公開本文"));
    AutonomousHistory history = reopened(false);
    assertThat(history.published(20)).containsExactly(second, first);
    assertThat(history.published(1)).containsExactly(second);
    assertThat(history.published(200))
        .allSatisfy(item -> {
          assertThat(item.state()).isEqualTo(AutonomousDraft.State.PUBLISHED);
          assertThat(item.publication().uri())
              .startsWith("at://did:plc:example/app.bsky.feed.post/");
          assertThat(item.publication().cid()).isEqualTo(CID);
        });
  }

  @Test
  void generationHistoryIncludesSkipsFailuresInterruptedWorkAndGeneratedDrafts() throws Exception {
    var generated = draft("確認待ちの本文");
    var skip = claim();
    store.skip(skip, "no_suitable_topic");
    var failed = claim();
    store.fail(failed);
    var interrupted = claim();
    store.recoverInterruptedConsiderations();
    clock.advance();
    var disabled = reopened(false);
    disabled.reserve(
        "disabled", AutonomousDraft.Trigger.SCHEDULED, "did:plc:example", "rei.example");
    var history = disabled.considerations(20);
    assertThat(history)
        .extracting(AutonomousHistory.Opportunity::status)
        .containsExactly(
            AutonomousHistory.Status.POLICY_SKIPPED,
            AutonomousHistory.Status.ABORTED,
            AutonomousHistory.Status.FAILED,
            AutonomousHistory.Status.SKIPPED,
            AutonomousHistory.Status.GENERATED);
    assertThat(history.getLast().draftId()).isEqualTo(generated.id());
    assertThat(history.getFirst().trigger()).isEqualTo(AutonomousDraft.Trigger.SCHEDULED);
    assertThat(history.getFirst().reason()).isEqualTo("disabled");
    String serialized = StorageDatabase.JSON.writeValueAsString(history);
    assertThat(serialized)
        .doesNotContain(
            skip.token(),
            failed.token(),
            interrupted.token(),
            "PRIVATE_DRAFT_REASON",
            "PRIVATE_OPERATOR",
            "targetDid",
            "targetHandle",
            "token");
  }

  @Test
  void regenerationHistoryKeepsItsDraftLinkAndDoesNotChangeContent() {
    var original = draft("元の本文");
    clock.advance();
    var regen =
        store.reserveRegeneration(
            "regen", original.id(), 1, original.target().did(), original.target().handle());
    store.skip(regen.claim(), "keep_existing");
    var history = store.considerations(1).getFirst();
    assertThat(history.kind()).isEqualTo(AutonomousHistory.Kind.REGENERATE);
    assertThat(history.status()).isEqualTo(AutonomousHistory.Status.SKIPPED);
    assertThat(history.draftId()).isEqualTo(original.id());
    assertThat(store.get(original.id()).content()).isEqualTo(original.content());
  }

  @Test
  void historyReadsDoNotRecoverOrExpireAnActiveConsiderationOrDraft() {
    var draft = draft("期限を進める前の本文");
    var running = claim();
    clock.now = clock.now.plus(Duration.ofDays(5));
    store.stop();
    assertThat(store.published(20)).isEmpty();
    assertThat(store.considerations(20))
        .anySatisfy(item -> {
          assertThat(item.id()).isEqualTo(running.opportunityId());
          assertThat(item.status()).isEqualTo(AutonomousHistory.Status.RUNNING);
        });
    new StorageDatabase(root)
        .read(db -> {
          try (var q = db.prepareStatement("SELECT state FROM autonomous_drafts WHERE id=?")) {
            q.setString(1, draft.id());
            try (var rows = q.executeQuery()) {
              assertThat(rows.next()).isTrue();
              assertThat(rows.getString(1)).isEqualTo("PENDING_REVIEW");
            }
          }
          return null;
        });
  }

  @Test
  void invalidLimitsAreRejectedRatherThanReadingUnboundedHistory() {
    for (int limit : new int[] {-1, 0, 201, Integer.MAX_VALUE}) {
      assertThatThrownBy(() -> store.published(limit)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> store.considerations(limit))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(store.published(1)).isEmpty();
    assertThat(store.considerations(200)).isEmpty();
  }

  @Test
  void historyPortAndOpportunityProjectionExposeNoExecutionOrClaimCapability() {
    assertThat(
            Arrays.stream(AutonomousHistory.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
        .containsExactlyInAnyOrder("published", "considerations");
    assertThat(
            Arrays.stream(AutonomousHistory.Opportunity.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
        .containsExactly(
            "id", "trigger", "kind", "status", "createdAt", "updatedAt", "reason", "draftId");
    assertThat(store.considerations(20)).isEmpty();
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-11-28T03:00:00Z");

    void advance() {
      now = now.plusSeconds(1);
    }

    public Instant instant() {
      return now;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
