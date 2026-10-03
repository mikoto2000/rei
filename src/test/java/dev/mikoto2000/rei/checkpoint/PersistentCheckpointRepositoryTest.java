package dev.mikoto2000.rei.checkpoint;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class PersistentCheckpointRepositoryTest {
  @TempDir Path directory;
  PersistentCheckpointRepository open() {
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("checkpoint.db"));
    return new PersistentCheckpointRepository(source,new CheckpointProperties());
  }
  @Test void restartDeduplicationAndOptimisticUpdates() {
    var repo=open();var first=PersistentCheckpoint.initial("task","A","session","run",directory,"request");
    repo.save(first,0,"event");
    assertEquals(1,open().get("A","task").revision());
    assertTrue(open().list("B").isEmpty());
    repo.save(first,0,"event");
    assertThrows(ConcurrentModificationException.class,()->repo.save(first,0,"other"));
    assertEquals(1,repo.get("A","task").revision());
  }
  @Test void onlyOneOwnerCanAcquireAndReleaseDoesNotEraseHistory() {
    var repo=open();repo.save(PersistentCheckpoint.initial("task","A","session","run",directory,"request"),0,"event");
    assertTrue(repo.acquire("A","task","new-run"));
    assertFalse(open().acquire("A","task","other-run"));
    repo.release("A","task","other-run");
    assertFalse(repo.acquire("A","task","other-run"));
    repo.release("A","task","new-run");
    assertTrue(repo.acquire("A","task","other-run"));
    assertEquals(1,repo.get("A","task").revision());
  }
  @Test void failedWritePreservesPreviousRevision() throws Exception {
    var repo=open();var first=PersistentCheckpoint.initial("task","A","session","run",directory,"request");repo.save(first,0,"event");
    try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("checkpoint.db"));var s=c.createStatement()) {
      s.execute("CREATE TRIGGER inject_failure BEFORE INSERT ON checkpoint_revisions WHEN NEW.revision=2 BEGIN SELECT RAISE(ABORT,'injected'); END");
    }
    assertThrows(RuntimeException.class,()->repo.save(first,1,"next"));
    assertEquals(1,open().get("A","task").revision());
  }
  @Test void deadOwnerAndReusedPidCannotBlockRecovery() throws Exception {
    var repo=open();repo.save(PersistentCheckpoint.initial("task","A","session","run",directory,"request"),0,"start");
    assertTrue(repo.acquire("A","task","old"));
    try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("checkpoint.db"));var s=c.prepareStatement("UPDATE checkpoint_heads SET owner=?")) {
      s.setString(1,ProcessHandle.current().pid()+"@1970-01-01T00:00:00Z");s.executeUpdate();
    }
    assertFalse(repo.leased("A","task"));assertTrue(open().acquire("A","task","recovered"));
  }
  @Test void capacityFailureAndCorruptionKeepLatestValidRevision() throws Exception {
    var settings=new CheckpointProperties();settings.setMaxRevisions(1);
    var ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("work.db"));
    var repo=new PersistentCheckpointRepository(ds,settings);var first=PersistentCheckpoint.initial("task","A","session","run",directory,"request");
    repo.save(first,0,"start");assertThrows(IllegalStateException.class,()->repo.save(first,1,"next"));assertEquals(1,repo.get("A","task").revision());
    settings.setMaxRevisions(10);repo.save(first,1,"next");
    try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("work.db"));var s=c.createStatement()) {
      s.executeUpdate("UPDATE checkpoint_revisions SET snapshot='broken' WHERE revision=2");
    }
    assertEquals(1,repo.get("A","task").revision());assertEquals(List.of("Corrupt checkpoint"),repo.diagnostics("A","task"));
  }
  @Test void concurrentWritersHaveExactlyOneWinner() throws Exception {
    var repo=open();var first=PersistentCheckpoint.initial("task","A","session","run",directory,"request");repo.save(first,0,"start");
    var barrier=new java.util.concurrent.CyclicBarrier(2);var successes=new java.util.concurrent.atomic.AtomicInteger();
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var work=new ArrayList<java.util.concurrent.Future<?>>();
      for(int i=0;i<2;i++){String event="event-"+i;var writer=open();work.add(pool.submit(()->{
        try {barrier.await();writer.save(first,1,event);successes.incrementAndGet();}
        catch(ConcurrentModificationException expected){}catch(Exception error){throw new RuntimeException(error);}
      }));}
      for(var result:work)result.get();
    }
    assertEquals(1,successes.get());assertEquals(2,open().get("A","task").revision());
  }
  @Test void pruningDoesNotAllowAnOldEventToBeSavedAgainOrDiscardLatestValidState() throws Exception {
    var repo=open();var first=PersistentCheckpoint.initial("task","A","session","run",directory,"request");
    repo.save(first,0,"first");repo.save(first,1,"second");
    try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("checkpoint.db"));var s=c.createStatement()) {
      s.executeUpdate("UPDATE checkpoint_revisions SET created='1970-01-01T00:00:00Z' WHERE revision=1");
    }
    repo.save(first,2,"third");repo.save(first,0,"first");assertEquals(3,repo.get("A","task").revision());
    try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("checkpoint.db"));var s=c.createStatement()) {
      s.executeUpdate("UPDATE checkpoint_revisions SET snapshot='corrupt' WHERE revision=3");
      s.executeUpdate("UPDATE checkpoint_revisions SET created='1970-01-01T00:00:00Z' WHERE revision=2");
    }
    var other=PersistentCheckpoint.initial("other","A","session","other-run",directory,"other request");repo.save(other,0,"other-start");
    assertEquals(2,repo.get("A","task").revision());assertFalse(repo.diagnostics("A","task").isEmpty());
  }
}
