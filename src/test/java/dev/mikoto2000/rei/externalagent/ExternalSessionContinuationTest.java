package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("integration")
class ExternalSessionContinuationTest {
  static final String SESSION="01234567-89ab-cdef-0123-456789abcdef";
  @TempDir Path root;
  ExternalAgentResult success(String session) {
    return new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS,"reviewed",List.of(),List.of(),1,0,"raw",null,session);
  }
  @Test void optInCapturesOnlyUnambiguousNativeThreadAndPreservesDefaultEphemeralMode() {
    var properties=new CodexProperties();
    var executor=new CodexExternalAgentExecutor(properties,new ExternalAgentProcessRunner());
    var request=new ExternalAgentRequest(ExternalAgentRequest.Agent.CODEX,ExternalAgentRequest.Action.REVIEW,"review",root,null,"","run","id");
    String message="{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"{\\\"summary\\\":\\\"ok\\\",\\\"findings\\\":[],\\\"warnings\\\":[]}\"}}";
    String event="{\"type\":\"thread.started\",\"thread_id\":\""+SESSION+"\"}\n";
    var output=new ExternalAgentProcessRunner.Output(ExternalAgentResult.Status.SUCCESS,event+message,"",0,1,false);
    assertTrue(executor.command(request,root.resolve("schema")).contains("--ephemeral"));
    assertNull(executor.parse(output).externalSessionId());assertFalse(executor.supportsContinuation());
    properties.setPersistSessions(true);
    assertFalse(executor.command(request,root.resolve("schema")).contains("--ephemeral"));
    assertEquals(SESSION,executor.parse(output).forEvaluation().externalSessionId());
    assertNull(executor.parse(new ExternalAgentProcessRunner.Output(ExternalAgentResult.Status.SUCCESS,
        event+"{\"type\":\"thread.started\",\"thread_id\":\"ffffffff-ffff-ffff-ffff-ffffffffffff\"}\n"+message,"",0,1,false)).externalSessionId());
    assertNull(executor.parse(new ExternalAgentProcessRunner.Output(ExternalAgentResult.Status.SUCCESS,
        "{\"type\":\"thread.started\",\"thread_id\":\"--last\"}\n"+message,"",0,1,false)).externalSessionId());
    assertNull(executor.parse(new ExternalAgentProcessRunner.Output(ExternalAgentResult.Status.FAILED,event+message,"",1,1,false)).externalSessionId());
  }
  @Test void continuationUsesExactSavedUuidAndSameReadOnlyIsolationWithoutFallback() {
    var properties=new CodexProperties();properties.setCommand("test-codex");properties.setPersistSessions(true);
    var calls=new ArrayList<List<String>>();
    var runner=new ExternalAgentProcessRunner() {
      @Override public Output run(List<String> command,Path cwd,String input,Duration total,Duration idle,int limit,BooleanSupplier cancelled) {
        calls.add(command);
        if(command.contains("--help"))return new Output(ExternalAgentResult.Status.SUCCESS,"--ignore-user-config --ignore-rules --strict-config --ephemeral --output-schema --json","",0,1,false);
        assertEquals(root,cwd);assertTrue(command.contains("resume"));assertTrue(command.contains(SESSION));
        assertFalse(command.contains("--last"));assertFalse(command.contains("--all"));assertFalse(command.contains("--ephemeral"));
        assertTrue(command.contains("default_permissions=\"rei_review\""));assertTrue(command.contains("mcp_servers={}"));
        assertTrue(command.stream().anyMatch(s->s.contains("network={enabled=false}")));
        assertEquals("-",command.getLast());assertTrue(input.contains("Review request data"));
        return new Output(ExternalAgentResult.Status.FAILED,"","no such session",1,1,false);
      }
    };
    var request=new ExternalAgentRequest(ExternalAgentRequest.Agent.CODEX,ExternalAgentRequest.Action.REVIEW,"review",root,null,"","run","id",SESSION);
    assertEquals(ExternalAgentResult.Status.FAILED,new CodexExternalAgentExecutor(properties,runner).execute(request,()->false).status());
    assertEquals(3,calls.size());assertEquals(List.of("test-codex","exec","resume","--help"),calls.get(1));
    properties.setPersistSessions(false);calls.clear();
    assertEquals(ExternalAgentResult.Status.REJECTED,new CodexExternalAgentExecutor(properties,runner).execute(request,()->false).status());assertTrue(calls.isEmpty());
  }
  @Test void restartedHistoryContinuesOwnedSuccessfulReviewOnceAndKeepsUnknownOutcomeConsumed() throws Exception {
    var fixture=new ExternalReviewHistoryTest();fixture.root=root;
    var history=fixture.repository();history.start(fixture.owner(),"saved",root.toRealPath(),null,null);history.finish("project","saved",success(SESSION));
    assertEquals(SESSION,fixture.repository().get("project","saved").result().externalSessionId());
    var requests=new ArrayList<ExternalAgentRequest>();
    var executor=new ExternalAgentExecutor() {
      @Override public boolean supportsContinuation(){return true;}
      @Override public ExternalAgentResult execute(ExternalAgentRequest request,BooleanSupplier cancelled){requests.add(request);return success(SESSION);}
    };
    var service=fixture.service(executor,fixture.repository());
    var unauthorized=fixture.run("review again","project");
    assertEquals(ExternalAgentResult.Status.REJECTED,service.continueReview(unauthorized,"saved","Codex review",null).status());assertFalse(unauthorized.externalDelegationUsed());
    assertEquals(ExternalAgentResult.Status.REJECTED,service.continueReview(fixture.run("Codex にレビューして","other"),"saved","review",null).status());
    var run=fixture.run("Codex にレビューを継続して","project");var result=service.continueReview(run,"saved","check current files",null);
    assertTrue(result.success());assertEquals(SESSION,requests.getFirst().externalSessionId());assertEquals(SESSION,result.externalSessionId());
    assertEquals("saved",history.get("project",result.reviewId()).previousId());assertEquals("",result.rawOutput());
    assertEquals(ExternalAgentResult.Status.REJECTED,service.review(run,"again",null,null).status());
    assertEquals(ExternalAgentResult.Status.REJECTED,service.continueReview(fixture.run("Codex にレビューして","project"),"saved","again",null).status());
    assertEquals(1,requests.size());
    var next=service.continueReview(fixture.run("Codex にレビューして","project"),result.reviewId(),"next",null);assertTrue(next.success());assertEquals(2,requests.size());
    var legacy=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"status\":\"SUCCESS\",\"summary\":\"ok\",\"findings\":[],\"warnings\":[],\"duration\":0,\"exitCode\":0,\"rawOutput\":\"\",\"reviewId\":null}",ExternalAgentResult.class);
    assertNull(legacy.externalSessionId());
  }
  @Test void incompleteFailedAndEphemeralReviewsCannotBeContinued() throws Exception {
    var fixture=new ExternalReviewHistoryTest();fixture.root=root;var history=fixture.repository();
    history.start(fixture.owner(),"pending",root.toRealPath(),null,null);
    history.start(fixture.owner(),"legacy",root.toRealPath(),null,null);history.finish("project","legacy",success(null));
    history.start(fixture.owner(),"failed",root.toRealPath(),null,null);
    history.finish("project","failed",new ExternalAgentResult(ExternalAgentResult.Status.FAILED,"failed",List.of(),List.of(),1,1,"",null,SESSION));
    var executor=new ExternalAgentExecutor(){public boolean supportsContinuation(){return true;}public ExternalAgentResult execute(ExternalAgentRequest r,BooleanSupplier c){fail("Must not start");return null;}};
    var service=fixture.service(executor,history);
    for(String id:List.of("pending","legacy","failed")) {
      var run=fixture.run("Codex にレビューして","project");
      assertEquals(ExternalAgentResult.Status.REJECTED,service.continueReview(run,id,"review",null).status());assertFalse(run.externalDelegationUsed());
    }
  }
  @Test void atomicParentClaimSurvivesRestartAndFailedCompletionCannotReplay() throws Exception {
    var fixture=new ExternalReviewHistoryTest();fixture.root=root;var history=fixture.repository();
    history.start(fixture.owner(),"parent",root.toRealPath(),null,null);history.finish("project","parent",success(SESSION));
    var second=fixture.repository();var gate=new java.util.concurrent.CountDownLatch(1);
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var outcomes=new ArrayList<java.util.concurrent.Future<Boolean>>();
      for(int i=0;i<2;i++) {
        final int index=i;
        outcomes.add(pool.submit(()->{gate.await();try{
          (index==0?history:second).startContinuation(fixture.owner(),"child-"+index,root.toRealPath(),null,"parent");return true;
        }catch(RuntimeException rejected){return false;}}));
      }
      gate.countDown();int claimed=0;for(var outcome:outcomes)if(outcome.get(10,java.util.concurrent.TimeUnit.SECONDS))claimed++;
      assertEquals(1,claimed);
    }
    var restarted=fixture.repository();assertTrue(restarted.continuationAttempted("parent"));
    var child=restarted.list("project").stream().filter(r->r.id().startsWith("child-")).findFirst().orElseThrow();
    assertEquals("STARTED",child.status());
    assertThrows(IllegalArgumentException.class,()->restarted.startContinuation(fixture.owner(),"retry",root.toRealPath(),null,"parent"));
    restarted.finish("project",child.id(),new ExternalAgentResult(ExternalAgentResult.Status.FAILED,"failed",List.of(),List.of(),1,1,"",child.id(),SESSION));
    assertNull(restarted.get("project",child.id()).result().externalSessionId());
    assertThrows(IllegalArgumentException.class,()->restarted.startContinuation(fixture.owner(),"retry-failed",root.toRealPath(),null,child.id()));
  }
  @Test void unsupportedResumeAndWrongThreadFailClosedWithoutStartingFreshReview() {
    var properties=new CodexProperties();properties.setCommand("test-codex");properties.setPersistSessions(true);
    var calls=new java.util.concurrent.atomic.AtomicInteger();var unsupported=new java.util.concurrent.atomic.AtomicBoolean(true);
    var runner=new ExternalAgentProcessRunner(){
      @Override public Output run(List<String> command,Path cwd,String input,Duration total,Duration idle,int limit,BooleanSupplier cancelled) {
        calls.incrementAndGet();assertTrue(total.compareTo(properties.getTotalTimeout())<=0);
        if(command.contains("--help"))return new Output(ExternalAgentResult.Status.SUCCESS,
            unsupported.get() && command.contains("resume")?"old resume":"--ignore-user-config --ignore-rules --strict-config --ephemeral --output-schema --json","",0,1,false);
        assertTrue(command.contains("resume"));
        return new Output(ExternalAgentResult.Status.SUCCESS,"{\"type\":\"thread.started\",\"thread_id\":\"ffffffff-ffff-ffff-ffff-ffffffffffff\"}\n"
            +"{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"{\\\"summary\\\":\\\"ok\\\",\\\"findings\\\":[],\\\"warnings\\\":[]}\"}}","",0,1,false);
      }
    };
    var executor=new CodexExternalAgentExecutor(properties,runner);
    var request=new ExternalAgentRequest(ExternalAgentRequest.Agent.CODEX,ExternalAgentRequest.Action.REVIEW,"review",root,null,"","run","id",SESSION);
    assertEquals(ExternalAgentResult.Status.UNAVAILABLE,executor.execute(request,()->false).status());assertEquals(2,calls.get());
    unsupported.set(false);calls.set(0);var wrong=executor.execute(request,()->false);
    assertEquals(ExternalAgentResult.Status.FAILED,wrong.status());assertNull(wrong.externalSessionId());assertEquals(3,calls.get());
  }
}
