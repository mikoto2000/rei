package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ImplementationDelegationIntegrationTest {
  @TempDir Path temporary;
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(ExternalAgentRequest.Agent.class)
  void explicitCommandSharesOneDelegationAndParentModelBudgetWithRealIsolatedVerification(ExternalAgentRequest.Agent agent)throws Exception {
    String provider=agent.name().toLowerCase(Locale.ROOT);
    Path root=Files.createDirectory(temporary.resolve("root"));
    for(var args:List.of(List.of("init","--quiet"),List.of("config","core.autocrlf","false")))git(root,args);
    Files.writeString(root.resolve("A.txt"),"before\n");git(root,List.of("add","A.txt"));git(root,List.of("commit","--quiet","--no-gpg-sign","-m","fixture"));
    var calls=new AtomicInteger();
    var executor=new ExternalAgentExecutor(){public ExternalAgentResult execute(ExternalAgentRequest request,java.util.function.BooleanSupplier cancelled){fail("Parent budget is mandatory");return null;}
      public ExternalAgentResult execute(ExternalAgentRequest request,java.util.function.BooleanSupplier cancelled,dev.mikoto2000.rei.llm.ModelCallBudget budget){
        assertNotEquals(root,request.projectRoot());assertTrue(request.projectRoot().startsWith(temporary.resolve("private")));assertEquals(ExternalAgentRequest.Action.IMPLEMENT,request.action());
        assertEquals(agent,request.agent());
        budget.run();calls.incrementAndGet();budget.recordTotalTokens(5);String hash=ImplementationProposal.sha256("before\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertTrue(request.context().contains(hash));
        return new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS,"proposal",List.of(),List.of(),0,0,"",null,null,null,null,new ImplementationProposal(List.of(new ImplementationProposal.Edit("A.txt",hash,"after\n"))));
      }};
    var service=new ExternalAgentDelegationService(executor,new dev.mikoto2000.rei.core.service.CommandCancellationService(),new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),e->{},Optional.empty());
    service.implementations(new IsolatedImplementationService(temporary.resolve("private"),new ExternalAgentProcessRunner(),cancelled->new dev.mikoto2000.rei.core.SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService(),cancelled)));
    var properties=new CodexProperties();properties.setImplementationEnabled(true);properties.setImplementationTestCommand(System.getProperty("os.name").startsWith("Windows")?"exit 0":"true");service.modelBudgetProperties(properties);
    var claude=new ClaudeCodeProperties();claude.setEnabled(true);claude.setImplementationEnabled(true);claude.setImplementationTestCommand(properties.getImplementationTestCommand());service.claudeProperties(claude);
    var budget=new dev.mikoto2000.rei.llm.OutputLimitRunBudget(1,10);
    var run=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("run",budget,new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(root,null),new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),e->{});
    run.setRunContext(new dev.mikoto2000.rei.core.chat.AgentRunContext("run","session",root,"project"));run.setUserRequest("review this file");
    assertEquals(ExternalAgentResult.Status.REJECTED,service.implement(run,agent,"A.txt").status());assertEquals(0,calls.get());assertFalse(run.externalDelegationUsed());
    run.setUserRequest("/agent "+provider+" implement "+root.resolve("A.txt"));
    var result=service.implement(run,agent,root.resolve("A.txt").toString());assertTrue(result.success(),result.summary());assertEquals(1,calls.get());assertTrue(run.externalDelegationUsed());
    var receipt=service.implementation(run,result.reviewId());assertEquals("READY_FOR_APPROVAL",receipt.status());assertEquals(provider,receipt.provider());assertEquals("before\n",Files.readString(root.resolve("A.txt")));
    assertEquals(ExternalAgentResult.Status.REJECTED,service.implement(run,agent,root.resolve("A.txt").toString()).status());assertEquals(1,calls.get());
    var merger=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("merge",new dev.mikoto2000.rei.llm.OutputLimitRunBudget(1,10),null,null,null);merger.setRunContext(new dev.mikoto2000.rei.core.chat.AgentRunContext("merge","session",root,"project"));
    merger.setUserRequest("/agent "+(agent==ExternalAgentRequest.Agent.CODEX?"claude":"codex")+" merge "+receipt.id()+" "+receipt.patchHash());assertEquals(ExternalAgentResult.Status.REJECTED,service.mergeImplementation(merger,receipt.id(),receipt.patchHash()).status());
    merger.setUserRequest("/agent "+provider+" merge "+receipt.id()+" "+receipt.patchHash());assertTrue(service.mergeImplementation(merger,receipt.id(),receipt.patchHash()).success());assertEquals("after\n",Files.readString(root.resolve("A.txt")));service.close();
  }
  void git(Path root,List<String> args)throws Exception{var cmd=new ArrayList<String>(List.of("git","-c","core.hooksPath=","-c","user.name=Fixture","-c","user.email=fixture@example.invalid"));cmd.addAll(args);var p=new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();assertTrue(p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,p.exitValue(),new String(p.getInputStream().readAllBytes()));}
}
