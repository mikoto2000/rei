package dev.mikoto2000.rei.workcontext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("integration")
class WorkContextRepositoryTest {
  @TempDir Path directory;
  WorkContextRepository open() {
    var ds=new SQLiteDataSource(); ds.setUrl("jdbc:sqlite:"+directory.resolve("work.db"));
    return new WorkContextRepository(ds);
  }
  WorkContext snapshot(String project,long revision) {
    return new WorkContext(project,revision,Instant.now(),Instant.now(),null,List.of(),Set.of("run-1"));
  }
  @Test void survivesRestartAndSeparatesProjectsWithRevisionHistory() {
    var repo=open(); repo.save(snapshot("A",1),0); repo.save(snapshot("A",2),1);
    assertEquals(2,open().current("A").orElseThrow().revision());
    assertTrue(open().current("B").isEmpty());
    assertEquals(List.of(2L,1L),open().history("A",20).stream().map(WorkContext::revision).toList());
  }
  @Test void staleWriterCannotLosePreviousState() {
    var repo=open(); repo.save(snapshot("A",1),0);
    assertThrows(java.util.ConcurrentModificationException.class,()->open().save(snapshot("A",1),0));
    assertEquals(1,repo.current("A").orElseThrow().revision());
    assertEquals(1,repo.history("A",20).size());
  }
  @Test void interruptedUpdateAndStorageFailureKeepHeadAndHistoryAtomic() throws Exception {
    var repo=open();repo.save(snapshot("A",1),0);
    Thread.currentThread().interrupt();
    try {assertThrows(java.util.concurrent.CancellationException.class,()->repo.save(snapshot("A",2),1));}
    finally {Thread.interrupted();}
    try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("work.db"));var statement=connection.createStatement()) {
      statement.execute("CREATE TRIGGER fail_work_context BEFORE INSERT ON work_context_revisions WHEN NEW.revision=2 BEGIN SELECT RAISE(ABORT,'simulated storage failure'); END");
    }
    assertThrows(RuntimeException.class,()->repo.save(snapshot("A",2),1));
    assertEquals(1,open().current("A").orElseThrow().revision());assertEquals(1,open().history("A",20).size());
  }
}
