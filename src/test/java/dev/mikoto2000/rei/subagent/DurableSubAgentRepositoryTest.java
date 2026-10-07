package dev.mikoto2000.rei.subagent;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import static org.junit.jupiter.api.Assertions.*;

class DurableSubAgentRepositoryTest {
  @TempDir Path directory;
  SQLiteDataSource source; AgentRunContext parent;
  @BeforeEach void setup()throws Exception {
    source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("children.db"));
    parent=new AgentRunContext("parent","session",Files.createDirectory(directory.resolve("project")),"project");
  }
  @Test void graphAdmissionRollsBackAllPreparedChildrenWhenCapacityIsExceeded() {
    var repository=new DurableSubAgentRepository(source,Clock.systemUTC(),2048);
    String graph=UUID.randomUUID().toString();
    assertThrows(IllegalArgumentException.class,()->repository.atomic(()->{
      repository.createPrepared(parent,graph,"GRAPH",null,"rei-dag","graph",null,4,0,"baseline");
      repository.createPrepared(parent,UUID.randomUUID().toString(),"CHILD",graph,"reviewer","inspect","x".repeat(2000),2,0,"baseline");return null;
    }));assertTrue(repository.list(parent,0,10).isEmpty());
  }
  @Test void completedResultAndConsumedBudgetSurviveRepositoryRestartAndRemainOwnerScoped()throws Exception {
    var repository=new DurableSubAgentRepository(source,Clock.systemUTC());
    var saved=repository.create(parent,"reviewer","inspect A",null,3,1000,"baseline");
    var started=repository.claim(parent,saved.id(),saved.revision(),"child-run");
    assertTrue(repository.reserve(started.id(),"child-run"));repository.tokens(started.id(),"child-run",100);
    repository.complete(started.id(),"child-run","COMPLETED","verified result");
    var restored=new DurableSubAgentRepository(source,Clock.systemUTC()).get(parent,saved.id());
    assertEquals("COMPLETED",restored.status());assertEquals(1,restored.consumedCalls());assertEquals(100,restored.consumedTokens());
    assertEquals("verified result",restored.result());assertEquals(64,restored.resultHash().length());
    var foreign=new AgentRunContext("other","other-session",parent.projectRoot(),parent.projectId());
    assertThrows(IllegalArgumentException.class,()->repository.get(foreign,saved.id()));
    assertThrows(IllegalArgumentException.class,()->repository.claim(parent,saved.id(),restored.revision(),"new-run"));
  }
  @Test void lostOwnerAndPendingToolRequireExplicitReconciliationWithoutResettingBudget()throws Exception {
    var repository=new DurableSubAgentRepository(source,Clock.systemUTC());
    var saved=repository.create(parent,"reviewer","inspect",null,2,1000,"baseline");
    repository.claim(parent,saved.id(),saved.revision(),"child-run");
    assertTrue(repository.reserve(saved.id(),"child-run"));repository.tokens(saved.id(),"child-run",100);
    repository.toolStarted(saved.id(),"child-run","operation","writeFile","args-hash");
    repository.ownerLost(saved.id(),"child-run");
    var restored=new DurableSubAgentRepository(source,Clock.systemUTC()).get(parent,saved.id());
    assertEquals("UNKNOWN",restored.status());assertEquals("UNKNOWN",restored.operations().getFirst().status());
    assertThrows(IllegalArgumentException.class,()->repository.claim(parent,saved.id(),restored.revision(),"next"));
    var reconciled=repository.reconcile(parent,saved.id(),restored.revision(),"operation","SUCCEEDED","human checked the file");
    var resumed=repository.claim(parent,saved.id(),reconciled.revision(),"next");
    assertEquals(1,resumed.consumedCalls());assertTrue(repository.reserve(saved.id(),"next"));assertFalse(repository.reserve(saved.id(),"next"));
    assertThrows(IllegalArgumentException.class,()->repository.toolStarted(saved.id(),"child-run","stale","readFile","hash"));
    repository.tokens(saved.id(),"next",null);assertFalse(repository.reserve(saved.id(),"next"));
    assertTrue(repository.get(parent,saved.id()).usageUnknown());
  }
  @Test void revisionsPreventDuplicateClaimsAndReadOnlyRunsCannotReconcile() {
    var repository=new DurableSubAgentRepository(source,Clock.systemUTC());var saved=repository.create(parent,"reviewer","inspect",null,3,0,"baseline");
    var other=new DurableSubAgentRepository(source,Clock.systemUTC());repository.claim(parent,saved.id(),0,"first");
    assertThrows(IllegalArgumentException.class,()->other.claim(parent,saved.id(),0,"second"));
    repository.toolStarted(saved.id(),"first","operation","readFile","hash");repository.ownerLost(saved.id(),"first");
    var unknown=repository.get(parent,saved.id());var reader=new AgentRunContext("read","session",parent.projectRoot(),parent.projectId(),AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.READ_ONLY);
    assertEquals(unknown,repository.get(reader,saved.id()));
    assertThrows(IllegalArgumentException.class,()->repository.reconcile(reader,saved.id(),unknown.revision(),"operation","FAILED","human checked"));
  }
  @Test void startupLossDuringAnUnreportedModelCallBlocksTokenLimitedResume()throws Exception {
    var repository=new DurableSubAgentRepository(source,Clock.systemUTC());var saved=repository.create(parent,"reviewer","inspect",null,3,1000,"baseline");
    repository.claim(parent,saved.id(),0,"lost");assertTrue(repository.reserve(saved.id(),"lost"));
    var db=org.springframework.jdbc.core.simple.JdbcClient.create(source);String encoded=db.sql("SELECT snapshot FROM subagent_checkpoints WHERE id=?").param(saved.id()).query(String.class).single();
    var json=new com.fasterxml.jackson.databind.ObjectMapper();var document=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(encoded);document.put("ownerPid",-1);
    db.sql("UPDATE subagent_checkpoints SET snapshot=? WHERE id=?").params(json.writeValueAsString(document),saved.id()).update();
    var restored=new DurableSubAgentRepository(source,Clock.systemUTC()).get(parent,saved.id());
    assertEquals("UNKNOWN",restored.status());assertTrue(restored.usageUnknown());
    assertThrows(IllegalArgumentException.class,()->repository.claim(parent,saved.id(),restored.revision(),"retry"));
  }
  @Test void storageQuotaAppliesToNewTasksAndGrowingResultsWithoutLosingThePreviousCheckpoint() {
    var repository=new DurableSubAgentRepository(source,Clock.systemUTC(),4096);
    var saved=repository.create(parent,"reviewer","a".repeat(2000),null,3,0,"baseline");
    assertThrows(IllegalArgumentException.class,()->repository.create(parent,"reviewer","b".repeat(2000),null,3,0,"baseline"));
    var started=repository.claim(parent,saved.id(),0,"run");
    assertThrows(IllegalArgumentException.class,()->repository.complete(saved.id(),"run","COMPLETED","c".repeat(4000)));
    assertEquals(started,repository.get(parent,saved.id()));
  }
  @Test void zeroUsageCannotMakeATokenLimitedReservationKnown() {
    var repository=new DurableSubAgentRepository(source,Clock.systemUTC());var saved=repository.create(parent,"reviewer","inspect",null,3,1000,"baseline");
    repository.claim(parent,saved.id(),0,"run");assertTrue(repository.reserve(saved.id(),"run"));repository.tokens(saved.id(),"run",0);
    assertTrue(repository.get(parent,saved.id()).usageUnknown());assertFalse(repository.reserve(saved.id(),"run"));
  }
  @Test void parallelReservationsRemainPendingUntilEveryUsageReportArrives() {
    var repository=new DurableSubAgentRepository(source,Clock.systemUTC());var saved=repository.create(parent,"reviewer","inspect",null,4,1000,"baseline");
    repository.claim(parent,saved.id(),0,"run");assertTrue(repository.reserve(saved.id(),"run"));assertTrue(repository.reserve(saved.id(),"run"));
    repository.tokens(saved.id(),"run",100);repository.ownerLost(saved.id(),"run");
    var unknown=repository.get(parent,saved.id());assertEquals(2,unknown.consumedCalls());assertTrue(unknown.usageUnknown());
    assertThrows(IllegalArgumentException.class,()->repository.claim(parent,saved.id(),unknown.revision(),"retry"));
  }
}
