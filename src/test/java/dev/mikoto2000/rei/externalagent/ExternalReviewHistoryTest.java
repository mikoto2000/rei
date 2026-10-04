package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import static org.junit.jupiter.api.Assertions.*;

class ExternalReviewHistoryTest {
  @TempDir Path root;
  ExternalReviewRepository repository() {var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("reviews.db"));return new ExternalReviewRepository(source,Clock.systemUTC());}
  AgentRunContext owner(){return new AgentRunContext("run","session",root,"project");}
  dev.mikoto2000.rei.core.stagnation.RunExecutionContext run(String prompt,String project) {
    String id=UUID.randomUUID().toString();
    var run=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(id,new dev.mikoto2000.rei.llm.OutputLimitRunBudget(2,10),
        new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(root,null),new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),e->{});
    run.setRunContext(new AgentRunContext(id,"session",root,project));run.setUserRequest(prompt);return run;
  }
  ExternalAgentDelegationService service(ExternalAgentExecutor executor,ExternalReviewRepository repository) {
    var service=new ExternalAgentDelegationService(executor,new dev.mikoto2000.rei.core.service.CommandCancellationService(),
        new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),e->{},Optional.empty());service.reviewHistory(repository);return service;
  }
  @Test void persistsBoundedRedactedResultWithoutRawPromptOrLogsAndKeepsOwnership() throws Exception {
    var repository=repository();repository.start(owner(),"review-1",root.toRealPath(),"note.txt",null);
    repository.finish("project","review-1",new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS,"api_key=secret-value",
        List.of(new ExternalAgentFinding(ExternalAgentFinding.Severity.high,"title","x".repeat(5000),"fix","note.txt")),List.of(),1,0,"private raw logs"));
    var restored=repository().get("project","review-1");
    assertFalse(restored.result().summary().contains("secret-value"));assertEquals("",restored.result().rawOutput());
    assertEquals(1024,restored.result().findings().getFirst().reason().length());
    assertEquals(ExternalAgentResult.Status.SUCCESS_WITH_WARNINGS,restored.result().status());assertFalse(restored.result().warnings().isEmpty());
    assertThrows(IllegalArgumentException.class,()->repository.get("other","review-1"));
    assertTrue(repository.list("other").isEmpty());
    assertThrows(IllegalArgumentException.class,()->repository.finish("project","review-1",restored.result()));
    assertFalse(Files.readString(root.resolve("reviews.db"),java.nio.charset.StandardCharsets.ISO_8859_1).contains("private raw logs"));
  }
  @Test void startedRecordSurvivesRestartAndCannotBeUsedAsCompletedParent() throws Exception {
    var repository=repository();repository.start(owner(),"pending",root.toRealPath(),null,null);
    assertEquals("STARTED",repository().get("project","pending").status());
    assertThrows(IllegalArgumentException.class,()->repository.start(owner(),"next",root.toRealPath(),null,"pending"));
  }
  @Test void explicitReReviewLinksPreviousResultRechecksTargetAndSharesOneRunBudget() throws Exception {
    Files.writeString(root.resolve("note.txt"),"before");var repository=repository();var requests=new ArrayList<ExternalAgentRequest>();
    var service=service((request,cancelled)->{requests.add(request);return new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS,"observed previous issue",List.of(),List.of(),1,0,"raw");},repository);
    var first=service.review(run("Codex にレビューして","project"),"review","note.txt",null);
    assertNotNull(first.reviewId());Files.writeString(root.resolve("note.txt"),"after");
    var rerun=run("Codex に再レビューして","project");var second=service.rereview(rerun,first.reviewId(),"check changes",null);
    assertTrue(second.success());assertEquals(first.reviewId(),repository.get("project",second.reviewId()).previousId());
    assertTrue(requests.getLast().context().contains("observed previous issue"));assertEquals(root.resolve("note.txt").toRealPath(),requests.getLast().target());
    assertEquals(ExternalAgentRequest.Action.REVIEW,requests.getLast().action());
    assertEquals(ExternalAgentResult.Status.REJECTED,service.review(rerun,"again",null,null).status());assertEquals(2,requests.size());
    assertEquals(ExternalAgentResult.Status.REJECTED,service.rereview(run("review again","project"),first.reviewId(),"Codex review",null).status());
    var other=run("Codex にレビューして","other");
    assertEquals(ExternalAgentResult.Status.REJECTED,service.rereview(other,first.reviewId(),"review",null).status());assertFalse(other.externalDelegationUsed());
  }
  @Test void persistenceFailureLeavesUnknownStartedOutcomeAndCannotTriggerAutomaticRetry() throws Exception {
    var repository=org.mockito.Mockito.spy(repository());var calls=new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doThrow(new IllegalStateException("private database error")).when(repository).finish(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
    var service=service((request,cancelled)->{calls.incrementAndGet();return new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS,"ok",List.of(),List.of(),1,0,"");},repository);
    var result=service.review(run("Codex にレビューして","project"),"review",null,null);
    assertEquals(ExternalAgentResult.Status.FAILED,result.status());assertFalse(result.summary().contains("private database"));
    assertEquals("STARTED",repository.get("project",result.reviewId()).status());
    assertEquals(ExternalAgentResult.Status.REJECTED,service.rereview(run("Codex にレビューして","project"),result.reviewId(),"review",null).status());assertEquals(1,calls.get());
  }
  @Test void cancelledResultIsSavedBeforeCancellationAndHistoryToolsEnforceProject() throws Exception {
    var repository=repository();var service=service((request,cancelled)->new ExternalAgentResult(ExternalAgentResult.Status.CANCELLED,"private output",List.of(),List.of(),1,null,"raw"),repository);
    assertThrows(java.util.concurrent.CancellationException.class,()->service.review(run("Codex にレビューして","project"),"review",null,null));
    var stored=repository.list("project").getFirst();assertEquals("CANCELLED",stored.status());assertFalse(stored.result().summary().contains("private output"));
    var tools=new ExternalAgentTools(service);tools.reviewHistory(repository);
    var context=new org.springframework.ai.chat.model.ToolContext(Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY,run("history","other")));
    assertTrue(tools.listCodexReviews(context).isEmpty());assertThrows(IllegalArgumentException.class,()->tools.getCodexReview(stored.id(),context));
  }
  @Test void missingOrRelocatedTargetRejectsReReviewBeforeConsumingBudget() throws Exception {
    Files.writeString(root.resolve("note.txt"),"note");var repository=repository();var calls=new java.util.concurrent.atomic.AtomicInteger();
    var service=service((request,cancelled)->{calls.incrementAndGet();return new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS,"ok",List.of(),List.of(),1,0,"");},repository);
    var first=service.review(run("Codex にレビューして","project"),"review","note.txt",null);
    Files.delete(root.resolve("note.txt"));var missing=run("Codex にレビューして","project");
    assertEquals(ExternalAgentResult.Status.REJECTED,service.rereview(missing,first.reviewId(),"review",null).status());assertFalse(missing.externalDelegationUsed());
    var moved=run("Codex にレビューして","project");moved.setRunContext(new AgentRunContext("moved","session",Files.createDirectory(root.resolve("other-root")),"project"));
    assertEquals(ExternalAgentResult.Status.REJECTED,service.rereview(moved,first.reviewId(),"review",null).status());assertFalse(moved.externalDelegationUsed());assertEquals(1,calls.get());
  }
  @Test void unavailablePersistencePreventsExternalExecution() {
    var repository=org.mockito.Mockito.mock(ExternalReviewRepository.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("private database error")).when(repository).start(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.isNull());
    var service=service((request,cancelled)->{fail("No external process may start");return null;},repository);
    var result=service.review(run("Codex にレビューして","project"),"review",null,null);
    assertEquals(ExternalAgentResult.Status.REJECTED,result.status());assertFalse(result.summary().contains("private database"));
  }
  @Test void historyToolSerializesSavedReviewAndKeepsRawLogsOut() throws Exception {
    var repository=repository();repository.start(owner(),"saved",root.toRealPath(),null,null);
    repository.finish("project","saved",new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS,"reviewed",List.of(),List.of(),1,0,"private logs"));
    var service=service((request,cancelled)->{fail("Read must not delegate");return null;},repository);
    var tools=new ExternalAgentTools(service);tools.reviewHistory(repository);
    var callback=Arrays.stream(org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
        .filter(c->c.getToolDefinition().name().equals("getCodexReview")).findFirst().orElseThrow();
    var context=new org.springframework.ai.chat.model.ToolContext(Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY,run("history","project")));
    String json=callback.call("{\"reviewId\":\"saved\"}",context);
    assertTrue(json.contains("reviewed"));assertTrue(json.contains("createdAt"));assertFalse(json.contains("private logs"));
  }
}
