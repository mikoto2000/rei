package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AutonomousPostRecordTest {
  private static final String DID = "did:plc:example";
  private static final String KEY = "3jzfcijpj2z2a";
  private static final String CID = "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a";
  private static final Instant AT = Instant.parse("2026-11-28T06:00:00.123456789Z");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String RAW =
      "{\"$type\":\"app.bsky.feed.post\",\"text\":\"本文\",\"createdAt\":\"2026-11-28T06:00:00Z\"}";

  @Test
  void freezesOnlyTheExactReviewedTextAndServerTimestamp() throws Exception {
    var text = "  全文\r\n次の行 😀 https://example.com/  ";
    var draft = draft(text);
    var frozen = AutonomousPostRecord.fromDraft(draft, KEY, AT);
    var value = JSON.readTree(frozen.recordJson());
    assertThat(value.properties())
        .extracting(java.util.Map.Entry::getKey)
        .containsExactlyInAnyOrder("$type", "text", "createdAt");
    assertThat(value.path("$type").textValue()).isEqualTo("app.bsky.feed.post");
    assertThat(value.path("text").textValue()).isEqualTo(text);
    assertThat(value.path("createdAt").textValue()).isEqualTo(AT.toString());
    assertThat(frozen.text()).isEqualTo(text);
    assertThat(frozen.did()).isEqualTo(DID);
    assertThat(frozen.recordKey()).isEqualTo(KEY);
    assertThat(frozen.uri()).isEqualTo("at://" + DID + "/app.bsky.feed.post/" + KEY);
    assertThat(frozen.recordHash()).isEqualTo(hash(frozen.recordJson()));
    assertThat(frozen.recordJson())
        .doesNotContain("PRIVATE_REASON", "PRIVATE_FEEDBACK", "topic:one:1", "rei.example");
    assertThat(draft.content().revision()).isEqualTo(3);
    assertThat(draft.approval()).isNull();
  }

  @Test
  void changingTextTimeOrTargetChangesTheFrozenRecordOrItsIdentity() {
    var first = AutonomousPostRecord.fromDraft(draft("本文"), KEY, AT);
    assertThat(AutonomousPostRecord.fromDraft(draft("別の本文"), KEY, AT).recordHash())
        .isNotEqualTo(first.recordHash());
    assertThat(AutonomousPostRecord.fromDraft(draft("本文"), KEY, AT.plusNanos(1)).recordHash())
        .isNotEqualTo(first.recordHash());
    assertThat(AutonomousPostRecord.fromDraft(draft("本文"), "3jzfcijpj2z2b", AT).uri())
        .isNotEqualTo(first.uri());
  }

  @Test
  void persistedRecordHashMustMatchTheExactJsonBytes() {
    assertThatThrownBy(() -> new AutonomousPostRecord(DID, KEY, RAW, "0".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    var value = record(RAW);
    assertThat(value.recordJson()).isEqualTo(RAW);
    assertThat(value.recordHash()).isEqualTo(hash(RAW));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "did:plc:repo/other",
        "did:plc:repo?query",
        "did:plc:repo#part",
        "https://example.com",
        ""
      })
  void rejectsTargetsThatCannotBeUsedAsTheExpectedAtUriAuthority(String did) {
    assertThatThrownBy(() -> new AutonomousPostRecord(did, KEY, RAW, hash(RAW)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonTidKeysAndMissingInputs() {
    assertThatThrownBy(() -> new AutonomousPostRecord(DID, "UUID-is-not-a-TID", RAW, hash(RAW)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AutonomousPostRecord(DID, KEY, null, hash(RAW)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AutonomousPostRecord(DID, KEY, RAW, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "[]",
        "{}",
        "{\"text\":\"本文\",\"createdAt\":\"2026-11-28T06:00:00Z\"}",
        "{\"$type\":\"OTHER\",\"text\":\"本文\",\"createdAt\":\"2026-11-28T06:00:00Z\"}",
        "{\"$type\":\"app.bsky.feed.post\",\"text\":null,\"createdAt\":\"2026-11-28T06:00:00Z\"}",
        "{\"$type\":\"app.bsky.feed.post\",\"text\":\"\",\"createdAt\":\"2026-11-28T06:00:00Z\"}",
        "{\"$type\":\"app.bsky.feed.post\",\"text\":\"@someone.example\",\"createdAt\":\"2026-11-28T06:00:00Z\"}",
        "{\"$type\":\"app.bsky.feed.post\",\"text\":\"本文\",\"createdAt\":123}",
        "{\"$type\":\"app.bsky.feed.post\",\"text\":\"本文\",\"createdAt\":\"invalid\"}",
        "{\"$type\":\"app.bsky.feed.post\",\"text\":\"本文\",\"createdAt\":\"2026-11-28T06:00:00Z\",\"reply\":null}",
        "{\"$type\":\"app.bsky.feed.post\",\"text\":\"本文\",\"text\":\"改変\",\"createdAt\":\"2026-11-28T06:00:00Z\"}",
        "{\"$type\":\"app.bsky.feed.post\",\"text\":\"本文\",\"createdAt\":\"2026-11-28T06:00:00Z\"} {}"
      })
  void rejectsAnythingOutsideTheFixedTextOnlyRecordContract(String raw) {
    assertThatThrownBy(() -> record(raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid fixed Bluesky post record")
        .hasNoCause();
  }

  @Test
  void recordSizeIsBoundedBeforeDecoding() {
    var oversized = RAW + " ".repeat(8192);
    assertThatThrownBy(() -> record(oversized)).isInstanceOf(IllegalArgumentException.class);
    var longText = RAW.replace("本文", "あ".repeat(301));
    assertThatThrownBy(() -> record(longText)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void matchesTheCompleteRecordAndExactIdentityWithoutDependingOnJsonKeyOrder() {
    var frozen = record(RAW);
    var reordered =
        "{\"createdAt\":\"2026-11-28T06:00:00Z\",\"text\":\"本文\",\"$type\":\"app.bsky.feed.post\"}";
    assertThat(frozen.matches(frozen.uri(), CID, reordered)).isTrue();
    for (var changed :
        List.of(
            RAW.replace("本文", "別の本文"),
            RAW.replace("06:00:00Z", "06:00:01Z"),
            RAW.replace("\"text\"", "\"other\""),
            RAW.substring(0, RAW.length() - 1) + ",\"labels\":null}",
            RAW + " {}")) {
      assertThat(frozen.matches(frozen.uri(), CID, changed)).isFalse();
    }
    assertThat(frozen.matches(frozen.uri() + "extra", CID, RAW)).isFalse();
    assertThat(frozen.matches(frozen.uri().replace(DID, "did:plc:other"), CID, RAW)).isFalse();
    assertThat(frozen.matches(frozen.uri(), "invalid", RAW)).isFalse();
    assertThat(frozen.matches(frozen.uri(), null, RAW)).isFalse();
    assertThat(frozen.matches(null, CID, RAW)).isFalse();
    assertThat(frozen.matches(frozen.uri(), CID, null)).isFalse();
  }

  @Test
  void acceptsTheOfficialRecordCidAndRejectsRawBlobCodecsAndNoncanonicalEncodings() {
    assertThat(AutonomousPostRecord.validRecordCid(CID)).isTrue();
    for (var invalid :
        List.of(
            CID.replace("bafyre", "bafkre"),
            CID.toUpperCase(java.util.Locale.ROOT),
            CID + "=",
            CID + "a",
            CID.substring(0, CID.length() - 1),
            CID.substring(0, CID.length() - 1) + "b",
            "bafyre",
            "",
            "QmNotACidV1")) {
      assertThat(AutonomousPostRecord.validRecordCid(invalid)).as(invalid).isFalse();
    }
    assertThat(AutonomousPostRecord.validRecordCid(null)).isFalse();
  }

  private static AutonomousPostRecord record(String raw) {
    return new AutonomousPostRecord(DID, KEY, raw, hash(raw));
  }

  private static String hash(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static AutonomousDraft draft(String text) {
    return new AutonomousDraft(
        "draft-one",
        AutonomousDraft.ActionType.BLUESKY_POST,
        "manual:fixture",
        AutonomousDraft.Trigger.MANUAL,
        AT.minusSeconds(60),
        AT.minusSeconds(60),
        new AutonomousDraft.Target(DID, "rei.example"),
        AutonomousDraft.Content.create(3, text, "PRIVATE_REASON", List.of("topic:one:1")),
        AutonomousDraft.State.PENDING_REVIEW,
        AT.plusSeconds(7200),
        "PRIVATE_FEEDBACK",
        false,
        0,
        null,
        null,
        null);
  }
}
