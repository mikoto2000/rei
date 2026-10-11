package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class EpisodeRepositoryTest {
  @TempDir Path dir;
  EpisodeRepository repository;
  @BeforeEach void setup() { repository=new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("memory.db"))); }
  Episode episode(String revision, Episode.Status status) {
    return new Episode("e1","p1","s1",revision,"2026-10-11T00:00:00Z",status,"CLI design","Separate process",
        List.of(new Episode.Claim("reason","Isolation",Episode.Evidence.USER_EXPLICIT,"r1","user"),
            new Episode.Claim("alternative","In process",Episode.Evidence.MODEL_PROPOSAL,"r1","assistant"),
            new Episode.Claim("unknown","Performance",Episode.Evidence.UNVERIFIED,"r1","assistant")),.8);
  }
  @Test void persistsClaimsAndImmutableRevisionsAcrossRestart() {
    repository.saveBatch("s1",0,1,List.of(episode("v1",Episode.Status.IN_PROGRESS)));
    repository.saveBatch("s1",1,2,List.of(episode("v2",Episode.Status.WITHDRAWN)));
    var restarted=new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("memory.db")));
    assertEquals(2,restarted.revisions("e1","p1").size());
    assertEquals(Episode.Evidence.MODEL_PROPOSAL,restarted.find("e1","p1").orElseThrow().claims().get(1).evidence());
    assertEquals(Episode.Status.WITHDRAWN,restarted.find("e1","p1").orElseThrow().status());
    assertTrue(restarted.find("e1","p2").isEmpty());
  }
  @Test void checkpointCompareAndSetIsAtomicAndRetryDoesNotDuplicate() {
    repository.saveBatch("s1",0,1,List.of(episode("v1",Episode.Status.COMPLETED)));
    assertThrows(IllegalStateException.class,()->repository.saveBatch("s1",0,1,List.of(episode("v1",Episode.Status.COMPLETED))));
    assertEquals(1,repository.revisions("e1","p1").size());
    assertEquals(1,repository.checkpoint("s1"));
  }
  @Test void lexicalSearchIsBoundedAndProjectScoped() {
    repository.saveBatch("s1",0,1,List.of(episode("v1",Episode.Status.COMPLETED)));
    assertEquals(1,repository.search("Isolation","p1",5).size());
    assertTrue(repository.search("Isolation","p2",5).isEmpty());
    assertTrue(repository.search("\" OR *", "p1",5).isEmpty());
    assertThrows(IllegalArgumentException.class,()->repository.search("CLI","p1",101));
  }
  @Test void emptyBatchAdvancesCheckpoint() {
    repository.saveBatch("s1",0,3,List.of());
    assertEquals(3,repository.checkpoint("s1"));
  }
  @Test void failureRollsBackEpisodeAndCheckpointTogether() {
    var valid=episode("v1",Episode.Status.COMPLETED);
    var foreign=new Episode("e2","p1","other","v2",valid.occurredAt(),valid.status(),valid.title(),valid.summary(),valid.claims(),.8);
    assertThrows(IllegalArgumentException.class,()->repository.saveBatch("s1",0,2,List.of(valid,foreign)));
    assertEquals(0,repository.checkpoint("s1"));assertTrue(repository.find("e1","p1").isEmpty());
    repository.saveBatch("s1",0,1,List.of(valid));assertEquals(1,repository.checkpoint("s1"));
  }
  @Test void sourceLeasePreventsConcurrentModelProcessingAndIsReleased() {
    assertTrue(repository.acquire("s1","worker1"));
    assertFalse(repository.acquire("s1","worker2"));
    repository.release("s1","worker2");assertFalse(repository.acquire("s1","worker2"));
    repository.release("s1","worker1");assertTrue(repository.acquire("s1","worker2"));
  }
  @Test void decisionLinksRequireExistingSameProjectMemory() {
    var db=org.springframework.jdbc.core.simple.JdbcClient.create(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("memory.db")));
    db.sql("CREATE TABLE memories(id TEXT PRIMARY KEY,project_id TEXT,type TEXT,scope TEXT)").update();
    db.sql("INSERT INTO memories VALUES('m','p1','DECISION','PROJECT')").update();
    repository.saveBatch("s1",0,1,List.of(episode("v1",Episode.Status.COMPLETED)));
    repository.linkMemory("p1","s1",List.of("r1"),"m");
    assertEquals("m",repository.relations("e1","p1").getFirst().get("targetId"));
    assertThrows(IllegalArgumentException.class,()->repository.linkMemory("p2","s1",List.of("r1"),"m"));
    assertThrows(IllegalArgumentException.class,()->repository.linkMemory("p1","s1",List.of("r1"),"invented"));
  }
  @Test void linksActualWorkContextItemsByTheirSourceRuns() {
    repository.saveBatch("s1",0,1,List.of(episode("v1",Episode.Status.COMPLETED)));
    var evidence=new dev.mikoto2000.rei.workcontext.WorkContext.Evidence("source",dev.mikoto2000.rei.workcontext.WorkContext.Origin.USER,"s1","r1","r1",null,null,null,java.time.Instant.EPOCH,java.time.Instant.EPOCH,"CLI");
    var item=new dev.mikoto2000.rei.workcontext.WorkContext.Item("w1",dev.mikoto2000.rei.workcontext.WorkContext.Kind.CURRENT_WORK,"CLI","",dev.mikoto2000.rei.workcontext.WorkContext.Status.OPEN,List.of(evidence),java.time.Instant.EPOCH,java.time.Instant.EPOCH,false,null);
    var context=new dev.mikoto2000.rei.workcontext.WorkContext("p1",1,java.time.Instant.EPOCH,java.time.Instant.EPOCH,null,List.of(item),java.util.Set.of("r1"));
    repository.linkWorkContext(context,"s1");
    assertEquals("w1",repository.relations("e1","p1").getFirst().get("targetId"));
    assertEquals("WORK_CONTEXT",repository.relations("e1","p1").getFirst().get("kind"));
    assertTrue(repository.relations("e1","p2").isEmpty());
  }
}
