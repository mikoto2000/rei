package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@Tag("integration")
class SecretaryPreparationSelectionTest {
  @TempDir Path root;

  SecretaryDatabase database() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    return new SqliteSecretaryDatabase(root.toString());
  }

  @Test
  void unknownEffortAndUnfinishedDependenciesDoNotConsumeTheUsersFreeTime() throws Exception {
    var ledger = new SecretaryLedger(database(), Clock.systemUTC());
    var blocker =
        ledger.create(
            "p", "blocker", "blocker", SecretaryTask.Intent.TODO, null, 3, List.of(), List.of());
    var blocked =
        ledger.create(
            "p",
            "blocked",
            "blocked",
            SecretaryTask.Intent.TODO,
            null,
            1,
            List.of("prep-minutes:5"),
            List.of("after:" + blocker.id()));
    var small =
        ledger.create(
            "p",
            "small",
            "small",
            SecretaryTask.Intent.TODO,
            null,
            2,
            List.of("prep-minutes:10", "load:LOW"),
            List.of());
    var large =
        ledger.create(
            "p",
            "large",
            "large",
            SecretaryTask.Intent.TODO,
            null,
            1,
            List.of("prep-minutes:45"),
            List.of());
    var missing =
        ledger.create(
            "p",
            "missing",
            "unknown dependency",
            SecretaryTask.Intent.TODO,
            null,
            1,
            List.of("prep-minutes:1"),
            List.of("after:missing-task"));
    var tasks = List.of(blocked, small, large, blocker, missing);
    assertEquals(List.of(small), SecretaryPreparation.choices(tasks, ledger, 60, ZoneId.of("UTC")));
    assertTrue(SecretaryPreparation.choices(tasks, ledger, 0, ZoneId.of("UTC")).isEmpty());
    ledger.transition("p", blocker.id(), blocker.revision(), SecretaryTask.Status.DONE);
    assertEquals(
        List.of(blocked, small), SecretaryPreparation.choices(tasks, ledger, 60, ZoneId.of("UTC")));
    assertEquals(null, SecretaryPreparation.minutes(blocker));
    assertEquals("LOW", SecretaryPreparation.load(small));
  }

  @Test
  void alreadySavedCalendarLimitsPersonalTimeAndNeverTreatsAnEmptyCalendarAsAvailable()
      throws Exception {
    var db = database();
    var from = Instant.parse("2026-10-26T10:00:00Z");
    var beans = new StaticListableBeanFactory();
    beans.addBean("db", db);
    var source = new SecretaryCalendarAvailability(beans.getBeanProvider(SecretaryDatabase.class));
    assertFalse(source.busy(from));
    var until = from.plusSeconds(7200);
    assertEquals(until, source.availableUntil(from, until));
    var config =
        new SecretaryConnections.Config(
            "p",
            "s",
            "private",
            SecretaryProvider.Service.CALENDAR,
            "calendar",
            true,
            600,
            false,
            false,
            "Asia/Tokyo");
    var connection =
        new SecretaryConnections.Connection("c", 0, config, "ACTIVE", "", null, from, 0, from);
    var item =
        new SecretaryProvider.Item(
            "e",
            "1",
            "CALENDAR",
            "private appointment",
            "",
            "CONFIRMED",
            from.plusSeconds(1800),
            null,
            false,
            Map.of(
                "start",
                from.plusSeconds(1800).toString(),
                "end",
                from.plusSeconds(3600).toString()));
    db.transaction(
        sql -> {
          try (var q =
              sql.prepareStatement(
                  "INSERT INTO secretary_connections VALUES('c','p','private','CALENDAR',0,?,0,'ACTIVE',0)")) {
            q.setString(1, SecretaryJson.JSON.writeValueAsString(connection));
            q.executeUpdate();
          }
          try (var q =
              sql.prepareStatement(
                  "INSERT INTO secretary_external_items VALUES('c','p','e','1',?,NULL,0)")) {
            q.setString(1, SecretaryJson.JSON.writeValueAsString(item));
            q.executeUpdate();
          }
          return null;
        });
    assertEquals(from.plusSeconds(1800), source.availableUntil(from, until));
    assertTrue(source.busy(from.plusSeconds(1800)));
    db.transaction(
        sql -> {
          try (var q = sql.createStatement()) {
            q.executeUpdate("UPDATE secretary_connections SET state='DISABLED'");
          }
          return null;
        });
    assertEquals(until, source.availableUntil(from, until));
  }
}
