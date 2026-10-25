package dev.mikoto2000.rei.secretary;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Service-owned network/auth adapters; external strings are evidence, never executable input. */
public interface SecretaryProvider {
  enum Service {
    CALENDAR,
    GMAIL,
    GITHUB,
    SLACK
  }

  record Auth(String state, String identity, List<String> scopes, String reason) {
    public Auth {
      scopes = List.copyOf(scopes);
    }
  }

  record Item(
      String id,
      String revision,
      String kind,
      String title,
      String text,
      String status,
      Instant occurred,
      LocalDate due,
      boolean actionable,
      Map<String, String> data) {
    public Item {
      data = Map.copyOf(data);
    }
  }

  record Page(List<Item> items, String cursor, boolean partial) {
    public Page {
      items = List.copyOf(items);
    }
  }

  record Outcome(
      String state, String id, String revision, Map<String, String> data, boolean verified) {
    public Outcome {
      data = Map.copyOf(data);
    }
  }

  final class Failure extends RuntimeException {
    private final String code;
    private final int retry;
    private final boolean uncertain;

    public Failure(String code, int retry, boolean uncertain) {
      super("External service failure", null, false, false);
      this.code = code;
      this.retry = Math.max(0, Math.min(86400, retry));
      this.uncertain = uncertain;
    }

    public String code() {
      return code;
    }

    public int retrySeconds() {
      return retry;
    }

    public boolean uncertain() {
      return uncertain;
    }
  }

  Set<Service> services();

  Auth status(Service service);

  String identity(SecretaryConnections.Connection connection);

  Page poll(SecretaryConnections.Connection connection, String cursor);

  Page read(
      SecretaryConnections.Connection connection, String operation, Map<String, String> arguments);

  Outcome write(
      SecretaryConnections.Connection connection,
      String operation,
      Map<String, String> arguments,
      String idempotencyKey);

  default Outcome verify(
      SecretaryConnections.Connection connection,
      String operation,
      Map<String, String> arguments,
      String key,
      Outcome previous) {
    return new Outcome("UNCERTAIN", previous == null ? "" : previous.id(), "", Map.of(), false);
  }

  default Auth authorizeConnection(Service service, boolean force) {
    throw new IllegalStateException("Configure credentials outside the application");
  }

  default void disconnect(Service service, boolean revoke) {
    if (revoke)
      throw new IllegalArgumentException(
          "Revoke this credential in the service's account settings");
  }
}
