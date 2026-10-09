package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import static org.junit.jupiter.api.Assertions.*;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.stagnation.*;

@Tag("integration")
class SpecificationDelegationIntegrationTest {
  @TempDir Path temporary;
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={0,1,2,3,4,5})
  void approvedRequirementsUseExistingEngineAndManifestRejectsOutsideScope(int mode) throws Exception {
    boolean outside=mode==1;var execution=new java.util.concurrent.atomic.AtomicReference<RunExecutionContext>();
    Path root=Files.createDirectory(temporary.resolve("root"));git(root,"init","--quiet");
    Files.writeString(root.resolve("A.txt"),"before\n");Files.writeString(root.resolve("B.txt"),"other\n");git(root,"add",".");git(root,"commit","--quiet","--no-gpg-sign","-m","fixture");
    var calls=new AtomicInteger();
    var executor=new ExternalAgentExecutor(){public ExternalAgentResult execute(ExternalAgentRequest request,java.util.function.BooleanSupplier cancelled){fail("Parent budget required");return null;}
      public ExternalAgentResult execute(ExternalAgentRequest request,java.util.function.BooleanSupplier cancelled,dev.mikoto2000.rei.llm.ModelCallBudget budget){
        budget.run();calls.incrementAndGet();budget.recordTotalTokens(5);
        assertTrue(request.task().contains("Replace before with after"));assertTrue(request.task().contains("No new files"));
        assertTrue(request.context().contains("Greeting says after"));assertTrue(request.context().contains("allowedPaths"));assertEquals(ExternalAgentRequest.Action.IMPLEMENT,request.action());
        if(mode==2){execution.get().cancel();assertTrue(cancelled.getAsBoolean());throw new java.util.concurrent.CancellationException();}
        if(mode==3)return new ExternalAgentResult(ExternalAgentResult.Status.TOTAL_TIMEOUT,"External timeout",List.of(),List.of(),0,null,"");
        String file=outside?"B.txt":"A.txt",text=outside?"other\n":"before\n";
        return new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS,"Codex claims all criteria passed",List.of(),List.of(),0,0,"",null,null,null,null,new ImplementationProposal(List.of(new ImplementationProposal.Edit(file,ImplementationProposal.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"after\n"))));
      }};
    try(var delegation=new ExternalAgentDelegationService(executor,new dev.mikoto2000.rei.core.service.CommandCancellationService(),new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),e->{},Optional.empty())) {
      delegation.implementations(new IsolatedImplementationService(temporary.resolve("private"),new ExternalAgentProcessRunner(),cancelled->new dev.mikoto2000.rei.core.SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService(),cancelled)));
      var properties=new CodexProperties();properties.setImplementationEnabled(true);properties.setImplementationTestCommand(System.getProperty("os.name").startsWith("Windows")?"exit 0":"true");if(mode==4){properties.setImplementationTestCommand(System.getProperty("os.name").startsWith("Windows")?"Start-Sleep -Seconds 2":"sleep 2");properties.setImplementationTestTimeoutSeconds(1);}delegation.modelBudgetProperties(properties);
      var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+temporary.resolve("requests.db"));
      var repository=new ImplementationRequestRepository(source,Clock.systemUTC());var approvals=new ToolApprovalRepository(source,Clock.systemUTC());
      var service=new ImplementationRequestService(repository,approvals,new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(ActionCapability.values()),Set.of(),Map.of())),properties,delegation,Clock.systemUTC());
      var run=new RunExecutionContext("run",new dev.mikoto2000.rei.llm.OutputLimitRunBudget(2,10),null,null,null);run.setRunContext(new dev.mikoto2000.rei.core.chat.AgentRunContext("run","session",root,"project"));String user=mode==5?"/agent codex implement "+root:"A.txt を実装してください";run.setUserRequest(user);execution.set(run);
      var spec=new ImplementationSpecification(1,"Improve greeting",List.of("Replace before with after"),".",List.of("A.txt"),List.of("No new files"),List.of(new ImplementationSpecification.AcceptanceCriterion("greeting","Greeting says after")),List.of(),"REPLACE_EXISTING_TEXT");
      var prepared=service.prepare(run,spec,null);assertEquals("AUTHORIZED",prepared.status());assertEquals(0,calls.get());
      if(mode==2){assertThrows(java.util.concurrent.CancellationException.class,()->service.execute(run,prepared.requestId(),1,prepared.specificationSha256()));assertEquals("UNKNOWN",repository.get(prepared.requestId()).executionStatus());assertEquals(1,calls.get());assertEquals("before\n",Files.readString(root.resolve("A.txt")));return;}
      boolean failed=outside||mode==3||mode==4;
      var result=service.execute(run,prepared.requestId(),1,prepared.specificationSha256());assertEquals(failed?"FAILED":"RESULT_AVAILABLE",result.status());assertEquals(1,calls.get());assertEquals(user,run.userRequest());assertEquals("before\n",Files.readString(root.resolve("A.txt")));
      var receipt=delegation.implementation(run,result.receiptId());assertEquals(failed?"FAILED":"READY_FOR_APPROVAL",receipt.status());if(mode==4){assertTrue(result.testResult().initialTest().timedOut());assertNull(result.testResult().finalTest());}assertEquals("NOT_VERIFIED",result.acceptanceResults().getFirst().status());assertFalse(result.mergePerformed());assertFalse(result.pushPerformed());
      assertEquals(result,service.execute(run,prepared.requestId(),1,prepared.specificationSha256()));assertEquals(1,calls.get());
    }
  }
  void git(Path root,String... args)throws Exception{var cmd=new ArrayList<String>(List.of("git","-c","core.hooksPath=","-c","core.autocrlf=false","-c","user.name=Fixture","-c","user.email=fixture@example.invalid"));cmd.addAll(List.of(args));var p=new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();assertTrue(p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,p.exitValue(),new String(p.getInputStream().readAllBytes()));}
}
