package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class SecretaryDesktopContextTest {
  final Instant now = Instant.parse("2026-10-26T00:00:00Z");
  List<ActivityRecord> records = List.of();
  int reads;
  ActivityStore store =
      new ActivityStore() {
        public void append(ActivityRecord record) {
          fail("No capture");
        }

        public void replace(ActivityRecord record) {
          fail("No write");
        }

        public List<ActivitySession> findBetween(Instant from, Instant to) {
          throw new AssertionError("Unbounded query");
        }

        public List<ActivityRecord> findRecordsBetween(Instant from, Instant to) {
          throw new AssertionError("Unbounded query");
        }

        public List<ActivityRecord> findObservationsBetweenBounded(
            Instant from, Instant to, int limit) {
          reads++;
          assertEquals(now.minusSeconds(120), from);
          assertEquals(256, limit);
          return records;
        }
      };

  ActivityRecord record(double confidence, String type, Instant at) {
    return new ActivityRecord(
        "r",
        at,
        1,
        List.of(),
        null,
        new ActivityRecord.Inference(
            "private screen summary",
            List.of(
                new ActivityRecord.Activity(
                    "m", type, "editor", "", "private title", "private project"))),
        confidence,
        List.of("private-screenshot.png"),
        0,
        false);
  }

  @Test
  void optInReadsOnlyBoundedSavedMetadataAndNeverCopiesPrivateScreenContent() {
    var beans = new StaticListableBeanFactory();
    beans.addBean("store", store);
    var properties = new ActivityProperties();
    properties.setEnabled(false);
    beans.addBean("properties", properties);
    var source =
        new SecretaryDesktopContext(
            beans.getBeanProvider(ActivityStore.class),
            beans.getBeanProvider(ActivityProperties.class));
    assertEquals("UNKNOWN", source.snapshot("p", now).state());
    assertEquals(0, reads);
    properties.setEnabled(true);
    records = List.of(record(.9, "coding", now));
    var result = source.snapshot("p", now);
    assertEquals("DEVELOPMENT", result.state());
    assertTrue(result.focused());
    assertFalse(result.toString().contains("private"));
  }

  @Test
  void lowConfidenceIdleAndUnavailableObservationsNeverAssertPresenceOrTesting() {
    var beans = new StaticListableBeanFactory();
    beans.addBean("store", store);
    var properties = new ActivityProperties();
    properties.setEnabled(true);
    beans.addBean("properties", properties);
    var source =
        new SecretaryDesktopContext(
            beans.getBeanProvider(ActivityStore.class),
            beans.getBeanProvider(ActivityProperties.class));
    records = List.of(record(.6, "coding", now));
    assertEquals("UNKNOWN", source.snapshot("p", now).state());
    records = List.of(record(.9, "idle", now));
    assertEquals("IDLE", source.snapshot("p", now).state());
    assertFalse(source.snapshot("p", now).focused());
    records = List.of();
    assertEquals("UNKNOWN", source.snapshot("p", now).state());
  }
}
