package dev.mikoto2000.rei.activity.behavior;

import static dev.mikoto2000.rei.activity.behavior.BehaviorEvaluatorTest.START;
import static dev.mikoto2000.rei.activity.behavior.BehaviorEvaluatorTest.record;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.activity.ActivityCapture;
import dev.mikoto2000.rei.activity.ActivityProperties;
import dev.mikoto2000.rei.activity.ActivityStore;
import dev.mikoto2000.rei.activity.SessionMergePolicy;
import dev.mikoto2000.rei.activity.SqliteActivityStore;
import dev.mikoto2000.rei.topic.AgentActivityTracker;
import dev.mikoto2000.rei.topic.AgentMessagePublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 作業習慣外部連携の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class BehaviorIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir java.nio.file.Path directory;

  /** 作業習慣の支援で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void realFineStoreAndCheckpointSurviveRestartWithoutTouchingTimeline() throws Exception {
    var ds = new org.sqlite.SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + directory.resolve("rei.db"));
    var store =
        new SqliteActivityStore(ds, new SessionMergePolicy(Duration.ofSeconds(90), ZoneOffset.UTC));
    var raw =
        List.of(
            record(0, 1200, "social"), record(1200, 1200, "media"), record(2400, 1200, "shopping"));
    raw.forEach(store::append);
    var end = START.plusSeconds(3600);
    var fine = store.findBetween(START, end);
    var p = new BehaviorProperties();
    p.setEnabled(true);
    var activity = new ActivityProperties();
    activity.setEnabled(true);
    var generator = mock(BehaviorMessageGenerator.class);
    when(generator.generate(any())).thenReturn("そろそろ区切りをつけようか。");
    var publisher = mock(AgentMessagePublisher.class);
    var tracker = mock(AgentActivityTracker.class);
    var capture = mock(ActivityCapture.class);
    var service =
        new BehaviorService(
            p,
            activity,
            capture,
            store,
            new SqliteBehaviorStateStore(ds),
            generator,
            publisher,
            tracker,
            Clock.fixed(end, ZoneOffset.UTC));
    service.tick();
    verify(publisher, times(1)).publish(any());
    assertEquals(BehaviorSeverity.WARNING, service.evaluate().severity());
    var restarted =
        new BehaviorService(
            p,
            activity,
            capture,
            store,
            new SqliteBehaviorStateStore(ds),
            generator,
            publisher,
            tracker,
            Clock.fixed(end, ZoneOffset.UTC));
    restarted.tick();
    verify(publisher, times(1)).publish(any());
    verify(generator, times(1)).generate(any());
    assertEquals(fine, store.findBetween(START, end));
    assertEquals(raw, store.findRecordsBetween(START, end));
    assertTrue(restarted.status().contains("cooldownUntil="));
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledCaptureDoesNotTriggerEvenWhenBehaviorIsEnabled() {
    var p = new BehaviorProperties();
    p.setEnabled(true);
    var store = mock(ActivityStore.class);
    var generator = mock(BehaviorMessageGenerator.class);
    var service =
        new BehaviorService(
            p,
            new ActivityProperties(),
            mock(ActivityCapture.class),
            store,
            mock(BehaviorStateStore.class),
            generator,
            mock(AgentMessagePublisher.class),
            mock(AgentActivityTracker.class),
            Clock.fixed(START, ZoneOffset.UTC));
    service.tick();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(store, generator);
  }
}
