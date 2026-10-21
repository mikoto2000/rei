package dev.mikoto2000.rei.core.policy;

import static dev.mikoto2000.rei.timing.TimingRecorder.Category;
import static dev.mikoto2000.rei.timing.TimingRecorder.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.timing.TimingStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** ツール承認時間計測の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ToolApprovalTimingTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** ツール利用の権限制御で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void humanDecisionAfterRunEndClipsOccupancyAndDoesNotCopyArguments() {
    var tick = new AtomicLong();
    var clock = Clock.systemUTC();
    var store = new TimingStore(true, 10, 20, 100, Duration.ofHours(1), clock, tick::get);
    var repository =
        new ToolApprovalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("approval.db")), clock);
    repository.setTiming(store);
    var owner = new AgentRunContext("run", "session", dir, "project");
    store.beginRun("run", "project", "session");
    tick.set(2);
    var request = repository.request("custom", "ARGUMENT-SECRET", owner);
    tick.set(5);
    store.finishRun("run", Status.SUCCESS);
    assertTrue(store.latest("project", "session").orElseThrow().incomplete());
    tick.set(30);
    repository.decide("project", request.id(), true);
    var run = store.latest("project", "session").orElseThrow();
    assertEquals(30, run.spans().getFirst().endNanos());
    assertEquals(3, run.summary().occupancyNanos().get(Category.APPROVAL_WAIT));
    assertFalse(run.toString().contains("SECRET"));
    assertFalse(run.incomplete());
  }

  /** ツール利用の権限制御で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void repeatedRequestAndForeignProjectDoNotCreateOrFinishAnotherSpan() {
    var tick = new AtomicLong();
    var clock = Clock.systemUTC();
    var store = new TimingStore(true, 10, 20, 100, Duration.ofHours(1), clock, tick::get);
    var repository =
        new ToolApprovalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("approval.db")), clock);
    repository.setTiming(store);
    var owner = new AgentRunContext("run", "session", dir, "project");
    store.beginRun("run", "project", "session");
    var request = repository.request("custom", "{}", owner);
    repository.request("custom", "{}", owner);
    assertEquals(1, store.statistics().spans());
    assertThrows(
        IllegalArgumentException.class, () -> repository.decide("foreign", request.id(), false));
    assertEquals(
        Status.INCOMPLETE,
        store.latest("project", "session").orElseThrow().spans().getFirst().status());
  }
}
