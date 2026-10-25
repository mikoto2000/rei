package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.secretary.SecretaryActivity;
import java.time.Instant;
import java.util.Comparator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Reuses recent persisted observations; this adapter never captures a screen or calls a model. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SecretaryDesktopContext implements SecretaryActivity {
  private final ObjectProvider<ActivityStore> stores;
  private final ObjectProvider<ActivityProperties> properties;

  public SecretaryDesktopContext(
      ObjectProvider<ActivityStore> stores, ObjectProvider<ActivityProperties> properties) {
    this.stores = stores;
    this.properties = properties;
  }

  @Override
  public Snapshot snapshot(String project, Instant now) {
    var unknown = new Snapshot("UNKNOWN", null, false, false);
    var configuration = properties.getIfAvailable();
    if (configuration == null || !configuration.isEnabled()) return unknown;
    var store = stores.getIfAvailable();
    if (store == null) return unknown;
    try {
      var latest =
          store
              .findObservationsBetweenBounded(now.minusSeconds(120), now.plusNanos(1), 256)
              .stream()
              .filter(
                  r ->
                      !r.capturedAt().isAfter(now)
                          && !r.capturedAt().isBefore(now.minusSeconds(120)))
              .max(Comparator.comparing(ActivityRecord::capturedAt));
      if (latest.isEmpty()) return unknown;
      var record = latest.get();
      if (record.confidence() < 0.7 || record.inference() == null) return unknown;
      var categories =
          record.inference().activities().stream()
              .limit(16)
              .map(a -> ActivityVocabulary.category(a.type()))
              .toList();
      String state =
          categories.stream()
              .filter(c -> java.util.Set.of("development", "research", "documentation").contains(c))
              .findFirst()
              .orElse(categories.contains("idle") ? "idle" : "unknown");
      return new Snapshot(
          state.toUpperCase(java.util.Locale.ROOT),
          record.capturedAt(),
          java.util.Set.of("development", "research", "documentation").contains(state),
          false);
    } catch (RuntimeException unavailable) {
      return unknown;
    }
  }
}
