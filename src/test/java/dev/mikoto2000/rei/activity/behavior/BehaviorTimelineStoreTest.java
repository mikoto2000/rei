package dev.mikoto2000.rei.activity.behavior;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorTimelineStoreTest {
  @TempDir java.nio.file.Path directory;
  @Test void eventsSurviveReopenAndRangeIsHalfOpenWithoutInventingOldDeliveries() throws Exception {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("events.db"));
    var store=new SqliteBehaviorStateStore(ds);var a=BehaviorEvaluatorTest.assess(3600,BehaviorEvaluatorTest.record(0,3600,"social"));
    store.save(BehaviorState.empty(),a);var start=a.evaluatedAt();
    assertTrue(store.findEventsBetween(start,start.plusSeconds(60)).isEmpty());
    var emitted=new BehaviorTimelineEvent("first",start,a.severity(),a.reason(),BehaviorTimelineEvent.Outcome.EMITTED,"DELIVERED",a.continuousEntertainmentSeconds(),a.windows());
    var suppressed=new BehaviorTimelineEvent("second",start.plusSeconds(60),a.severity(),a.reason(),BehaviorTimelineEvent.Outcome.SUPPRESSED,"COOLDOWN",a.continuousEntertainmentSeconds(),a.windows());
    store.appendEvent(suppressed);store.appendEvent(emitted);
    var reopened=new SqliteBehaviorStateStore(ds);
    assertEquals(List.of(emitted),reopened.findEventsBetween(start,start.plusSeconds(60)));
    assertEquals(List.of(emitted,suppressed),reopened.findEventsBetween(start,start.plusSeconds(61)));
    assertEquals(BehaviorState.empty(),reopened.load());
  }
  @Test void readingOldDatabaseDoesNotCreateHistoryOrModifySchema() throws Exception {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("old.db"));
    try(var c=ds.getConnection();var s=c.createStatement()){s.execute("CREATE TABLE activity_behavior_state(id INTEGER PRIMARY KEY,payload TEXT)");}
    assertTrue(new SqliteBehaviorStateStore(ds).findEventsBetween(Instant.EPOCH,Instant.EPOCH.plusSeconds(60)).isEmpty());
    try(var c=ds.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table'")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
  }
}
