package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.llm.LlmProperties;

@Tag("integration")
class GoalTokenBudgetTest {
  @TempDir Path dir;
  GoalRepository repository(long limit) {
    var properties=new LlmProperties();properties.getOutputLimit().setMaxTotalTokensPerGoal(limit);
    return new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("goals.db")),Clock.systemUTC(),properties);
  }
  @Test void reportsSurviveRestartAndResumeWithoutReplenishingLimit() {
    var goals=repository(10);
    var goal=goals.create(new AgentRunContext("source","session",dir,"project"),"Artifact","out.txt","a".repeat(64),3,10);
    var first=goals.claim("project",goal.id());var run=goals.beginAttempt(first);
    assertTrue(goals.reserveLlm(first));goals.recordTotalTokens(first,run,6);
    goals.recordAttempt(first,run,"UNVERIFIED","digest_mismatch");goals.stop(first,"PAUSED","pause");
    var reopened=repository(999);
    assertEquals(10,reopened.get("project",goal.id()).maxTotalTokens());
    assertEquals(6,reopened.get("project",goal.id()).totalTokens());
    var second=reopened.claim("project",goal.id());var next=reopened.beginAttempt(second);
    assertTrue(reopened.reserveLlm(second));reopened.recordTotalTokens(second,next,4);
    assertFalse(reopened.reserveLlm(second));assertEquals(0,reopened.remainingLlm(second));
    reopened.recordAttempt(second,next,"UNVERIFIED","digest_mismatch");reopened.stop(second,"BLOCKED","token_budget_exhausted");
    assertThrows(IllegalStateException.class,()->reopened.claim("project",goal.id()));
  }
  @Test void unknownOrUnreconciledUsageCannotBeResetByResume() {
    for(boolean reconcile:new boolean[]{false,true}) {
      var goals=repository(10);
      var goal=goals.create(new AgentRunContext("source","session"+reconcile,dir,"project"),"Artifact","out.txt","a".repeat(64),3,10);
      var claim=goals.claim("project",goal.id());var run=goals.beginAttempt(claim);
      assertTrue(goals.reserveLlm(claim));
      if(reconcile)goals.reconcile("project",goal.id(),run);
      else {
        assertThrows(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class,()->goals.recordTotalTokens(claim,run,null));
        goals.recordAttempt(claim,run,"FAILED","unknown_usage");goals.stop(claim,"BLOCKED","unknown_usage");
      }
      assertTrue(repository(0).get("project",goal.id()).tokenUsageUnknown());
      assertThrows(IllegalStateException.class,()->repository(0).claim("project",goal.id()));
    }
  }
  @Test void concurrentReportsAreAtomicAndCannotBeReplayedWithoutPendingReservation() throws Exception {
    var goals=repository(100);
    var goal=goals.create(new AgentRunContext("source","session",dir,"project"),"Artifact","out.txt","a".repeat(64),3,20);
    var claim=goals.claim("project",goal.id());var run=goals.beginAttempt(claim);
    var reopened=repository(100);
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      for(int round=0;round<10;round++) {
        assertTrue(goals.reserveLlm(claim));assertTrue(goals.reserveLlm(claim));
        var a=pool.submit(()->goals.recordTotalTokens(claim,run,4));
        var b=pool.submit(()->reopened.recordTotalTokens(claim,run,6));a.get();b.get();
      }
    }
    assertEquals(100,goals.get("project",goal.id()).totalTokens());
    assertEquals(0,goals.get("project",goal.id()).pendingLlmCalls());
    assertThrows(IllegalStateException.class,()->goals.recordTotalTokens(claim,run,4));
    assertEquals(100,goals.get("project",goal.id()).totalTokens());
  }
  @Test void migrationPreservesLegacyGoalAndDoesNotRetroactivelyEnableLimit() throws Exception {
    var source=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("goals.db"));
    try(var connection=source.getConnection();var statement=connection.createStatement()) {
      statement.execute("CREATE TABLE agent_goals(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,objective TEXT NOT NULL,file TEXT NOT NULL,digest TEXT NOT NULL,max_runs INTEGER NOT NULL,max_calls INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,used INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL,run TEXT,token TEXT,reason TEXT NOT NULL DEFAULT '')");
      statement.execute("INSERT INTO agent_goals(id,project,root,session,objective,file,digest,max_runs,max_calls,used,status) VALUES('legacy','project','.','session','Artifact','out.txt','"+"a".repeat(64)+"',3,10,2,'READY')");
    }
    var goals=repository(10);var legacy=goals.get("project","legacy");
    assertEquals(0,legacy.maxTotalTokens());assertEquals(2,legacy.llmCallsUsed());
    var claim=goals.claim("project","legacy");var run=goals.beginAttempt(claim);
    assertTrue(goals.reserveLlm(claim));assertDoesNotThrow(()->goals.recordTotalTokens(claim,run,null));
    assertFalse(goals.get("project","legacy").tokenUsageUnknown());
  }
  @Test void cancelledPendingUsageIsRetainedAndNegativeConfigIsRejected() {
    var goals=repository(10);
    var goal=goals.create(new AgentRunContext("source","session",dir,"project"),"Artifact","out.txt","a".repeat(64),3,10);
    var claim=goals.claim("project",goal.id());goals.beginAttempt(claim);assertTrue(goals.reserveLlm(claim));
    goals.cancel("project",goal.id());
    assertTrue(repository(0).get("project",goal.id()).tokenUsageUnknown());
    assertEquals(1,goals.get("project",goal.id()).pendingLlmCalls());
    assertThrows(IllegalArgumentException.class,()->new LlmProperties().getOutputLimit().setMaxTotalTokensPerGoal(-1));
  }
  @Test void unfinishedPrepaidReservationStopsBeforeAnotherAttempt() {
    var goals=repository(10);
    var goal=goals.create(new AgentRunContext("source","session",dir,"project"),"Artifact","out.txt","a".repeat(64),3,10);
    var dispatched=new java.util.concurrent.atomic.AtomicInteger();
    var loop=new GoalLoopService(goals,new FileGoalVerifier(),(claim,run,done)->{
      dispatched.incrementAndGet();assertTrue(goals.reserveLlm(claim));
      done.accept(new GoalLoopService.Outcome(dev.mikoto2000.rei.core.chat.ChatExecutionResult.success("No model response",false)));
    },new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null),new GoalEvents(event->{},Clock.systemUTC()));
    var stopped=loop.run("project",goal.id());
    assertEquals("BLOCKED",stopped.status());assertEquals("token_usage_unknown",stopped.reason());
    assertTrue(stopped.tokenUsageUnknown());assertEquals(1,stopped.attempts());assertEquals(1,dispatched.get());
    assertThrows(IllegalStateException.class,()->loop.run("project",goal.id()));
  }
  @Test void childReportsReachPersistentGoalOnceEvenWithIndependentRunLimit() {
    var goals=repository(5);
    var goal=goals.create(new AgentRunContext("source","session",dir,"project"),"Artifact","out.txt","a".repeat(64),3,10);
    var claim=goals.claim("project",goal.id());var runId=goals.beginAttempt(claim);
    var budget=new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0,10,goals.modelBudget(claim,runId),100);
    var run=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(runId,budget,null,null,null);
    run.consumeNextLlmCall();run.recordTotalTokens(3);
    var child=run.sharedLlmReservation();assertTrue(child.tryReserve());child.recordTotalTokens(2);
    assertEquals(5,budget.totalTokens());assertEquals(5,goals.get("project",goal.id()).totalTokens());
    assertEquals(2,goals.get("project",goal.id()).llmCallsUsed());assertFalse(child.tryReserve());
    var stopped=assertThrows(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class,run::checkModelTokenBudget);
    assertEquals("TOKEN_BUDGET_EXCEEDED",stopped.getMessage());
  }
}
