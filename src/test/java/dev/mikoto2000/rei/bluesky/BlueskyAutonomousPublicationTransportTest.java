package dev.mikoto2000.rei.bluesky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.autonomous.AutonomousDraft;
import dev.mikoto2000.rei.autonomous.AutonomousPostRecord;
import dev.mikoto2000.rei.autonomous.AutonomousPublicationStore;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlueskyAutonomousPublicationTransportTest {
  private static final String DID = "did:plc:example";
  private static final String CID = "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a";
  private BlueskyProperties properties;
  private FakeApi api;
  private BlueskyAutonomousPublicationTransport transport;
  private AutonomousPostRecord record;

  @BeforeEach
  void prepare() {
    properties = new BlueskyProperties();
    properties.setEnabled(true);
    properties.setHandle("rei.example");
    properties.setAppPassword("fixture-app-password");
    api = new FakeApi();
    transport = new BlueskyAutonomousPublicationTransport(properties, api);
    var at = Instant.parse("2026-11-28T06:00:00Z");
    var draft =
        new AutonomousDraft(
            "one",
            AutonomousDraft.ActionType.BLUESKY_POST,
            "manual:one",
            AutonomousDraft.Trigger.MANUAL,
            at,
            at,
            new AutonomousDraft.Target(DID, "rei.example"),
            AutonomousDraft.Content.create(
                1, "公開する全文\r\n次の行", "PRIVATE_REASON", List.of("topic:one:1")),
            AutonomousDraft.State.PENDING_REVIEW,
            at.plusSeconds(7200),
            "",
            false,
            0,
            null,
            null,
            null);
    record = AutonomousPostRecord.fromDraft(draft, "3jzfcijpj2z2a", at);
  }

  @Test
  void lookupOnlyUsesTheFrozenPublicIdentityWithoutAuthenticationOrPosting() {
    api.snapshot = new BlueskyApiClient.RecordSnapshot(record.uri(), CID, record.recordJson());
    properties.setAppPassword("");
    var found = transport.lookup(record);
    assertThat(found.uri()).isEqualTo(record.uri());
    assertThat(found.cid()).isEqualTo(CID);
    assertThat(found.recordJson()).isEqualTo(record.recordJson());
    assertThat(api.lookups).isEqualTo(1);
    assertThat(api.did).isEqualTo(record.did());
    assertThat(api.key).isEqualTo(record.recordKey());
    assertThat(api.authentications).isZero();
    assertThat(api.creates).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent", "failed", "interrupted"})
  void lookupFailureNeverSubmitsAnythingOrExposesProviderErrors(String defect) throws Exception {
    if (defect.equals("failed")) api.lookupFailure = new IOException("PRIVATE_PROVIDER");
    if (defect.equals("interrupted"))
      api.lookupFailure = new InterruptedException("PRIVATE_PROVIDER");
    try (var executor = Executors.newSingleThreadExecutor()) {
      executor
          .submit(() -> {
            assertThat(transport.lookup(record)).isNull();
            assertThat(Thread.currentThread().isInterrupted())
                .isEqualTo(defect.equals("interrupted"));
          })
          .get(5, TimeUnit.SECONDS);
    }
    assertThat(api.lookups).isEqualTo(1);
    assertThat(api.authentications).isZero();
    assertThat(api.creates).isZero();
  }

  @Test
  void reusesExistingCredentialsAndSendsTheExactFrozenRecordOnce() {
    transport.validate(record.text());
    assertThat(api.authentications).isZero();
    var result = transport.send(record, () -> true);
    assertThat(result.outcome()).isEqualTo(AutonomousPublicationStore.Outcome.PUBLISHED);
    assertThat(result.uri()).isEqualTo(record.uri());
    assertThat(result.cid()).isEqualTo(CID);
    assertThat(api.authentications).isEqualTo(1);
    assertThat(api.creates).isEqualTo(1);
    assertThat(api.handle).isEqualTo("rei.example");
    assertThat(api.password).isEqualTo("fixture-app-password");
    assertThat(api.did).isEqualTo(DID);
    assertThat(api.key).isEqualTo(record.recordKey());
    assertThat(api.raw).isEqualTo(record.recordJson());
    assertThat(api.jwt).isEqualTo("fixture-access-jwt");
  }

  @ParameterizedTest
  @ValueSource(strings = {"disabled", "missing_handle", "missing_password"})
  void existingPostingConfigurationIsRequiredBeforeAuthentication(String defect) {
    if (defect.equals("disabled")) properties.setEnabled(false);
    if (defect.equals("missing_handle")) properties.setHandle("");
    if (defect.equals("missing_password")) properties.setAppPassword("");
    assertThatThrownBy(() -> transport.validate(record.text()))
        .isInstanceOf(SecurityException.class);
    assertThat(transport.send(record, () -> true).outcome())
        .isEqualTo(AutonomousPublicationStore.Outcome.FAILED);
    assertThat(api.authentications).isZero();
    assertThat(api.creates).isZero();
  }

  @Test
  void existingMaximumPostLengthStillAppliesWithoutNormalizingTheBody() {
    properties.setMaxPostLength(2);
    assertThatThrownBy(() -> transport.validate(record.text()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(transport.send(record, () -> true).outcome())
        .isEqualTo(AutonomousPublicationStore.Outcome.FAILED);
    assertThat(api.authentications).isZero();
    assertThat(api.creates).isZero();
  }

  @Test
  void anInitiallyClosedSendGateDoesNotAuthenticate() {
    assertThat(transport.send(record, () -> false).outcome())
        .isEqualTo(AutonomousPublicationStore.Outcome.FAILED);
    assertThat(api.authentications).isZero();
    assertThat(api.creates).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"failed", "wrong_did", "missing_jwt", "control_jwt", "exception"})
  void authenticationFailureOrAnotherAccountNeverAttemptsCreateRecord(String defect) {
    if (defect.equals("failed")) api.success = false;
    if (defect.equals("wrong_did")) api.authDid = "did:plc:other";
    if (defect.equals("missing_jwt")) api.authJwt = null;
    if (defect.equals("control_jwt")) api.authJwt = "bad\r\nheader";
    if (defect.equals("exception")) api.authFailure = new IOException("PRIVATE_AUTH_FRAGMENT");
    var result = transport.send(record, () -> true);
    assertThat(result.outcome()).isEqualTo(AutonomousPublicationStore.Outcome.FAILED);
    assertThat(result.failureCode()).doesNotContain("PRIVATE");
    assertThat(api.authentications).isEqualTo(1);
    assertThat(api.creates).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"disabled", "handle_changed", "password_removed", "rights_revoked"})
  void rechecksConfigurationAndCurrentRightsAfterAuthentication(String defect) {
    var allowed = new AtomicBoolean(true);
    api.afterAuthentication =
        () -> {
          if (defect.equals("disabled")) properties.setEnabled(false);
          if (defect.equals("handle_changed")) properties.setHandle("other.example");
          if (defect.equals("password_removed")) properties.setAppPassword("");
          if (defect.equals("rights_revoked")) allowed.set(false);
        };
    assertThat(transport.send(record, allowed::get).outcome())
        .isEqualTo(AutonomousPublicationStore.Outcome.FAILED);
    assertThat(api.authentications).isEqualTo(1);
    assertThat(api.creates).isZero();
  }

  @Test
  void aFailureAfterAttemptingCreateRecordIsUnknownAndIsNotRetried() {
    api.createFailure = new IOException("PRIVATE_RESPONSE_FRAGMENT");
    var result = transport.send(record, () -> true);
    assertThat(result.outcome()).isEqualTo(AutonomousPublicationStore.Outcome.UNKNOWN);
    assertThat(result.failureCode()).doesNotContain("PRIVATE");
    assertThat(api.creates).isEqualTo(1);
    assertThat(api.raw).isEqualTo(record.recordJson());
  }

  @ParameterizedTest
  @ValueSource(strings = {"null_result", "wrong_uri", "invalid_cid"})
  void invalidSuccessResponsesDoNotConfirmPublication(String defect) {
    api.resultDefect = defect;
    var result = transport.send(record, () -> true);
    assertThat(result.outcome()).isEqualTo(AutonomousPublicationStore.Outcome.UNKNOWN);
    assertThat(result.uri()).isNull();
    assertThat(result.cid()).isNull();
    assertThat(api.creates).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void interruptionPreservesTheFlagAndOnlyAnUnsentOperationIsFailed(boolean duringCreate)
      throws Exception {
    if (duringCreate) api.createFailure = new InterruptedException("fixture cancellation");
    else api.authFailure = new InterruptedException("fixture cancellation");
    try (var executor = Executors.newSingleThreadExecutor()) {
      var result =
          executor
              .submit(() -> {
                var receipt = transport.send(record, () -> true);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                return receipt;
              })
              .get(5, TimeUnit.SECONDS);
      assertThat(result.outcome())
          .isEqualTo(
              duringCreate
                  ? AutonomousPublicationStore.Outcome.UNKNOWN
                  : AutonomousPublicationStore.Outcome.FAILED);
    }
    assertThat(api.creates).isEqualTo(duringCreate ? 1 : 0);
  }

  private static final class FakeApi implements BlueskyApiClient {
    private int lookups;
    private Exception lookupFailure;
    private RecordSnapshot snapshot;
    private int authentications, creates;
    private boolean success = true;
    private String authDid = DID, authJwt = "fixture-access-jwt", resultDefect = "";
    private String handle, password, jwt, did, key, raw;
    private Exception authFailure, createFailure;
    private Runnable afterAuthentication = () -> {};

    @Override
    public AuthResult authenticate(String handle, String password) {
      throw new AssertionError("The bounded record authentication method is required");
    }

    @Override
    public RecordSnapshot getFixedRecord(String did, String key)
        throws IOException, InterruptedException {
      lookups++;
      this.did = did;
      this.key = key;
      if (lookupFailure instanceof IOException failed) throw failed;
      if (lookupFailure instanceof InterruptedException cancelled) throw cancelled;
      return snapshot;
    }

    @Override
    public PostResult createPost(String jwt, String did, String text) {
      throw new AssertionError("Legacy createPost rewrites the record and must not be called");
    }

    @Override
    public AuthResult authenticateForRecord(String handle, String password)
        throws IOException, InterruptedException {
      authentications++;
      this.handle = handle;
      this.password = password;
      if (authFailure instanceof InterruptedException interrupted) throw interrupted;
      if (authFailure instanceof IOException failed) throw failed;
      afterAuthentication.run();
      return new AuthResult(success, authJwt, authDid);
    }

    @Override
    public RecordResult createRecord(String jwt, String did, String key, String raw)
        throws IOException, InterruptedException {
      creates++;
      this.jwt = jwt;
      this.did = did;
      this.key = key;
      this.raw = raw;
      if (createFailure instanceof InterruptedException interrupted) throw interrupted;
      if (createFailure instanceof IOException failed) throw failed;
      if (resultDefect.equals("null_result")) return null;
      return new RecordResult(
          "at://"
              + did
              + "/app.bsky.feed.post/"
              + key
              + (resultDefect.equals("wrong_uri") ? "extra" : ""),
          resultDefect.equals("invalid_cid") ? "invalid" : CID);
    }
  }
}
