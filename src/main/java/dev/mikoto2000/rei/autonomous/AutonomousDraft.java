package dev.mikoto2000.rei.autonomous;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Immutable review snapshot. Approval and execution are deliberately absent from draft mutations.
 */
public record AutonomousDraft(
    String id,
    ActionType actionType,
    String opportunityId,
    Trigger trigger,
    Instant createdAt,
    Instant updatedAt,
    Target target,
    Content content,
    State state,
    Instant expiresAt,
    String rejectionReason,
    boolean rejectionReasonPublic,
    int regenerationAttempts,
    Instant lastRegenerationAttemptAt,
    Approval approval,
    Publication publication) {

  public enum ActionType {
    BLUESKY_POST
  }

  public enum Trigger {
    MANUAL,
    SCHEDULED
  }

  public enum State {
    PENDING_REVIEW,
    REJECTED,
    EXPIRED,
    PUBLISHING,
    PUBLISHED,
    FAILED,
    UNKNOWN
  }

  public record Target(String did, String handle) {
    public Target {
      if (did == null
          || !did.matches("did:[a-z0-9]+:[^\\s]{1,2048}")
          || handle == null
          || handle.isBlank()
          || handle.length() > 253
          || handle.chars().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Invalid publication target");
    }
  }

  public record Content(
      int revision, String text, String bodyHash, String reason, List<String> sourceIds) {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    public Content {
      if (revision < 1
          || text == null
          || text.isBlank()
          || text.getBytes(StandardCharsets.UTF_8).length > 3000
          || GRAPHEME.matcher(text).results().limit(301).count() > 300)
        throw new IllegalArgumentException("Invalid Bluesky draft text");
      AutonomousPostText.validate(text);
      if (!hash(text).equals(bodyHash)) throw new IllegalArgumentException("Draft hash mismatch");
      if (reason == null || reason.length() > 2048 || sourceIds == null || sourceIds.size() > 64)
        throw new IllegalArgumentException("Invalid draft evidence");
      sourceIds = List.copyOf(sourceIds);
      if (sourceIds.stream()
          .anyMatch(id ->
              id.isBlank() || id.length() > 128 || id.chars().anyMatch(Character::isISOControl)))
        throw new IllegalArgumentException("Invalid draft source identifier");
    }

    public static Content create(int revision, String text, String reason, List<String> sourceIds) {
      return new Content(revision, text, text == null ? null : hash(text), reason, sourceIds);
    }

    private static String hash(String text) {
      try {
        var encoded =
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(encoded);
        return HexFormat.of().formatHex(digest.digest());
      } catch (CharacterCodingException malformed) {
        throw new IllegalArgumentException("Invalid Unicode draft text", malformed);
      } catch (NoSuchAlgorithmException unavailable) {
        throw new IllegalStateException(unavailable);
      }
    }
  }

  public record Approval(Instant approvedAt, String approvedBy) {}

  public record Publication(
      Instant startedAt,
      String recordKey,
      String recordJson,
      String uri,
      String cid,
      String result,
      String failureReason) {}

  public AutonomousDraft reviewed(State next, String rejection, Instant updated) {
    return new AutonomousDraft(
        id,
        actionType,
        opportunityId,
        trigger,
        createdAt,
        updated,
        target,
        content,
        next,
        expiresAt,
        rejection,
        rejectionReasonPublic,
        regenerationAttempts,
        lastRegenerationAttemptAt,
        null,
        null);
  }

  public AutonomousDraft edited(Content revised, Instant updated) {
    return new AutonomousDraft(
        id,
        actionType,
        opportunityId,
        trigger,
        createdAt,
        updated,
        target,
        revised,
        State.PENDING_REVIEW,
        expiresAt,
        rejectionReason,
        rejectionReasonPublic,
        regenerationAttempts,
        lastRegenerationAttemptAt,
        null,
        null);
  }
}
