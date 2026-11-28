package dev.mikoto2000.rei.bluesky;

import dev.mikoto2000.rei.autonomous.AutonomousPostRecord;
import dev.mikoto2000.rei.autonomous.AutonomousPublicationStore;
import dev.mikoto2000.rei.autonomous.AutonomousPublicationTransport;
import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/** Reuses existing Bluesky credentials for fixed, explicitly selected publication records. */
@Component
public final class BlueskyAutonomousPublicationTransport implements AutonomousPublicationTransport {
  private final BlueskyProperties properties;
  private final BlueskyApiClient client;

  public BlueskyAutonomousPublicationTransport(
      BlueskyProperties properties, BlueskyApiClient client) {
    this.properties = Objects.requireNonNull(properties);
    this.client = Objects.requireNonNull(client);
  }

  @Override
  public void validate(String text) {
    if (!properties.isEnabled()
        || blank(properties.getHandle())
        || blank(properties.getAppPassword()))
      throw new SecurityException("Bluesky posting is disabled or not configured");
    if (blank(text) || text.length() > properties.getMaxPostLength())
      throw new IllegalArgumentException("Post text exceeds the configured Bluesky limit");
  }

  @Override
  public Receipt send(AutonomousPostRecord record, BooleanSupplier canSend) {
    try {
      validate(record.text());
      if (!canSend.getAsBoolean()) return failed("publication_blocked");
    } catch (RuntimeException blocked) {
      return failed("publication_blocked");
    }
    String handle = properties.getHandle();
    BlueskyApiClient.AuthResult auth;
    try {
      auth = client.authenticateForRecord(handle, properties.getAppPassword());
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      return failed("cancelled");
    } catch (IOException | RuntimeException unavailable) {
      return failed("authentication_failed");
    }
    if (auth == null || !auth.success()) return failed("authentication_failed");
    if (!record.did().equals(auth.did())) return failed("target_changed");
    if (blank(auth.accessJwt())
        || auth.accessJwt().length() > 16384
        || auth.accessJwt().chars().anyMatch(c -> c <= 0x20 || c >= 0x7f))
      return failed("invalid_authentication_response");
    try {
      validate(record.text());
      if (!handle.equals(properties.getHandle())) return failed("configuration_changed");
      if (!canSend.getAsBoolean()) return failed("publication_blocked");
    } catch (RuntimeException blocked) {
      return failed("publication_blocked");
    }
    // Every failure after entering createRecord is uncertain; this path never retries it.
    try {
      var result =
          client.createRecord(
              auth.accessJwt(), record.did(), record.recordKey(), record.recordJson());
      if (result == null
          || !record.uri().equals(result.uri())
          || !AutonomousPostRecord.validRecordCid(result.cid()))
        return unknown("invalid_record_receipt");
      return new Receipt(
          AutonomousPublicationStore.Outcome.PUBLISHED, result.uri(), result.cid(), "");
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      return unknown("cancelled");
    } catch (IOException | RuntimeException uncertain) {
      return unknown("record_request_failed");
    }
  }

  private static Receipt failed(String code) {
    return new Receipt(AutonomousPublicationStore.Outcome.FAILED, null, null, code);
  }

  private static Receipt unknown(String code) {
    return new Receipt(AutonomousPublicationStore.Outcome.UNKNOWN, null, null, code);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
