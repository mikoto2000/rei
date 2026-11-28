package dev.mikoto2000.rei.bluesky;

import dev.mikoto2000.rei.autonomous.AutonomousPostRecord;
import dev.mikoto2000.rei.autonomous.AutonomousPublicationStore;
import dev.mikoto2000.rei.autonomous.AutonomousPublicationTransport;
import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Reuses existing Bluesky credentials for fixed, explicitly selected publication records. */
@Component
public final class BlueskyAutonomousPublicationTransport implements AutonomousPublicationTransport {
  private final BooleanSupplier enabled;
  private final Supplier<String> configuredHandle;
  private final Supplier<String> password;
  private final IntSupplier maxPostLength;
  private final BlueskyApiClient client;

  public BlueskyAutonomousPublicationTransport(
      BlueskyProperties properties, BlueskyApiClient client) {
    Objects.requireNonNull(properties);
    this.enabled = properties::isEnabled;
    this.configuredHandle = properties::getHandle;
    this.password = properties::getAppPassword;
    this.maxPostLength = properties::getMaxPostLength;
    this.client = Objects.requireNonNull(client);
  }

  @Override
  public void validate(String text) {
    if (!enabled.getAsBoolean() || blank(configuredHandle.get()) || blank(password.get()))
      throw new SecurityException("Bluesky posting is disabled or not configured");
    if (blank(text) || text.length() > maxPostLength.getAsInt())
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
    String handle = configuredHandle.get();
    BlueskyApiClient.AuthResult auth;
    try {
      auth = client.authenticateForRecord(handle, password.get());
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
      if (!handle.equals(configuredHandle.get())) return failed("configuration_changed");
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

  @Override
  public FoundRecord lookup(AutonomousPostRecord record) {
    try {
      var found = client.getFixedRecord(record.did(), record.recordKey());
      return found == null ? null : new FoundRecord(found.uri(), found.cid(), found.recordJson());
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      return null;
    } catch (IOException | RuntimeException unavailable) {
      return null;
    }
  }

  private static Receipt unknown(String code) {
    return new Receipt(AutonomousPublicationStore.Outcome.UNKNOWN, null, null, code);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
