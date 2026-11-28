package dev.mikoto2000.rei.autonomous;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/** Immutable text-only record, independent of authentication and transport. */
public record AutonomousPostRecord(
    String did, String recordKey, String recordJson, String recordHash) {
  public static final String COLLECTION = "app.bsky.feed.post";
  private static final String CID_BASE32 = "abcdefghijklmnopqrstuvwxyz234567";
  private static final Pattern DID_SYNTAX =
      Pattern.compile("did:[a-z0-9]+:[A-Za-z0-9._:%-]*[A-Za-z0-9._%-]");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(4)
                          .maxStringLength(8192)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public AutonomousPostRecord {
    if (!validDid(did) || !AutonomousTidGenerator.valid(recordKey)) throw invalid();
    value(recordJson);
    if (recordHash == null || !hash(recordJson).equals(recordHash)) throw invalid();
  }

  public static AutonomousPostRecord fromDraft(
      AutonomousDraft draft, String recordKey, Instant createdAt) {
    if (draft == null
        || createdAt == null
        || draft.actionType() != AutonomousDraft.ActionType.BLUESKY_POST
        || draft.target() == null
        || draft.content() == null) throw invalid();
    var node = JSON.createObjectNode();
    node.put("$type", COLLECTION);
    node.put("text", draft.content().text());
    node.put("createdAt", createdAt.toString());
    var raw = node.toString();
    return new AutonomousPostRecord(draft.target().did(), recordKey, raw, hash(raw));
  }

  public String uri() {
    return "at://" + did + "/" + COLLECTION + "/" + recordKey;
  }

  public String text() {
    return value(recordJson).path("text").textValue();
  }

  public boolean matches(String uri, String cid, String foundRecordJson) {
    if (!uri().equals(uri) || !validRecordCid(cid)) return false;
    try {
      return value(recordJson).equals(value(foundRecordJson));
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  public static boolean validRecordCid(String cid) {
    if (cid == null || cid.length() != 59 || cid.charAt(0) != 'b') return false;
    var decoded = new byte[36];
    int buffer = 0, bits = 0, count = 0;
    for (int i = 1; i < cid.length(); i++) {
      int digit = CID_BASE32.indexOf(cid.charAt(i));
      if (digit < 0) return false;
      buffer = (buffer << 5) | digit;
      bits += 5;
      if (bits >= 8) {
        bits -= 8;
        if (count >= decoded.length) return false;
        decoded[count++] = (byte) (buffer >>> bits);
        buffer &= (1 << bits) - 1;
      }
    }
    return count == decoded.length
        && bits == 2
        && buffer == 0
        && decoded[0] == 1
        && decoded[1] == 0x71
        && decoded[2] == 0x12
        && decoded[3] == 0x20;
  }

  private static boolean validDid(String did) {
    if (did == null || did.length() > 2048 || !DID_SYNTAX.matcher(did).matches()) return false;
    try {
      return did.equals(URI.create("at://" + did + "/" + COLLECTION + "/sample").getRawAuthority());
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  private static JsonNode value(String raw) {
    if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 8192) throw invalid();
    try {
      var node = JSON.readTree(raw);
      if (node == null
          || !node.isObject()
          || node.size() != 3
          || !COLLECTION.equals(node.path("$type").textValue())
          || !node.path("text").isTextual()
          || !node.path("createdAt").isTextual()) throw invalid();
      AutonomousDraft.Content.create(1, node.path("text").textValue(), "", List.of());
      Instant.parse(node.path("createdAt").textValue());
      return node;
    } catch (IOException | IllegalArgumentException | java.time.DateTimeException malformed) {
      // Parser and date exceptions can contain record fragments; keep errors generic.
      throw invalid();
    }
  }

  private static String hash(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid fixed Bluesky post record");
  }
}
