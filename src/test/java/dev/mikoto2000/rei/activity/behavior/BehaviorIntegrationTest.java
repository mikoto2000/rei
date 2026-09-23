package dev.mikoto2000.rei.activity.behavior;

import dev.mikoto2000.rei.activity.*;
import dev.mikoto2000.rei.topic.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static dev.mikoto2000.rei.activity.behavior.BehaviorEvaluatorTest.*;

class BehaviorIntegrationTest {
  @TempDir java.nio.file.Path directory;
  @Test void realFineStoreAndCheckpointSurviveRestartWithoutTouchingTimeline() throws Exception {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("rei.db"));
    var store=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    var raw=List.of(record(0,1200,"social"),record(1200,1200,"media"),record(2400,1200,"shopping"));raw.forEach(store::append);
    var end=START.plusSeconds(3600);var fine=store.findBetween(START,end);
    var p=new BehaviorProperties();p.setEnabled(true);var activity=new ActivityProperties();activity.setEnabled(true);
    var generator=mock(BehaviorMessageGenerator.class);when(generator.generate(any())).thenReturn("そろそろ区切りをつけようか。");
    var publisher=mock(AgentMessagePublisher.class);var tracker=mock(AgentActivityTracker.class);var capture=mock(ActivityCapture.class);
    var service=new BehaviorService(p,activity,capture,store,new SqliteBehaviorStateStore(ds),generator,publisher,tracker,Clock.fixed(end,ZoneOffset.UTC));
    service.tick();verify(publisher,times(1)).publish(any());assertEquals(BehaviorSeverity.WARNING,service.evaluate().severity());
    var restarted=new BehaviorService(p,activity,capture,store,new SqliteBehaviorStateStore(ds),generator,publisher,tracker,Clock.fixed(end,ZoneOffset.UTC));
    restarted.tick();verify(publisher,times(1)).publish(any());verify(generator,times(1)).generate(any());
    assertEquals(fine,store.findBetween(START,end));assertEquals(raw,store.findRecordsBetween(START,end));
    assertTrue(restarted.status().contains("cooldownUntil="));
  }
  @Test void disabledCaptureDoesNotTriggerEvenWhenBehaviorIsEnabled() {
    var p=new BehaviorProperties();p.setEnabled(true);var store=mock(ActivityStore.class);var generator=mock(BehaviorMessageGenerator.class);
    var service=new BehaviorService(p,new ActivityProperties(),mock(ActivityCapture.class),store,mock(BehaviorStateStore.class),generator,
        mock(AgentMessagePublisher.class),mock(AgentActivityTracker.class),Clock.fixed(START,ZoneOffset.UTC));
    service.tick();verifyNoInteractions(store,generator);
  }
}
