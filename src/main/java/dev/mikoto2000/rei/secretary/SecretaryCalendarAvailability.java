package dev.mikoto2000.rei.secretary;

import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Adapter from existing opt-in calendar cache to a read-only life constraint. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SecretaryCalendarAvailability
    implements dev.mikoto2000.rei.life.LifeAvailability {
  private final ObjectProvider<SecretaryDatabase> databases;

  public SecretaryCalendarAvailability(ObjectProvider<SecretaryDatabase> databases) {
    this.databases = databases;
  }

  /** Only the opt-in saved calendar cache is read; no automatic network acquisition. */
  public boolean busy(Instant at) {
    return !availableUntil(at, at.plusNanos(1)).isAfter(at);
  }

  public Instant availableUntil(Instant from, Instant limit) {
    return databases
        .getObject()
        .read(
            db -> {
              Instant until = limit;
              int count = 0;
              try (var q = db.createStatement();
                  var r =
                      q.executeQuery(
                          "SELECT c.record,i.record FROM secretary_external_items i JOIN secretary_connections c ON c.id=i.connection WHERE c.service='CALENDAR' AND c.state<>'DISABLED' ORDER BY i.updated DESC LIMIT 256")) {
                while (r.next()) {
                  count++;
                  var connection =
                      SecretaryJson.JSON.readValue(
                          r.getString(1), SecretaryConnections.Connection.class);
                  if (!connection.config().enabled()) continue;
                  var item =
                      SecretaryJson.JSON.readValue(r.getString(2), SecretaryProvider.Item.class);
                  if (!item.kind().equals("CALENDAR")
                      || Set.of("CANCELLED", "CANCELED", "AVAILABLE", "UNKNOWN")
                          .contains(item.status().toUpperCase(java.util.Locale.ROOT))) continue;
                  Instant start;
                  Instant end;
                  try {
                    var startValue = item.data().get("start");
                    var endValue = item.data().get("end");
                    if (startValue == null || endValue == null)
                      throw new IllegalStateException("Cached calendar interval unknown");
                    start = java.time.ZonedDateTime.parse(startValue).toInstant();
                    end = java.time.ZonedDateTime.parse(endValue).toInstant();
                  } catch (java.time.DateTimeException malformed) {
                    // Unreadable cached occupied intervals cannot establish free time.
                    throw new IllegalStateException("Cached calendar interval unknown", malformed);
                  }
                  if (!end.isAfter(start))
                    throw new IllegalStateException("Cached calendar interval invalid");
                  if (end.isAfter(from) && start.isBefore(until))
                    until = start.isBefore(from) ? from : start;
                }
              }
              if (count == 256) throw new IllegalStateException("Calendar cache limit reached");
              return until;
            });
  }
}
