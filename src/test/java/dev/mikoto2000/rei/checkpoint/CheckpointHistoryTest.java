package dev.mikoto2000.rei.checkpoint;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class CheckpointHistoryTest {
  @TempDir Path root;
  SQLiteDataSource source;
  CheckpointProperties settings;
  PersistentCheckpointRepository repo;
  @BeforeEach void setup() {
    source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("history.db"));
    settings=new CheckpointProperties();repo=new PersistentCheckpointRepository(source,settings);
  }
  PersistentCheckpoint initial(String task) {
    return PersistentCheckpoint.initial(task,"project","session","run-"+task,root,"request-"+task);
  }
  PersistentCheckpoint save(String task,int revisions,String status) {
    var state=initial(task);var fields=repo.fields(state);fields.put("status",status);state=repo.fields(fields);
    for(int i=0;i<revisions;i++)state=repo.save(state,i,task+"-"+(i+1));
    return state;
  }
  void sql(String query) throws Exception {
    try(var db=DriverManager.getConnection(source.getUrl());var statement=db.createStatement()){statement.executeUpdate(query);}
  }
  long revisionRows(String task) throws Exception {
    try(var db=DriverManager.getConnection(source.getUrl());var query=db.prepareStatement("SELECT COUNT(*) FROM checkpoint_revisions WHERE task=?")) {
      query.setString(1,task);try(var rows=query.executeQuery()){assertTrue(rows.next());return rows.getLong(1);}
    }
  }
  @Test void automaticallyRemovesOldestAcrossTasksOnlyUntilNewSaveFitsAndRetainsDeduplication() throws Exception {
    save("older",2,"FAILED");save("newer",2,"INTERRUPTED");
    // Same-second timestamps with and without fractional seconds must be ordered chronologically.
    sql("UPDATE checkpoint_revisions SET created='2026-10-12T00:00:00Z' WHERE task='older' AND revision=1");
    sql("UPDATE checkpoint_revisions SET created='2026-10-12T00:00:00.100Z' WHERE task='newer' AND revision=1");
    settings.setMaxRevisions(4);
    repo.save(initial("incoming"),0,"incoming-1");
    assertEquals(1,revisionRows("older"));assertEquals(2,revisionRows("newer"));
    assertEquals(2,repo.get("project","older").revision());
    assertEquals(4,repo.historyUsage().revisions());assertEquals(5,repo.historyUsage().eventKeys());
    var reopened=new PersistentCheckpointRepository(source,settings);
    assertEquals(2,reopened.save(initial("older"),0,"older-1").revision());
    assertEquals(4,reopened.historyUsage().revisions());
  }
  @Test void automaticallyMakesRoomForSnapshotBytes() throws Exception {
    var fields=repo.fields(initial("large"));fields.put("request","x".repeat(4000));var state=repo.fields(fields);
    repo.save(state,0,"large-1");repo.save(state,1,"large-2");
    long before=repo.historyUsage().snapshotBytes();
    settings.setMaxBytes(before+256);
    repo.save(initial("incoming"),0,"incoming");
    assertEquals(1,revisionRows("large"));
    assertEquals(2,repo.get("project","large").revision());
    assertTrue(repo.historyUsage().snapshotBytes()<=settings.getMaxBytes());
  }
  @Test void previewIsReadOnlyAndApplyPreservesLatestLeasedWaitingAndDamagedTasks() throws Exception {
    save("ordinary",3,"FAILED");save("live",3,"RUNNING");save("waiting",3,"WAITING_APPROVAL");save("damaged",3,"FAILED");
    assertTrue(repo.acquire("project","live","running"));
    sql("UPDATE checkpoint_revisions SET snapshot='broken' WHERE task='damaged' AND revision=3");
    var preview=repo.cleanupHistory(false);
    assertFalse(preview.applied());assertEquals(2,preview.removedRevisions());assertEquals(3,preview.protectedTasks());
    assertEquals(12,repo.historyUsage().revisions());assertEquals(10,preview.after().revisions());
    var applied=repo.cleanupHistory(true);
    assertTrue(applied.applied());assertEquals(preview.removedBytes(),applied.removedBytes());
    assertEquals(10,repo.historyUsage().revisions());assertEquals(12,repo.historyUsage().eventKeys());
    assertEquals(3,repo.get("project","ordinary").revision());
    assertEquals(3,revisionRows("live"));assertEquals(3,revisionRows("waiting"));assertEquals(3,revisionRows("damaged"));
    assertEquals(2,repo.get("project","damaged").revision());assertFalse(repo.diagnostics("project","damaged").isEmpty());
  }
  @Test void applyRechecksLeaseAcquiredAfterPreview() throws Exception {
    save("task",3,"FAILED");assertEquals(2,repo.cleanupHistory(false).removedRevisions());
    assertTrue(repo.acquire("project","task","resume"));
    assertEquals(0,repo.cleanupHistory(true).removedRevisions());assertEquals(3,revisionRows("task"));
  }
  @ParameterizedTest @ValueSource(strings={"WAITING_APPROVAL","QUEUED","RESUMING","DENY_QUEUED","UNKNOWN","EXPIRED"})
  void approvalContinuationsProtectHistoricalRunWithoutPageLimit(String status) throws Exception {
    var first=save("approval",2,"COMPLETED");var fields=repo.fields(first);fields.put("runId","new-run");
    repo.save(repo.fields(fields),2,"approval-3");
    sql("CREATE TABLE approval_continuations(approval TEXT PRIMARY KEY,run TEXT,state TEXT)");
    for(int i=0;i<300;i++)sql("INSERT INTO approval_continuations VALUES('unrelated-"+i+"','run-unrelated-"+i+"','QUEUED')");
    sql("INSERT INTO approval_continuations VALUES('protected','run-approval','"+status+"')");
    assertEquals(0,repo.cleanupHistory(true).removedRevisions());assertEquals(3,revisionRows("approval"));
  }
  @Test void insufficientSpaceRollsBackTentativeDeletionsAndReportsRealUsage() throws Exception {
    save("old",2,"FAILED");save("protected",2,"WAITING_APPROVAL");
    var before=repo.historyUsage();settings.setMaxRevisions(3);
    var error=assertThrows(CheckpointException.class,()->repo.save(initial("new"),0,"new"));
    assertEquals(CheckpointException.Code.CAPACITY,error.code());
    assertEquals(4,repo.historyUsage().revisions());assertEquals(before.snapshotBytes(),repo.historyUsage().snapshotBytes());
    assertEquals(2,revisionRows("old"));assertEquals(2,revisionRows("protected"));
    assertTrue(error.getMessage().contains("4"));assertTrue(error.getMessage().contains("rei.checkpoint.max-revisions"));
  }
  @Test void failedInsertRollsBackAutomaticCleanup() throws Exception {
    save("old",2,"FAILED");settings.setMaxRevisions(2);
    sql("CREATE TRIGGER fail_new BEFORE INSERT ON checkpoint_revisions WHEN NEW.task='new' BEGIN SELECT RAISE(ABORT,'injected'); END");
    assertThrows(RuntimeException.class,()->repo.save(initial("new"),0,"new"));
    assertEquals(2,revisionRows("old"));assertEquals(2,repo.historyUsage().eventKeys());
  }
  @Test void staleSaveNeverDeletesOtherTasksHistory() throws Exception {
    save("task",2,"FAILED");save("other",2,"FAILED");settings.setMaxRevisions(4);
    assertThrows(ConcurrentModificationException.class,()->repo.save(initial("task"),1,"stale"));
    assertEquals(4,repo.historyUsage().revisions());
  }
  @Test void cleanupAndConcurrentSaveRetainHeadAndMonotonicRevision() throws Exception {
    save("task",3,"INTERRUPTED");var barrier=new java.util.concurrent.CyclicBarrier(2);
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var cleanup=pool.submit(()->{barrier.await();return new PersistentCheckpointRepository(source,settings).cleanupHistory(true);});
      var save=pool.submit(()->{barrier.await();return repo.save(repo.get("project","task"),3,"concurrent");});
      cleanup.get();assertEquals(4,save.get().revision());
    }
    assertEquals(4,repo.get("project","task").revision());
    assertEquals(4,repo.historyUsage().eventKeys());assertTrue(repo.historyUsage().revisions()>=1);
  }
}
