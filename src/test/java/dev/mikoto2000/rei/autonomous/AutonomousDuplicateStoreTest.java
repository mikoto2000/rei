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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("integration")
class AutonomousDuplicateStoreTest {
  private static final String CID = "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a";
  @TempDir Path root;
  private final MutableClock clock = new MutableClock();
  private SqliteAutonomousDraftStore store;

  private SqliteAutonomousDraftStore reopened() {
    var d = AutonomousActionProperties.Bluesky.defaults();
    return new SqliteAutonomousDraftStore(
        new StorageDatabase(root),
        clock,
        new AutonomousActionProperties(
            true,
            new AutonomousActionProperties.Bluesky(
                true,
                d.cron(),
                d.zone(),
                100,
                100,
                Duration.ofMillis(1),
                d.draftExpiration(),
                10,
                Duration.ofMillis(1),
                d.systemPrompt())));
  }

  @BeforeEach
  void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    store = reopened();
  }

  private AutonomousDraftStore.Claim reserve(String did) {
    clock.advance();
    return store
        .reserve(UUID.randomUUID().toString(), AutonomousDraft.Trigger.MANUAL, did, "rei.example")
        .claim();
  }

  private AutonomousDraft draft(String text) {
    return draft(text, "did:plc:example");
  }

  private AutonomousDraft draft(String text, String did) {
    return store.complete(reserve(did), text, "公開する理由", List.of()).orElseThrow();
  }

  private void publication(AutonomousDraft draft, AutonomousDraft.State state) {
    var claim = store.beginPublication(draft.id(), 1, "operator");
    if (state != AutonomousDraft.State.PUBLISHING) {
      var outcome = AutonomousPublicationStore.Outcome.valueOf(state.name());
      store.finishPublication(
          claim,
          outcome,
          outcome == AutonomousPublicationStore.Outcome.PUBLISHED ? claim.record().uri() : null,
          outcome == AutonomousPublicationStore.Outcome.PUBLISHED ? CID : null,
          outcome == AutonomousPublicationStore.Outcome.PUBLISHED ? "" : "response_lost");
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = AutonomousDraft.State.class,
      names = {"PUBLISHED", "PUBLISHING", "UNKNOWN"})
  void generationDoesNotRecreateAnEquivalentPossiblyPublicPost(AutonomousDraft.State state) {
    publication(draft("ＡＩ\r\n公開資料"), state);
    var claim = reserve("did:plc:example");
    assertThat(store.complete(claim, "AI 公開資料", "", List.of())).isEmpty();
    var attempt = store.considerations(1).getFirst();
    assertThat(attempt.status()).isEqualTo(AutonomousHistory.Status.SKIPPED);
    assertThat(attempt.reason()).isEqualTo("duplicate_publication");
    assertThat(store.list(20)).hasSize(1);
  }

  @ParameterizedTest
  @EnumSource(
      value = AutonomousDraft.State.class,
      names = {"PUBLISHED", "PUBLISHING", "UNKNOWN"})
  void aStagedOrEditedDuplicateCannotStartAnotherPublication(AutonomousDraft.State state) {
    var first = draft("ＡＩ\r\n公開資料");
    var second = draft("別の本文");
    publication(first, state);
    var edited = store.edit(second.id(), 1, "AI 公開資料");
    assertThatThrownBy(() -> store.beginPublication(second.id(), 2, "operator"))
        .isInstanceOf(AutonomousConflictException.class);
    assertThat(store.get(second.id())).isEqualTo(edited);
    assertThat(store.get(second.id()).approval()).isNull();
    assertThat(store.publicationRecord(second.id())).isEmpty();
  }

  @Test
  void aGeneratedResultFinishingAfterAnotherPublicationIsSuppressedInsideTheTransaction() {
    var first = draft("先に公開される本文");
    var generation = reserve(first.target().did());
    publication(first, AutonomousDraft.State.PUBLISHED);
    assertThat(store.complete(generation, first.content().text(), "", List.of())).isEmpty();
    assertThat(store.considerations(1).getFirst().reason()).isEqualTo("duplicate_publication");
  }

  @Test
  void duplicateRegenerationPreservesTheReviewedBodyAndConsumesOnlyItsAcceptedAttempt() {
    var old = draft("公開済み本文");
    var pending = draft("確認待ちの別の本文");
    publication(old, AutonomousDraft.State.PUBLISHED);
    clock.advance();
    var claim =
        store
            .reserveRegeneration(
                "regenerate", pending.id(), 1, pending.target().did(), pending.target().handle())
            .claim();
    assertThat(store.completeRegeneration(claim, old.content().text(), "", List.of())).isEmpty();
    var current = store.get(pending.id());
    assertThat(current.content()).isEqualTo(pending.content());
    assertThat(current.regenerationAttempts()).isEqualTo(1);
    assertThat(current.approval()).isNull();
    assertThat(current.publication()).isNull();
    assertThat(store.considerations(1).getFirst().draftId()).isEqualTo(pending.id());
    assertThat(store.considerations(1).getFirst().reason()).isEqualTo("duplicate_publication");
  }

  @Test
  void anotherTargetAndDifferentPunctuationAreNotMistakenForTheSamePublication() {
    publication(draft("C++ の話題"), AutonomousDraft.State.PUBLISHED);
    var other = draft("C++ の話題", "did:plc:other");
    publication(other, AutonomousDraft.State.PUBLISHED);
    var different = draft("C の話題");
    publication(different, AutonomousDraft.State.PUBLISHED);
    assertThat(store.published(20)).hasSize(3);
  }

  @Test
  void rejectedExpiredAndProvablyUnsentDraftsDoNotCountAsPublicPosts() {
    var rejected = draft("再検討できる本文");
    store.reject(rejected.id(), 1, "PRIVATE_REJECTION");
    var expired = draft(rejected.content().text());
    clock.now = clock.now.plus(Duration.ofDays(4));
    assertThat(store.get(expired.id()).state()).isEqualTo(AutonomousDraft.State.EXPIRED);
    var failed = draft(rejected.content().text());
    publication(failed, AutonomousDraft.State.FAILED);
    assertThat(draft(rejected.content().text()).state())
        .isEqualTo(AutonomousDraft.State.PENDING_REVIEW);
  }

  @Test
  void anUnsentRetryIsBlockedIfAnotherDraftHasSincePublishedTheSameBody() {
    var first = draft("同じ本文");
    publication(first, AutonomousDraft.State.FAILED);
    var second = draft(first.content().text());
    publication(second, AutonomousDraft.State.PUBLISHED);
    assertThatThrownBy(() -> store.beginPublication(first.id(), 1, "operator"))
        .isInstanceOf(AutonomousConflictException.class);
    assertThat(store.get(first.id()).state()).isEqualTo(AutonomousDraft.State.FAILED);
  }

  @Test
  void independentStoresCanStartOnlyOneOfTwoEquivalentDrafts() throws Exception {
    var first = draft("ＡＩ の話題");
    var second = draft("AI の話題");
    var other = reopened();
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var one = workers.submit(() -> beginAfter(start, store, first));
      var two = workers.submit(() -> beginAfter(start, other, second));
      start.countDown();
      assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(store.list(20))
        .filteredOn(d -> d.state() == AutonomousDraft.State.PUBLISHING)
        .hasSize(1);
  }

  private boolean beginAfter(
      CountDownLatch start, SqliteAutonomousDraftStore instance, AutonomousDraft draft)
      throws InterruptedException {
    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    try {
      instance.beginPublication(draft.id(), 1, "operator");
      return true;
    } catch (AutonomousConflictException duplicate) {
      return false;
    }
  }

  @Test
  void restartKeepsUnknownDuplicatesBlockedWithoutChangingTheFrozenRecordBytes() {
    var original = draft("ＡＩ\r\n公開資料");
    var staged = draft("AI 公開資料");
    var claim = store.beginPublication(original.id(), 1, "operator");
    assertThat(claim.record().text()).isEqualTo(original.content().text());
    store = reopened();
    store.recoverInterruptedPublications();
    assertThat(store.get(original.id()).state()).isEqualTo(AutonomousDraft.State.UNKNOWN);
    assertThat(store.publicationRecord(original.id())).contains(claim.record());
    assertThatThrownBy(() -> store.beginPublication(staged.id(), 1, "operator"))
        .isInstanceOf(AutonomousConflictException.class);
    assertThat(
            store.complete(
                reserve(original.target().did()), staged.content().text(), "", List.of()))
        .isEmpty();
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
