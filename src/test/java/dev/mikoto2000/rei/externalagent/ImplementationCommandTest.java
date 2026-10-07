package dev.mikoto2000.rei.externalagent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImplementationCommandTest {
  @Test void implementationNeedsAnExplicitTargetAndMergeNeedsAnExactReceiptHash() {
    var request=ExternalAgentCommandRequest.parse("/agent codex implement src/Foo.java");assertEquals("implement",request.action());assertEquals("src/Foo.java",request.target());
    assertEquals("claude",ExternalAgentCommandRequest.parse("/agent claude implement A.txt").agent());
    for(String invalid:new String[]{"/agent codex implement","/agent claude implement","/agent codex merge","/agent codex merge evil command"})assertThrows(IllegalArgumentException.class,()->ExternalAgentCommandRequest.parse(invalid));
    String id=java.util.UUID.randomUUID().toString(),hash="a".repeat(64);
    assertEquals(id+" "+hash,ExternalAgentCommandRequest.parse("/agent codex merge "+id+" "+hash).target());
    assertEquals(id,ExternalAgentCommandRequest.parse("/agent codex implementation "+id).target());
  }
  @Test void disabledOrUnrequestedImplementationNeverCallsProvider() {
    var executor=(ExternalAgentExecutor)(request,cancelled)->{fail("Must not call provider");return null;};
    var service=new ExternalAgentDelegationService(executor,new dev.mikoto2000.rei.core.service.CommandCancellationService(),new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),e->{},java.util.Optional.empty());
    assertEquals(ExternalAgentResult.Status.REJECTED,service.implement(null,"A.txt").status());
    assertEquals(ExternalAgentResult.Status.REJECTED,service.implement(null,"../outside").status());service.close();
  }
  @Test void implementationAndMergeDoNotAuthorizeAnotherReadOnlyDelegation() {
    for(String action:new String[]{"implement A.txt","implementation "+java.util.UUID.randomUUID(),"merge "+java.util.UUID.randomUUID()+" "+"a".repeat(64)}) {
      String input="/agent codex "+action;
      assertFalse(ExternalAgentAuthorization.explicitRequest(input));assertFalse(ExternalAgentAuthorization.explicitRequest(input,ExternalAgentRequest.Agent.CODEX));
    }
  }
}
