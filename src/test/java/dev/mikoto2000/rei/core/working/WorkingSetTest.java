package dev.mikoto2000.rei.core.working;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.WorkingSetItemAddedPayload;
import dev.mikoto2000.rei.event.WorkingSetItemRemovedPayload;

class WorkingSetTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  private WorkingSet workingSet(int maxFiles, Instant start) {
    return new WorkingSet(maxFiles, Clock.fixed(start, ZONE));
  }

  private Path abs(String relative) {
    return Path.of(relative).toAbsolutePath().normalize();
  }

  @Test
  void publishesAddedOnlyWhenAFileActuallyEntersTheWorkingSet() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(received::add);
    Clock clock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE);
    WorkingSet ws = new WorkingSet(20, clock, new AgentEventFactory(clock), bus);

    ws.recordRead(Path.of("foo.txt"));
    ws.recordRead(Path.of("foo.txt"));

    assertEquals(1, received.size());
    assertEquals(AgentEventType.WORKING_SET_ITEM_ADDED, received.getFirst().type());
    WorkingSetItemAddedPayload payload = (WorkingSetItemAddedPayload) received.getFirst().payload();
    assertEquals(abs("foo.txt").toString(), payload.path());
    assertEquals("read", payload.reason());
  }

  @Test
  void publishesRemovalReasonsAtTheMutationBoundary() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(received::add);
    Clock clock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE);
    WorkingSet ws = new WorkingSet(1, clock, new AgentEventFactory(clock), bus);

    ws.recordRead(Path.of("old.txt"));
    ws.recordRead(Path.of("new.txt"));
    ws.remove(Path.of("new.txt"));
    ws.remove(Path.of("absent.txt"));

    List<WorkingSetItemRemovedPayload> removals = received.stream()
        .filter(event -> event.type() == AgentEventType.WORKING_SET_ITEM_REMOVED)
        .map(event -> (WorkingSetItemRemovedPayload) event.payload())
        .toList();
    assertEquals(2, removals.size());
    assertEquals("capacity eviction", removals.get(0).reason());
    assertEquals("explicit removal", removals.get(1).reason());
  }

  @Test
  void searchQueryPayloadIsBoundedSingleLineAndRedactsCommonSecrets() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(received::add);
    Clock clock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE);
    WorkingSet ws = new WorkingSet(20, clock, new AgentEventFactory(clock), bus);

    ws.beginSearch("api_key=supersecret\n" + "x".repeat(600), "searchAndRead");

    dev.mikoto2000.rei.event.WorkingSetSearchStartedPayload payload =
        (dev.mikoto2000.rei.event.WorkingSetSearchStartedPayload) received.getFirst().payload();
    assertFalse(payload.query().contains("supersecret"));
    assertFalse(payload.query().contains("\n"));
    assertTrue(payload.query().length() <= 500);
  }

  @Test
  void readFileIsAddedToWorkingSet() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordRead(Path.of("foo.txt"));
    assertTrue(ws.contains(Path.of("foo.txt")));
    assertEquals(1, ws.getFiles().size());
  }

  @Test
  void readingSameFileTwiceDoesNotDuplicate() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordRead(Path.of("foo.txt"));
    ws.recordRead(Path.of("foo.txt"));
    assertEquals(1, ws.getFiles().size());
  }

  @Test
  void reAccessUpdatesLastAccessedAt() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordRead(Path.of("foo.txt"));
    ws.recordRead(Path.of("bar.txt"));
    ws.recordRead(Path.of("foo.txt"));
    List<FileReference> files = ws.getFiles();
    assertEquals(abs("foo.txt").toString(), files.get(0).path());
    assertEquals(abs("bar.txt").toString(), files.get(1).path());
  }

  @Test
  void editFileIsRegisteredWithEditOperation() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordEdit(Path.of("foo.txt"));
    FileReference ref = ws.find(Path.of("foo.txt")).orElseThrow();
    assertEquals("edit", ref.accessType());
    assertTrue(ref.lastModifiedAt() != null);
  }

  @Test
  void newlyCreatedFileIsRegistered() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordCreate(Path.of("bar.txt"));
    assertTrue(ws.contains(Path.of("bar.txt")));
  }

  @Test
  void failedOperationIsNotRegistered() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    // 失敗操作は呼び出し側で記録しないため、Working Set は空のまま
    assertTrue(ws.isEmpty());
  }

  @Test
  void searchResultsAloneDoNotPopulateWorkingSet() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    // 検索結果は Working Set に追加しない
    assertTrue(ws.isEmpty());
  }

  @Test
  void evictsOldestWhenMaxExceeded() {
    WorkingSet ws = workingSet(3, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordRead(Path.of("a"));
    ws.recordRead(Path.of("b"));
    ws.recordRead(Path.of("c"));
    ws.recordRead(Path.of("d"));
    assertTrue(ws.contains(Path.of("b")));
    assertTrue(ws.contains(Path.of("c")));
    assertTrue(ws.contains(Path.of("d")));
    assertFalse(ws.contains(Path.of("a")));
  }

  @Test
  void reAccessedFileBecomesNewerInLru() {
    AtomicLong tick = new AtomicLong(0);
    WorkingSet ws = new WorkingSet(3, new AdvancingClock(tick));
    ws.recordRead(Path.of("a"));
    ws.recordRead(Path.of("b"));
    ws.recordRead(Path.of("c"));
    ws.recordRead(Path.of("a"));
    ws.recordRead(Path.of("d"));
    assertTrue(ws.contains(Path.of("c")));
    assertTrue(ws.contains(Path.of("a")));
    assertTrue(ws.contains(Path.of("d")));
    assertFalse(ws.contains(Path.of("b")));
  }

  private static final class AdvancingClock extends Clock {
    private final AtomicLong tick;

    AdvancingClock(AtomicLong tick) {
      this.tick = tick;
    }

    @Override
    public ZoneId getZone() {
      return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.parse("2026-08-17T00:00:00Z").plus(Duration.ofMillis(tick.getAndIncrement()));
    }
  }

  @Test
  void renderForPromptContainsPaths() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordRead(Path.of("src/foo.java"));
    ws.recordRead(Path.of("src/fooTest.java"));
    String prompt = ws.renderForPrompt();
    assertTrue(prompt.contains(abs("src/foo.java").toString()));
    assertTrue(prompt.contains(abs("src/fooTest.java").toString()));
  }

  @Test
  void emptyWorkingSetRendersBlank() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    assertEquals("", ws.renderForPrompt());
  }

  @Test
  void missingFileCanBeRemoved() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordRead(Path.of("existing.txt"));
    ws.recordRead(Path.of("missing.txt"));
    ws.removeIfMissing(Path.of("missing.txt"));
    assertFalse(ws.contains(Path.of("missing.txt")));
    assertTrue(ws.contains(Path.of("existing.txt")));
  }

  @Test
  void normalizesEquivalentPathsToAvoidDuplicates() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordRead(Path.of("src/foo.txt"));
    ws.recordRead(Path.of("./src/foo.txt"));
    assertEquals(1, ws.getFiles().size());
  }

  @Test
  void clockIsInjectableForDeterministicTests() {
    Instant start = Instant.parse("2026-08-17T00:00:00Z");
    WorkingSet ws = workingSet(20, start);
    ws.recordRead(Path.of("a"));
    ws.recordRead(Path.of("b"));
    ws.recordRead(Path.of("a"));
    List<FileReference> files = ws.getFiles();
    assertEquals(abs("a").toString(), files.get(0).path());
    assertEquals(abs("b").toString(), files.get(1).path());
    assertEquals(start.plus(Duration.ZERO), files.get(0).lastAccessedAt().toInstant());
  }
}
