package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SecretaryLedgerTest {
  @TempDir Path root;
  final Clock clock = Clock.fixed(Instant.parse("2026-10-25T01:00:00Z"), ZoneOffset.UTC);

  SecretaryLedger ledger() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    return new SecretaryLedger(
        new dev.mikoto2000.rei.storage.SqliteSecretaryDatabase(root.toString()), clock);
  }

  @Test
  void ambiguousConversationOnlyCreatesCandidateAndReplaySurvivesRestart() throws Exception {
    var ledger = ledger();
    var candidates = ledger.extract("p", "session/run", "来週あたり Phase 2 も進めたい");
    assertEquals(1, candidates.size());
    assertEquals(SecretaryTask.Status.CANDIDATE, candidates.getFirst().status());
    assertEquals(SecretaryTask.Intent.IDEA, candidates.getFirst().intent());
    assertEquals(candidates, ledger().extract("p", "session/run", "来週あたり Phase 2 も進めたい"));
    assertEquals(1, ledger.list("p", 0, 100).size());
    assertEquals("session/run#0", ledger.sources("p", candidates.getFirst().id()).getFirst());
    assertThrows(
        IllegalArgumentException.class, () -> ledger.extract("p", "session/run", "違うタスクをやりたい"));
  }

  @Test
  void explicitRegistrationApprovalUpdatesAndTerminalStatesAreAudited() throws Exception {
    var ledger = ledger();
    var task =
        ledger.create(
            "p",
            "manual-1",
            "release",
            SecretaryTask.Intent.COMMITMENT,
            LocalDate.of(2026, 10, 26),
            1,
            List.of("rei"),
            List.of("run:1"));
    assertEquals(SecretaryTask.Status.OPEN, task.status());
    assertEquals(
        task,
        ledger.create(
            "p",
            "manual-1",
            "release",
            SecretaryTask.Intent.COMMITMENT,
            LocalDate.of(2026, 10, 26),
            1,
            List.of("rei"),
            List.of("run:1")));
    var waiting =
        ledger.update(
            "p",
            task.id(),
            task.revision(),
            "release",
            task.intent(),
            SecretaryTask.Status.WAITING,
            null,
            2,
            List.of("tag"),
            List.of("dependency:1"));
    assertEquals(null, waiting.due());
    assertThrows(
        IllegalStateException.class,
        () -> ledger.transition("p", task.id(), task.revision(), SecretaryTask.Status.DONE));
    var done = ledger.transition("p", task.id(), waiting.revision(), SecretaryTask.Status.DONE);
    assertEquals(SecretaryTask.Status.DONE, ledger().get("p", task.id()).status());
    assertEquals(3, ledger.history("p", task.id()).size());
    assertThrows(
        IllegalArgumentException.class,
        () -> ledger.transition("p", done.id(), done.revision(), SecretaryTask.Status.OPEN));
    assertThrows(IllegalArgumentException.class, () -> ledger.get("other", task.id()));
  }

  @Test
  void candidateNeedsApprovalAndCancellationIsRetained() throws Exception {
    var ledger = ledger();
    var candidate = ledger.extract("p", "event-1", "明日テストを実施します").getFirst();
    assertThrows(
        IllegalArgumentException.class,
        () -> ledger.transition("p", candidate.id(), 0, SecretaryTask.Status.DONE));
    var approved = ledger.transition("p", candidate.id(), 0, SecretaryTask.Status.OPEN);
    var cancelled =
        ledger.transition("p", candidate.id(), approved.revision(), SecretaryTask.Status.CANCELLED);
    assertEquals(cancelled, ledger.get("p", candidate.id()));
    assertEquals(3, ledger.history("p", candidate.id()).size());
  }

  @Test
  void duplicateTitlesLinkSourcesAndRelatedWorkIsDiscoverable() throws Exception {
    var ledger = ledger();
    var first = ledger.extract("p", "one", "Phase 2 のテストを進めたい").getFirst();
    assertEquals(first.id(), ledger.extract("p", "two", "Phase 2 のテストを進めたい").getFirst().id());
    assertEquals(2, ledger.sources("p", first.id()).size());
    var other =
        ledger.create(
            "p",
            "three",
            "Phase 2 のテストを実施",
            SecretaryTask.Intent.TODO,
            null,
            3,
            List.of(),
            List.of());
    assertTrue(ledger.related("p", first.id()).stream().anyMatch(t -> t.id().equals(other.id())));
    assertTrue(ledger.extract("p", "none", "今日は晴れです").isEmpty());
    assertTrue(ledger.extract("p", "negative", "このタスクは実施しない").isEmpty());
  }

  @Test
  void concurrentReplaysAndUpdatesHaveOneWinner() throws Exception {
    var ledger = ledger();
    try (var workers = Executors.newFixedThreadPool(4)) {
      var results =
          workers.invokeAll(
              java.util.stream.IntStream.range(0, 12)
                  .<java.util.concurrent.Callable<String>>mapToObj(
                      i -> () -> ledger.extract("p", "event", "資料を作成したい").getFirst().id())
                  .toList());
      assertEquals(
          1,
          results.stream()
              .map(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new AssertionError(e);
                    }
                  })
              .distinct()
              .count());
      var task = ledger.list("p", 0, 10).getFirst();
      var updates =
          workers.invokeAll(
              java.util.stream.IntStream.range(0, 4)
                  .<java.util.concurrent.Callable<Boolean>>mapToObj(
                      i ->
                          () -> {
                            try {
                              ledger.transition("p", task.id(), 0, SecretaryTask.Status.OPEN);
                              return true;
                            } catch (IllegalStateException conflict) {
                              return false;
                            }
                          })
                  .toList());
      int successes = 0;
      for (var result : updates) if (result.get()) successes++;
      assertEquals(1, successes);
    }
  }

  @Test
  void invalidInputsAndPaginationAreBounded() throws Exception {
    var ledger = ledger();
    assertThrows(IllegalArgumentException.class, () -> ledger.extract("", "e", "x"));
    assertThrows(IllegalArgumentException.class, () -> ledger.extract("p", "e", "x".repeat(32769)));
    assertThrows(IllegalArgumentException.class, () -> ledger.list("p", 0, 1001));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ledger.create("p", "e", " ", SecretaryTask.Intent.TODO, null, 0, List.of(), List.of()));
    assertFalse(ledger.list("p", 0, 10).iterator().hasNext());
  }
}
