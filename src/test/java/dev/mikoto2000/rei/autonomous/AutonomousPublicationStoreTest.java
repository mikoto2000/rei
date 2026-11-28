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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
class AutonomousPublicationStoreTest {
  private static final String CID = "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a";
  @TempDir Path root;
  private final MutableClock clock = new MutableClock();
  private SqliteAutonomousDraftStore store;
  private AutonomousDraft initial;

  private AutonomousActionProperties settings(boolean enabled) {
    var d = AutonomousActionProperties.Bluesky.defaults();
    return new AutonomousActionProperties(
        enabled,
        new AutonomousActionProperties.Bluesky(
            enabled,
            d.cron(),
            d.zone(),
            1,
            1,
            d.cooldown(),
            d.draftExpiration(),
            d.maxRegenerations(),
            d.regenerationCooldown(),
            d.systemPrompt()));
  }

  private SqliteAutonomousDraftStore reopened(boolean enabled) {
    return new SqliteAutonomousDraftStore(new StorageDatabase(root), clock, settings(enabled));
  }

  @BeforeEach
  void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    store = reopened(true);
    var claim =
        store
            .reserve("initial", AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example")
            .claim();
    initial =
        store
            .complete(claim, "確認する本文\r\n続き 😀", "PRIVATE_REASON", List.of("topic:one:1"))
            .orElseThrow();
  }

  private AutonomousPublicationStore.PublishClaim begin() {
    return store.beginPublication(initial.id(), 1, "operator-one");
  }

  @Test
  void beginningPersistsTheSelectedRecordAndApprovalBeforeAnyExternalExecution() {
    assertThat(store.publicationRecord(initial.id())).isEmpty();
    var claim = begin();
    var current = reopened(true).get(initial.id());
    assertThat(current.state()).isEqualTo(AutonomousDraft.State.PUBLISHING);
    assertThat(current.content()).isEqualTo(initial.content());
    assertThat(current.target()).isEqualTo(initial.target());
    assertThat(current.approval().approvedBy()).isEqualTo("operator-one");
    assertThat(current.approval().approvedAt()).isEqualTo(clock.instant());
    assertThat(current.publication().recordJson()).isEqualTo(claim.record().recordJson());
    assertThat(current.publication().recordKey()).isEqualTo(claim.record().recordKey());
    assertThat(claim.record().text()).isEqualTo(initial.content().text());
    assertThat(claim.token()).isNotBlank();
    assertThat(store.publicationRecord(initial.id())).contains(claim.record());
    assertThatThrownBy(this::begin).isInstanceOf(AutonomousConflictException.class);
    assertThatThrownBy(() -> store.edit(initial.id(), 1, "変更した本文"))
        .isInstanceOf(AutonomousConflictException.class);
    assertThatThrownBy(() -> store.reject(initial.id(), 1, "見送り"))
        .isInstanceOf(AutonomousConflictException.class);
  }

  @Test
  void staleRevisionDoesNotClaimOrApproveTheEditedBody() {
    var edited = store.edit(initial.id(), 1, "編集した全文");
    assertThatThrownBy(this::begin).isInstanceOf(AutonomousConflictException.class);
    assertThat(store.publicationRecord(initial.id())).isEmpty();
    assertThat(store.get(initial.id()).approval()).isNull();
    var claim = store.beginPublication(initial.id(), 2, "operator");
    assertThat(claim.record().text()).isEqualTo(edited.content().text());
  }

  @Test
  void rejectedExpiredDisabledAndStoppingDraftsNeverBecomePublishing() {
    assertThatThrownBy(() -> reopened(false).beginPublication(initial.id(), 1, "operator"))
        .isInstanceOf(SecurityException.class);
    store.stop();
    assertThatThrownBy(this::begin).isInstanceOf(AutonomousConflictException.class);
    store = reopened(true);
    clock.advance(Duration.ofHours(73));
    assertThatThrownBy(this::begin).isInstanceOf(AutonomousConflictException.class);
    assertThat(store.get(initial.id()).state()).isEqualTo(AutonomousDraft.State.EXPIRED);
    assertThat(store.publicationRecord(initial.id())).isEmpty();
  }

  @Test
  void rejectedDraftsCannotBePublished() {
    store.reject(initial.id(), 1, "PRIVATE_FEEDBACK");
    assertThatThrownBy(this::begin).isInstanceOf(AutonomousConflictException.class);
    assertThat(store.publicationRecord(initial.id())).isEmpty();
  }

  @Test
  void malformedReviewInputsAreRejectedWithoutCreatingAPublication() {
    for (var actor : java.util.Arrays.asList(null, "", " ", "operator\nforged", "a".repeat(257))) {
      assertThatThrownBy(() -> store.beginPublication(initial.id(), 1, actor))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> store.beginPublication(initial.id(), 0, "operator"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.publicationRecord(initial.id())).isEmpty();
  }

  @Test
  void twoStoresAtomicallyClaimOnlyOnePublication() throws Exception {
    var other = reopened(true);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> race(store, ready, start));
      var second = executor.submit(() -> race(other, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(store.get(initial.id()).state()).isEqualTo(AutonomousDraft.State.PUBLISHING);
    }
  }

  private boolean race(
      SqliteAutonomousDraftStore candidate, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    start.await();
    try {
      candidate.beginPublication(initial.id(), 1, "operator");
      return true;
    } catch (AutonomousConflictException conflict) {
      return false;
    }
  }

  @Test
  void aRegenerationFinishingAfterPublicationCannotOverwriteTheSelectedBody() {
    var regeneration =
        store
            .reserveRegeneration(
                "regen", initial.id(), 1, initial.target().did(), initial.target().handle())
            .claim();
    var claim = begin();
    assertThat(
            store.completeRegeneration(
                regeneration, "後から生成された本文", "reason", List.of("topic:one:1")))
        .isEmpty();
    assertThat(store.get(initial.id()).content()).isEqualTo(initial.content());
    assertThat(store.publicationRecord(initial.id())).contains(claim.record());
  }

  @Test
  void successfulCompletionIsBoundToTheStoredRecordAndCannotBeRepeated() {
    var claim = begin();
    var result =
        store.finishPublication(
            claim, AutonomousPublicationStore.Outcome.PUBLISHED, claim.record().uri(), CID, "");
    assertThat(result.state()).isEqualTo(AutonomousDraft.State.PUBLISHED);
    assertThat(result.publication().uri()).isEqualTo(claim.record().uri());
    assertThat(result.publication().cid()).isEqualTo(CID);
    assertThat(result.content()).isEqualTo(initial.content());
    assertThatThrownBy(() ->
            store.finishPublication(
                claim, AutonomousPublicationStore.Outcome.PUBLISHED, claim.record().uri(), CID, ""))
        .isInstanceOf(AutonomousConflictException.class);
    assertThatThrownBy(this::begin).isInstanceOf(AutonomousConflictException.class);
  }

  @Test
  void uncertainCompletionRetainsTheRecordAndCannotBeRepublished() {
    var claim = begin();
    var unknown =
        store.finishPublication(
            claim, AutonomousPublicationStore.Outcome.UNKNOWN, null, null, "response_lost");
    assertThat(unknown.state()).isEqualTo(AutonomousDraft.State.UNKNOWN);
    assertThat(unknown.publication().failureReason()).isEqualTo("response_lost");
    assertThat(store.publicationRecord(initial.id())).contains(claim.record());
    assertThatThrownBy(this::begin).isInstanceOf(AutonomousConflictException.class);
  }

  @Test
  void explicitRetryOfAnUnsentFailureUsesTheSameRecordAndADifferentExecutionToken() {
    var first = begin();
    store.finishPublication(
        first, AutonomousPublicationStore.Outcome.FAILED, null, null, "authentication_failed");
    clock.advance(Duration.ofMinutes(1));
    var retry = store.beginPublication(initial.id(), 1, "operator-two");
    assertThat(retry.record()).isEqualTo(first.record());
    assertThat(retry.token()).isNotEqualTo(first.token());
    assertThat(store.get(initial.id()).approval().approvedBy()).isEqualTo("operator-two");
    assertThatThrownBy(() ->
            store.finishPublication(
                first, AutonomousPublicationStore.Outcome.PUBLISHED, first.record().uri(), CID, ""))
        .isInstanceOf(AutonomousConflictException.class);
    assertThat(
            store
                .finishPublication(
                    retry,
                    AutonomousPublicationStore.Outcome.PUBLISHED,
                    retry.record().uri(),
                    CID,
                    "")
                .state())
        .isEqualTo(AutonomousDraft.State.PUBLISHED);
  }

  @Test
  void restartRecoversPublishingAsUnknownWithoutReopeningThePublication() {
    var claim = begin();
    var approval = store.get(initial.id()).approval();
    var restart = reopened(true);
    restart.recoverInterruptedPublications();
    assertThat(restart.get(initial.id()).state()).isEqualTo(AutonomousDraft.State.UNKNOWN);
    assertThat(restart.get(initial.id()).approval()).isEqualTo(approval);
    assertThat(restart.publicationRecord(initial.id())).contains(claim.record());
    assertThatThrownBy(() -> restart.beginPublication(initial.id(), 1, "operator"))
        .isInstanceOf(AutonomousConflictException.class);
    assertThatThrownBy(() ->
            store.finishPublication(
                claim, AutonomousPublicationStore.Outcome.PUBLISHED, claim.record().uri(), CID, ""))
        .isInstanceOf(AutonomousConflictException.class);
    restart.recoverInterruptedPublications();
    assertThat(restart.get(initial.id()).state()).isEqualTo(AutonomousDraft.State.UNKNOWN);
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong_did", "wrong_key", "bad_cid", "missing_cid"})
  void anInvalidSuccessReceiptIsUncertainRatherThanPublished(String defect) {
    var claim = begin();
    var uri = claim.record().uri();
    var cid = CID;
    if (defect.equals("wrong_did")) uri = uri.replace("did:plc:example", "did:plc:other");
    if (defect.equals("wrong_key")) uri += "extra";
    if (defect.equals("bad_cid")) cid = "not-a-cid";
    if (defect.equals("missing_cid")) cid = null;
    var result =
        store.finishPublication(claim, AutonomousPublicationStore.Outcome.PUBLISHED, uri, cid, "");
    assertThat(result.state()).isEqualTo(AutonomousDraft.State.UNKNOWN);
    assertThat(result.publication().uri()).isNull();
    assertThat(result.publication().cid()).isNull();
    assertThat(result.publication().failureReason()).isEqualTo("invalid_record_receipt");
    assertThatThrownBy(this::begin).isInstanceOf(AutonomousConflictException.class);
  }

  @Test
  void aClaimCannotSubstituteADifferentRecordOrExecutionToken() {
    var claim = begin();
    var otherRecord = AutonomousPostRecord.fromDraft(initial, "3jzfcijpj2z2b", clock.instant());
    var substituted =
        new AutonomousPublicationStore.PublishClaim(claim.draftId(), claim.token(), otherRecord);
    var wrongToken =
        new AutonomousPublicationStore.PublishClaim(claim.draftId(), "other-token", claim.record());
    for (var invalid : List.of(substituted, wrongToken)) {
      assertThatThrownBy(() ->
              store.finishPublication(
                  invalid,
                  AutonomousPublicationStore.Outcome.PUBLISHED,
                  invalid.record().uri(),
                  CID,
                  ""))
          .isInstanceOf(AutonomousConflictException.class);
    }
    assertThat(store.get(initial.id()).state()).isEqualTo(AutonomousDraft.State.PUBLISHING);
    assertThat(store.publicationRecord(initial.id())).contains(claim.record());
  }

  @Test
  void retryStillRequiresAnUnexpiredReviewAndAnExistingFixedRecord() throws Exception {
    var claim = begin();
    store.finishPublication(
        claim, AutonomousPublicationStore.Outcome.FAILED, null, null, "authentication_failed");
    clock.advance(Duration.ofHours(73));
    assertThatThrownBy(this::begin).isInstanceOf(AutonomousConflictException.class);
    assertThat(store.publicationRecord(initial.id())).contains(claim.record());
  }

  @Test
  void losingTheFixedRecordNeverAllocatesANewKeyForAnExistingFailure() throws Exception {
    var claim = begin();
    store.finishPublication(
        claim, AutonomousPublicationStore.Outcome.FAILED, null, null, "authentication_failed");
    new StorageDatabase(root)
        .transaction(db -> {
          try (var sql = db.createStatement()) {
            sql.executeUpdate("DELETE FROM autonomous_publications");
          }
          return null;
        });
    assertThatThrownBy(this::begin).isInstanceOf(AutonomousConflictException.class);
    assertThat(store.get(initial.id()).publication().recordKey())
        .isEqualTo(claim.record().recordKey());
  }

  @Test
  void aStoredRecordMustStillMatchTheSelectedContentEvenWithARecomputedRecordHash() {
    var claim = begin();
    var changedJson = claim.record().recordJson().replace("確認する本文", "承認していない本文");
    var changedHash = hash(changedJson);
    new StorageDatabase(root)
        .transaction(db -> {
          try (var sql =
              db.prepareStatement(
                  "UPDATE autonomous_publications SET record_json=?,record_hash=?")) {
            sql.setString(1, changedJson);
            sql.setString(2, changedHash);
            sql.executeUpdate();
          }
          return null;
        });
    assertThatThrownBy(() -> store.publicationRecord(initial.id()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
            store.finishPublication(
                claim, AutonomousPublicationStore.Outcome.PUBLISHED, claim.record().uri(), CID, ""))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.get(initial.id()).state()).isEqualTo(AutonomousDraft.State.PUBLISHING);
  }

  @Test
  void publicationAndEditingRaceWithoutMixingTheSelectedRevision() throws Exception {
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var publication =
          executor.submit(() -> {
            start.await();
            return attempt(() -> begin());
          });
      var edit =
          executor.submit(() -> {
            start.await();
            return attempt(() -> store.edit(initial.id(), 1, "編集した本文"));
          });
      start.countDown();
      assertThat(List.of(publication.get(5, TimeUnit.SECONDS), edit.get(5, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      var current = store.get(initial.id());
      if (current.state() == AutonomousDraft.State.PUBLISHING) {
        assertThat(current.content()).isEqualTo(initial.content());
        assertThat(store.publicationRecord(initial.id()).orElseThrow().text())
            .isEqualTo(initial.content().text());
      } else {
        assertThat(current.state()).isEqualTo(AutonomousDraft.State.PENDING_REVIEW);
        assertThat(current.content().revision()).isEqualTo(2);
        assertThat(store.publicationRecord(initial.id())).isEmpty();
      }
    }
  }

  @Test
  void publicationAuditsHaveAnActorAndSafeCodesWithoutDuplicatingTheBodyOrPrivateReason() {
    var claim = begin();
    assertThatThrownBy(() ->
            store.finishPublication(
                claim,
                AutonomousPublicationStore.Outcome.UNKNOWN,
                null,
                null,
                "PRIVATE_PROVIDER_FRAGMENT"))
        .isInstanceOf(IllegalArgumentException.class);
    store.finishPublication(
        claim, AutonomousPublicationStore.Outcome.UNKNOWN, null, null, "response_lost");
    new StorageDatabase(root)
        .read(db -> {
          try (var sql = db.createStatement();
              var rows =
                  sql.executeQuery(
                      "SELECT actor,reason_code FROM autonomous_audit WHERE operation LIKE 'PUBLICATION_%'")) {
            int count = 0;
            while (rows.next()) {
              count++;
              assertThat(rows.getString(1)).isEqualTo("operator-one");
              assertThat(rows.getString(2)).doesNotContain("PRIVATE", initial.content().text());
            }
            assertThat(count).isEqualTo(2);
          }
          return null;
        });
  }

  @Test
  void theGenerationStorePortDoesNotExposePublicationOperations() {
    assertThat(AutonomousDraftStore.class.getMethods())
        .extracting(java.lang.reflect.Method::getName)
        .doesNotContain(
            "beginPublication",
            "finishPublication",
            "publicationRecord",
            "recoverInterruptedPublications");
  }

  private static boolean attempt(Runnable operation) {
    try {
      operation.run();
      return true;
    } catch (AutonomousConflictException conflict) {
      return false;
    }
  }

  private static String hash(String raw) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-11-28T06:00:00Z");

    void advance(Duration elapsed) {
      now = now.plus(elapsed);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("Asia/Tokyo");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
