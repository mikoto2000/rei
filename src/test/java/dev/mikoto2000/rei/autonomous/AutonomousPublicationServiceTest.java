package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
class AutonomousPublicationServiceTest {
  private static final String CID = "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a";
  @TempDir Path root;
  private final AtomicBoolean allowed = new AtomicBoolean(true);
  private SqliteAutonomousDraftStore store;
  private AutonomousDraft initial;
  private FakeTransport transport;
  private AutonomousPublicationService service;

  @BeforeEach
  void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
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
                d.maxRegenerations(),
                d.regenerationCooldown(),
                d.systemPrompt()));
    store =
        new SqliteAutonomousDraftStore(
            new StorageDatabase(root),
            Clock.fixed(Instant.parse("2026-11-28T06:00:00Z"), ZoneOffset.UTC),
            settings);
    var claim =
        store
            .reserve("initial", AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example")
            .claim();
    initial =
        store
            .complete(claim, "確認した全文\r\n続き", "PRIVATE_REASON", List.of("topic:one:1"))
            .orElseThrow();
    transport = new FakeTransport();
    service = new AutonomousPublicationService(store, allowed::get, transport);
  }

  private AutonomousDraft publish() {
    return service.publish(initial.id(), 1, "api-client");
  }

  private AutonomousDraft uncertain() {
    transport.behavior =
        (record, gate) ->
            new AutonomousPublicationTransport.Receipt(
                AutonomousPublicationStore.Outcome.UNKNOWN, null, null, "response_lost");
    return publish();
  }

  @Test
  void readOnlyReconciliationConfirmsAllFrozenValuesWithoutAnotherSend() {
    var unknown = uncertain();
    var record = store.publicationRecord(initial.id()).orElseThrow();
    transport.found =
        new AutonomousPublicationTransport.FoundRecord(record.uri(), CID, record.recordJson());
    var result = service.reconcile(initial.id(), 1, "api-client");
    assertThat(result.state()).isEqualTo(AutonomousDraft.State.PUBLISHED);
    assertThat(result.approval()).isEqualTo(unknown.approval());
    assertThat(result.publication().cid()).isEqualTo(CID);
    assertThat(transport.lookups).isEqualTo(1);
    assertThat(transport.records).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "absent",
        "missing_cid",
        "invalid_cid",
        "wrong_uri",
        "changed_text",
        "extra_field",
        "invalid_json",
        "lookup_failure"
      })
  void missingDifferentOrUnavailableRecordsStayUnknownAndAreNeverResent(String defect) {
    var unknown = uncertain();
    var record = store.publicationRecord(initial.id()).orElseThrow();
    transport.found =
        switch (defect) {
          case "absent" -> null;
          case "missing_cid" ->
              new AutonomousPublicationTransport.FoundRecord(
                  record.uri(), null, record.recordJson());
          case "invalid_cid" ->
              new AutonomousPublicationTransport.FoundRecord(
                  record.uri(), "invalid", record.recordJson());
          case "wrong_uri" ->
              new AutonomousPublicationTransport.FoundRecord(
                  record.uri() + "other", CID, record.recordJson());
          case "changed_text" ->
              new AutonomousPublicationTransport.FoundRecord(
                  record.uri(), CID, record.recordJson().replace("確認した全文", "別の本文"));
          case "extra_field" ->
              new AutonomousPublicationTransport.FoundRecord(
                  record.uri(), CID, record.recordJson().replaceFirst("\\{", "{\"facets\":[],"));
          case "invalid_json" ->
              new AutonomousPublicationTransport.FoundRecord(record.uri(), CID, "{PRIVATE");
          default -> null;
        };
    if (defect.equals("lookup_failure"))
      transport.lookupFailure = new IllegalStateException("PRIVATE_PROVIDER_BODY");
    assertThat(service.reconcile(initial.id(), 1, "api-client")).isEqualTo(unknown);
    assertThat(store.get(initial.id())).isEqualTo(unknown);
    assertThat(transport.records).hasSize(1);
    assertThatThrownBy(this::publish).isInstanceOf(AutonomousConflictException.class);
  }

  @Test
  void stalePendingDeniedAndStoppingReconciliationsDoNotPerformNetworkReads() {
    assertThatThrownBy(() -> service.reconcile(initial.id(), 1, "api-client"))
        .isInstanceOf(AutonomousConflictException.class);
    uncertain();
    assertThatThrownBy(() -> service.reconcile(initial.id(), 2, "api-client"))
        .isInstanceOf(AutonomousConflictException.class);
    allowed.set(false);
    assertThatThrownBy(() -> service.reconcile(initial.id(), 1, "api-client"))
        .isInstanceOf(SecurityException.class);
    allowed.set(true);
    store.stop();
    assertThatThrownBy(() -> service.reconcile(initial.id(), 1, "api-client"))
        .isInstanceOf(AutonomousConflictException.class);
    assertThat(transport.lookups).isZero();
  }

  @Test
  void onlyAnExplicitPublicationSendsThePersistedSelectedRecord() {
    assertThat(transport.records).isEmpty();
    transport.behavior =
        (record, gate) -> {
          var persisted = store.get(initial.id());
          assertThat(persisted.state()).isEqualTo(AutonomousDraft.State.PUBLISHING);
          assertThat(persisted.approval().approvedBy()).isEqualTo("api-client");
          assertThat(persisted.publication().recordJson()).isEqualTo(record.recordJson());
          assertThat(gate.getAsBoolean()).isTrue();
          return success(record);
        };
    var result = publish();
    assertThat(result.state()).isEqualTo(AutonomousDraft.State.PUBLISHED);
    assertThat(transport.records).hasSize(1);
    assertThat(transport.records.getFirst().text()).isEqualTo(initial.content().text());
    assertThat(transport.records.getFirst().recordJson())
        .doesNotContain("PRIVATE_REASON", "topic:one:1");
    assertThatThrownBy(this::publish).isInstanceOf(AutonomousConflictException.class);
    assertThat(transport.records).hasSize(1);
  }

  @Test
  void deniedExistingSendRightsDoNotStartAPublicationOrAuthenticate() {
    allowed.set(false);
    transport.validation =
        text -> {
          throw new AssertionError("Denied send must not reach transport");
        };
    assertThatThrownBy(this::publish).isInstanceOf(SecurityException.class);
    assertUnclaimed();
  }

  @Test
  void localConfigurationFailureDoesNotLockTheDraft() {
    transport.validation =
        text -> {
          throw new SecurityException("Posting is disabled");
        };
    assertThatThrownBy(this::publish).isInstanceOf(SecurityException.class);
    assertUnclaimed();
  }

  @Test
  void staleReviewDoesNotSendTheNewerBody() {
    store.edit(initial.id(), 1, "別の画面で編集した本文");
    assertThatThrownBy(this::publish).isInstanceOf(AutonomousConflictException.class);
    assertThat(transport.records).isEmpty();
    assertThat(store.publicationRecord(initial.id())).isEmpty();
    assertThat(store.get(initial.id()).approval()).isNull();
  }

  @Test
  void shutdownRefusesNewPublicationWithoutSending() {
    store.stop();
    assertThatThrownBy(this::publish).isInstanceOf(AutonomousConflictException.class);
    assertUnclaimed();
  }

  @Test
  void anExceptionAfterEnteringTransportIsUnknownAndNeverAutomaticallyRetried() {
    transport.behavior =
        (record, gate) -> {
          throw new IllegalStateException("PRIVATE_PROVIDER_FRAGMENT");
        };
    var result = publish();
    assertThat(result.state()).isEqualTo(AutonomousDraft.State.UNKNOWN);
    assertThat(result.publication().failureReason())
        .doesNotContain("PRIVATE")
        .matches("[a-z0-9_]+");
    assertThat(store.publicationRecord(initial.id())).contains(transport.records.getFirst());
    assertThatThrownBy(this::publish).isInstanceOf(AutonomousConflictException.class);
    assertThat(transport.records).hasSize(1);
  }

  @Test
  void aKnownUnsentFailureNeedsAnExplicitRetryAndReusesTheFixedRecord() {
    transport.behavior =
        (record, gate) ->
            new AutonomousPublicationTransport.Receipt(
                AutonomousPublicationStore.Outcome.FAILED, null, null, "authentication_failed");
    assertThat(publish().state()).isEqualTo(AutonomousDraft.State.FAILED);
    assertThat(transport.records).hasSize(1);
    transport.behavior = (record, gate) -> success(record);
    assertThat(publish().state()).isEqualTo(AutonomousDraft.State.PUBLISHED);
    assertThat(transport.records).hasSize(2);
    assertThat(transport.records.get(1)).isEqualTo(transport.records.getFirst());
  }

  @Test
  void theFinalTransportGateReadsCurrentRightsAndShutdownState() {
    transport.behavior =
        (record, gate) -> {
          allowed.set(false);
          assertThat(gate.getAsBoolean()).isFalse();
          allowed.set(true);
          store.stop();
          assertThat(gate.getAsBoolean()).isFalse();
          return new AutonomousPublicationTransport.Receipt(
              AutonomousPublicationStore.Outcome.FAILED, null, null, "publication_blocked");
        };
    assertThat(publish().state()).isEqualTo(AutonomousDraft.State.FAILED);
  }

  @Test
  void interruptedTransportStillRecordsUncertaintyAndPreservesTheInterrupt() throws Exception {
    transport.behavior =
        (record, gate) -> {
          Thread.currentThread().interrupt();
          return new AutonomousPublicationTransport.Receipt(
              AutonomousPublicationStore.Outcome.UNKNOWN, null, null, "cancelled");
        };
    try (var executor = Executors.newSingleThreadExecutor()) {
      var task =
          executor.submit(() -> {
            assertThat(publish().state()).isEqualTo(AutonomousDraft.State.UNKNOWN);
            return Thread.currentThread().isInterrupted();
          });
      assertThat(task.get(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(store.get(initial.id()).state()).isEqualTo(AutonomousDraft.State.UNKNOWN);
  }

  @ParameterizedTest
  @ValueSource(strings = {"null_receipt", "null_outcome", "raw_failure", "raw_lowercase_failure"})
  void malformedTransportResultsBecomeSafeUncertainty(String defect) {
    transport.behavior =
        (record, gate) ->
            switch (defect) {
              case "null_receipt" -> null;
              case "null_outcome" ->
                  new AutonomousPublicationTransport.Receipt(null, null, null, "PRIVATE");
              case "raw_lowercase_failure" ->
                  new AutonomousPublicationTransport.Receipt(
                      AutonomousPublicationStore.Outcome.UNKNOWN,
                      null,
                      null,
                      "private_provider_fragment");
              default ->
                  new AutonomousPublicationTransport.Receipt(
                      AutonomousPublicationStore.Outcome.UNKNOWN,
                      null,
                      null,
                      "PRIVATE_PROVIDER_FRAGMENT");
            };
    var result = publish();
    assertThat(result.state()).isEqualTo(AutonomousDraft.State.UNKNOWN);
    assertThat(result.publication().failureReason())
        .doesNotContain("PRIVATE", "private_provider_fragment")
        .matches("[a-z0-9_]+");
    assertThat(transport.records).hasSize(1);
  }

  private void assertUnclaimed() {
    assertThat(transport.records).isEmpty();
    assertThat(store.get(initial.id()).state()).isEqualTo(AutonomousDraft.State.PENDING_REVIEW);
    assertThat(store.publicationRecord(initial.id())).isEmpty();
    assertThat(store.get(initial.id()).approval()).isNull();
  }

  private static AutonomousPublicationTransport.Receipt success(AutonomousPostRecord record) {
    return new AutonomousPublicationTransport.Receipt(
        AutonomousPublicationStore.Outcome.PUBLISHED, record.uri(), CID, "");
  }

  private final class FakeTransport implements AutonomousPublicationTransport {
    private FoundRecord found;
    private RuntimeException lookupFailure;
    private int lookups;
    private final List<AutonomousPostRecord> records = new ArrayList<>();
    private Consumer<String> validation = text -> {};
    private BiFunction<AutonomousPostRecord, BooleanSupplier, Receipt> behavior =
        (record, gate) -> success(record);

    @Override
    public void validate(String text) {
      validation.accept(text);
    }

    @Override
    public FoundRecord lookup(AutonomousPostRecord record) {
      lookups++;
      if (lookupFailure != null) throw lookupFailure;
      return found;
    }

    @Override
    public Receipt send(AutonomousPostRecord record, BooleanSupplier gate) {
      records.add(record);
      return behavior.apply(record, gate);
    }
  }
}
