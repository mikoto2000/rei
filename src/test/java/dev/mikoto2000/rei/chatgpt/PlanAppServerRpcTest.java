package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class PlanAppServerRpcTest {
  static final ObjectMapper JSON=new ObjectMapper();
  static final String INIT="{\"id\":1,\"result\":{\"userAgent\":\"rei/0.154.0 (Linux)\",\"platformOs\":\"linux\"}}";
  static final String PROFILE="{\"id\":2,\"result\":{\"data\":[{\"id\":\"rei-read\",\"allowed\":true}],\"nextCursor\":null}}";
  static final String THREAD="{\"id\":3,\"result\":{\"thread\":{\"id\":\"thread-1\"},\"cwd\":\"/isolated/workspace\",\"modelProvider\":\"rei_plan\",\"model\":\"model\",\"approvalPolicy\":\"untrusted\",\"approvalsReviewer\":\"user\",\"sandbox\":{\"type\":\"readOnly\",\"networkAccess\":false},\"activePermissionProfile\":{\"id\":\"rei-read\"},\"instructionSources\":[]}}";
  static final String TURN="{\"id\":4,\"result\":{\"turn\":{\"id\":\"turn-1\",\"status\":\"inProgress\"}}}";
  static final String USAGE="{\"method\":\"thread/tokenUsage/updated\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"tokenUsage\":{\"total\":{\"totalTokens\":17},\"last\":{\"totalTokens\":17}}}}";
  static String done(String status){return "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\",\"status\":\""+status+"\",\"items\":[{\"type\":\"agentMessage\",\"id\":\"message-1\",\"text\":\"answer\",\"phase\":\"final_answer\"}]}}}";}
  static final class Fake implements PlanAppServerRpc.Session {
    final Queue<String> incoming=new LinkedList<>(); final List<JsonNode> sent=new ArrayList<>(); boolean closed;
    Fake(String... tail){incoming.addAll(List.of(INIT,PROFILE,THREAD,TURN));incoming.addAll(List.of(tail));}
    public void send(String line){try{sent.add(JSON.readTree(line));}catch(Exception e){throw new RuntimeException(e);}}
    public String poll(Duration timeout){return incoming.poll();}
    public void close(){closed=true;}
  }
  @Test void pinnedWindowsHandshakeUsesSameTerminalAndBudgetProtocol() {
    var s=new Fake(USAGE,done("completed"));var frames=new ArrayList<>(s.incoming);frames.set(0,INIT.replace("0.154.0","0.162.1").replace("linux","windows"));s.incoming.clear();s.incoming.addAll(frames);
    var result=new PlanAppServerRpc("0.162.1","windows").execute(s,"/isolated/workspace","model","instructions",List.of(new PlanClient.Input("user","task")),Duration.ofSeconds(1),4096,()->false,()->{},path->"snapshot");
    assertEquals(PlanSseAssembler.State.COMPLETED,result.result().state());assertEquals(17,result.result().totalTokens());assertTrue(s.closed);
  }
  @Test void windowsAdapterRejectsLinuxOrWrongVersionBeforeTurn() {
    var s=new Fake();assertEquals(ChatGptException.Code.PROTOCOL,assertThrows(ChatGptException.class,()->new PlanAppServerRpc("0.162.1","windows").execute(s,"cwd","model","instructions",List.of(),Duration.ofSeconds(1),4096,()->false,()->{},path->"")).code());
    assertTrue(s.closed);assertEquals(1,s.sent.size());
  }
  PlanAppServerRpc.Outcome run(Fake s,BooleanSupplier stop,java.util.function.Function<String,String> read){
    return new PlanAppServerRpc().execute(s,"/isolated/workspace","model","instructions",List.of(new PlanClient.Input("user","task")),Duration.ofSeconds(1),4096,stop,()->{},read);
  }
  @Test void terminalCompletionAndUsageAreRequiredAndProcessCloses(){
    var s=new Fake(USAGE,done("completed"));var o=run(s,()->false,p->"source");
    assertEquals(PlanSseAssembler.State.COMPLETED,o.result().state());assertEquals("answer",o.result().text());assertEquals(17,o.result().totalTokens());assertTrue(s.closed);
    assertEquals("thread-1",o.trace().threadId());assertEquals("turn-1",o.trace().turnId());
    assertTrue(s.sent.get(0).path("params").path("capabilities").path("experimentalApi").asBoolean());
    assertEquals("rei-read",s.sent.get(3).path("params").path("permissions").asText());
  }
  @Test void eofAfterDispatchNeverBecomesSuccess(){var o=run(new Fake(),()->false,p->"");assertEquals(PlanSseAssembler.State.OUTCOME_UNKNOWN,o.result().state());assertEquals("",o.result().text());}
  @Test void unsupportedVersionFailsBeforeThreadOrDispatch(){var s=new Fake();s.incoming.clear();s.incoming.add(INIT.replace("0.154.0","0.155.0"));assertEquals(ChatGptException.Code.PROTOCOL,assertThrows(ChatGptException.class,()->run(s,()->false,p->"")).code());assertEquals(1,s.sent.size());assertTrue(s.closed);}
  @Test void fileChangeApprovalIsDeniedAfterConfirmingStop(){var s=new Fake("{\"id\":90,\"method\":\"item/fileChange/requestApproval\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\"}}",USAGE,done("interrupted"));var o=run(s,()->false,p->"");assertEquals(1,o.trace().deniedRequests());assertEquals("decline",s.sent.stream().filter(n->n.path("id").asInt()==90).findFirst().orElseThrow().path("result").path("decision").asText());assertEquals(PlanSseAssembler.State.FAILED,o.result().state());}
  @Test void onlyBoundedGuardedSnapshotToolIsServed(){var s=new Fake("{\"id\":91,\"method\":\"item/tool/call\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"tool\":\"rei_read_source\",\"callId\":\"call-1\",\"arguments\":{\"path\":\"docs/source.md\"}}}",USAGE,done("completed"));var paths=new ArrayList<String>();var o=run(s,()->false,p->{paths.add(p);return "approved snapshot";});assertEquals(List.of("docs/source.md"),paths);assertEquals(1,o.trace().sourceReads());assertTrue(s.sent.stream().filter(n->n.path("id").asInt()==91).findFirst().orElseThrow().path("result").path("success").asBoolean());}
  @Test void mismatchedProjectThreadNeverReachesReader(){var s=new Fake("{\"id\":91,\"method\":\"item/tool/call\",\"params\":{\"threadId\":\"other\",\"turnId\":\"turn-1\",\"tool\":\"rei_read_source\",\"arguments\":{\"path\":\"docs/source.md\"}}}");var o=run(s,()->false,p->{fail("Cross-thread read");return "";});assertEquals(PlanSseAssembler.State.OUTCOME_UNKNOWN,o.result().state());}
  @Test void malformedRpcAndErrorsDoNotExposeRawValues(){var s=new Fake("not-json secret-value");var o=run(s,()->false,p->"");assertEquals(PlanSseAssembler.State.OUTCOME_UNKNOWN,o.result().state());assertFalse(o.result().toString().contains("secret-value"));}
  @Test void failedTurnRetainsUsageButNotUnvalidatedText(){var o=run(new Fake(USAGE,done("failed")),()->false,p->"");assertEquals(PlanSseAssembler.State.FAILED,o.result().state());assertEquals(17,o.result().totalTokens());assertEquals("",o.result().text());}
  @Test void cancellationBeforeDispatchDoesNotStartThread(){var s=new Fake();assertEquals(ChatGptException.Code.CANCELLED,assertThrows(ChatGptException.class,()->run(s,()->true,p->"")).code());assertTrue(s.closed);assertTrue(s.sent.isEmpty());}
  @Test void rpcErrorBeforeTurnDoesNotDispatchOrLeakRemoteMessage(){var s=new Fake();s.incoming.clear();s.incoming.add(INIT);s.incoming.add("{\"id\":2,\"error\":{\"code\":-32603,\"message\":\"secret-value\"}}");var error=assertThrows(ChatGptException.class,()->run(s,()->false,p->""));assertEquals(ChatGptException.Code.PROTOCOL,error.code());assertFalse(error.getMessage().contains("secret-value"));assertTrue(s.closed);}
  @Test void streamDisconnectIsUnknownEvenWhenNativeTurnReportsFailed(){var terminal=done("failed").replace("\"items\":", "\"error\":{\"codexErrorInfo\":{\"responseStreamDisconnected\":{\"httpStatusCode\":null}}},\"items\":");var result=run(new Fake(USAGE,terminal),()->false,p->"").result();assertEquals(PlanSseAssembler.State.OUTCOME_UNKNOWN,result.state());assertNull(result.totalTokens());}
  @Test void quotaAndUnauthorizedAreClassifiedWithoutRetry(){for(var code:List.of("usageLimitExceeded","unauthorized")){var terminal=done("failed").replace("\"items\":", "\"error\":{\"codexErrorInfo\":\""+code+"\"},\"items\":");var s=new Fake(terminal);var result=run(s,()->false,p->"").result();assertEquals(code.equals("unauthorized")?ChatGptException.Code.AUTH_REQUIRED:ChatGptException.Code.USAGE_LIMIT,result.error());assertEquals(1,s.sent.stream().filter(n->n.path("method").asText().equals("turn/start")).count());}}
  @Test void cancellationAfterTurnRequestsInterruptAndRequiresConfirmation(){var s=new Fake(done("interrupted"));var o=run(s,()->s.incoming.size()==1,p->"");assertEquals(PlanSseAssembler.State.FAILED,o.result().state());assertEquals(ChatGptException.Code.CANCELLED,o.result().error());assertTrue(o.trace().interruptRequested());assertTrue(s.sent.stream().anyMatch(n->n.path("method").asText().equals("turn/interrupt")));assertTrue(s.closed);}
  @Test void refusedSnapshotCannotBecomeSuccessfulReview(){var s=new Fake("{\"id\":91,\"method\":\"item/tool/call\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"tool\":\"rei_read_source\",\"callId\":\"call-1\",\"arguments\":{\"path\":\"outside\"}}}",done("interrupted"));var o=run(s,()->false,p->{throw ChatGptException.of(ChatGptException.Code.PERMISSION_DENIED);});assertEquals(PlanSseAssembler.State.FAILED,o.result().state());assertEquals(ChatGptException.Code.PERMISSION_DENIED,o.result().error());assertFalse(s.sent.stream().filter(n->n.path("id").asInt()==91).findFirst().orElseThrow().path("result").path("success").asBoolean());}
}
