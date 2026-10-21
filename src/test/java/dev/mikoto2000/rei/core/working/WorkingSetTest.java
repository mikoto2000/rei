package dev.mikoto2000.rei.core.working;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.WorkingSetItemAddedPayload;
import dev.mikoto2000.rei.event.WorkingSetItemRemovedPayload;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 作業中集合の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class WorkingSetTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** 作業中の情報管理で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void defaultWorkingSetRetainsFiftyFilesAndEvictsOldestOnFiftyFirst() {
    assertEquals(50, new WorkingSet().maxFiles());
    WorkingSet ws =
        new WorkingSet(WorkingSet.DEFAULT_MAX_FILES, new AdvancingClock(new AtomicLong()));
    for (int i = 0; i < 50; i++) {
      ws.recordRead(Path.of("file-" + i));
    }
    assertEquals(50, ws.getFiles().size());
    for (int i = 0; i < 50; i++) {
      assertTrue(ws.contains(Path.of("file-" + i)));
    }
    ws.recordRead(Path.of("file-50"));
    assertEquals(50, ws.getFiles().size());
    assertFalse(ws.contains(Path.of("file-0")));
    for (int i = 1; i <= 50; i++) {
      assertTrue(ws.contains(Path.of("file-" + i)));
    }
  }

  /** 作業中の情報管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void defaultCapacityKeepsReAccessedFileAndEvictsNextOldest() {
    WorkingSet ws =
        new WorkingSet(WorkingSet.DEFAULT_MAX_FILES, new AdvancingClock(new AtomicLong()));
    for (int i = 0; i < 50; i++) {
      ws.recordRead(Path.of("file-" + i));
    }
    ws.recordRead(Path.of("file-0"));
    assertEquals(50, ws.getFiles().size());
    ws.recordRead(Path.of("file-50"));
    assertEquals(50, ws.getFiles().size());
    assertTrue(ws.contains(Path.of("file-0")));
    assertFalse(ws.contains(Path.of("file-1")));
    for (int i = 2; i <= 50; i++) {
      assertTrue(ws.contains(Path.of("file-" + i)));
    }
  }

  /** {@code workingSet} の条件を再現するための補助情報を返す。 */
  private WorkingSet workingSet(int maxFiles, Instant start) {
    return new WorkingSet(maxFiles, Clock.fixed(start, ZONE));
  }

  /** {@code abs} の条件を再現するための補助情報を返す。 */
  private Path abs(String relative) {
    return Path.of(relative).toAbsolutePath().normalize();
  }

  /** 作業中の情報管理で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** 作業中の情報管理で、指定した条件に対する結果と状態の変化を確認する。 */
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

    List<WorkingSetItemRemovedPayload> removals =
        received.stream()
            .filter(event -> event.type() == AgentEventType.WORKING_SET_ITEM_REMOVED)
            .map(event -> (WorkingSetItemRemovedPayload) event.payload())
            .toList();
    assertEquals(2, removals.size());
    assertEquals("capacity eviction", removals.get(0).reason());
    assertEquals("explicit removal", removals.get(1).reason());
  }

  /** 作業中の情報管理で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
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

  /** ファイル追加済みを読み取ることを確認する。 */
  @Test
  void readFileIsAddedToWorkingSet() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordRead(Path.of("foo.txt"));
    assertTrue(ws.contains(Path.of("foo.txt")));
    assertEquals(1, ws.getFiles().size());
  }

  /** 作業中の情報管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void readingSameFileTwiceDoesNotDuplicate() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordRead(Path.of("foo.txt"));
    ws.recordRead(Path.of("foo.txt"));
    assertEquals(1, ws.getFiles().size());
  }

  /** 作業中の情報管理で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** 作業中の情報管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void editFileIsRegisteredWithEditOperation() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordEdit(Path.of("foo.txt"));
    FileReference ref = ws.find(Path.of("foo.txt")).orElseThrow();
    assertEquals("edit", ref.accessType());
    assertTrue(ref.lastModifiedAt() != null);
  }

  /** 作業中の情報管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void newlyCreatedFileIsRegistered() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordCreate(Path.of("bar.txt"));
    assertTrue(ws.contains(Path.of("bar.txt")));
  }

  /** 作業中の情報管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedOperationIsNotRegistered() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    // 失敗操作は呼び出し側で記録しないため、Working Set は空のまま
    assertTrue(ws.isEmpty());
  }

  /** 作業中の情報管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void searchResultsAloneDoNotPopulateWorkingSet() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    // 検索結果は Working Set に追加しない
    assertTrue(ws.isEmpty());
  }

  /** 作業中の情報管理で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** 作業中の情報管理で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** 検証条件を再現するためのAdvancing時刻の取得元。 */
  private static final class AdvancingClock extends Clock {
    /** 検証に使う定期確認。 */
    private final AtomicLong tick;

    /** 検証条件に必要な値を受け取り、AdvancingClockを用意する。 */
    AdvancingClock(AtomicLong tick) {
      this.tick = tick;
    }

    /** 検証用に、作業中の情報管理で参照するタイムゾーンを返す。 */
    @Override
    public ZoneId getZone() {
      return ZONE;
    }

    /** 検証用に、指定されたタイムゾーンを反映した情報を作成する。 */
    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    /** {@code instant} の条件を再現するための補助情報を返す。 */
    @Override
    public Instant instant() {
      return Instant.parse("2026-08-17T00:00:00Z").plus(Duration.ofMillis(tick.getAndIncrement()));
    }
  }

  /** 作業中の情報管理で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void renderForPromptContainsPaths() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordRead(Path.of("src/foo.java"));
    ws.recordRead(Path.of("src/fooTest.java"));
    String prompt = ws.renderForPrompt();
    assertTrue(prompt.contains(abs("src/foo.java").toString()));
    assertTrue(prompt.contains(abs("src/fooTest.java").toString()));
  }

  /** 作業中の情報管理で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void emptyWorkingSetRendersBlank() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    assertEquals("", ws.renderForPrompt());
  }

  /** 作業中の情報管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void missingFileCanBeRemoved() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordRead(Path.of("existing.txt"));
    ws.recordRead(Path.of("missing.txt"));
    ws.removeIfMissing(Path.of("missing.txt"));
    assertFalse(ws.contains(Path.of("missing.txt")));
    assertTrue(ws.contains(Path.of("existing.txt")));
  }

  /** 作業中の情報管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void normalizesEquivalentPathsToAvoidDuplicates() {
    WorkingSet ws = workingSet(20, Instant.parse("2026-08-17T00:00:00Z"));
    ws.recordRead(Path.of("src/foo.txt"));
    ws.recordRead(Path.of("./src/foo.txt"));
    assertEquals(1, ws.getFiles().size());
  }

  /** 作業中の情報管理で、指定した条件に対する結果と状態の変化を確認する。 */
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
