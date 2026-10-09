package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.stagnation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("integration")
class ImplementationRequestServiceTest {
  @TempDir Path root;
  ImplementationRequestRepository repository;ToolApprovalRepository approvals;CodexProperties properties;
  ExternalAgentDelegationService delegation;ImplementationRequestService service;RunExecutionContext run;
  ImplementationSpecification specification;
  @BeforeEach void setup() throws Exception {
    git("init","--quiet");Files.writeString(root.resolve("A.txt"),"before\n");git("add","A.txt");git("commit","--quiet","--no-gpg-sign","-m","fixture");
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolveSibling(root.getFileName()+".db"));
    repository=new ImplementationRequestRepository(source,Clock.systemUTC());approvals=new ToolApprovalRepository(source,Clock.systemUTC());
    properties=new CodexProperties();properties.setImplementationEnabled(true);properties.setImplementationTestCommand("exit 0");delegation=mock(ExternalAgentDelegationService.class);
    run=new RunExecutionContext("run",new dev.mikoto2000.rei.llm.OutputLimitRunBudget(2,10),null,null,null);
    run.setRunContext(new AgentRunContext("run","session",root,"project"));run.setUserRequest("Codex で A.txt を実装してください");
    specification=new ImplementationSpecification(1,"Improve greeting",List.of("Replace before with after"),"A.txt",List.of("A.txt"),List.of("No new files"),List.of(new ImplementationSpecification.AcceptanceCriterion("greeting","Greeting says after")),List.of(),"REPLACE_EXISTING_TEXT");
    policy(Set.of(ActionCapability.values()),Set.of(),true);
  }
  void policy(Set<ActionCapability> automatic,Set<ActionCapability> denied,boolean enabled) {
    service=new ImplementationRequestService(repository,approvals,new ToolPermissionPolicy(new ToolPermissionProperties(enabled,automatic,denied,Map.of())),properties,delegation,Clock.systemUTC());
  }
  @Test void policyAuthorizesOnlyActualHumanImplementationAndOptIn() {
    var ready=service.prepare(run,specification,null);assertEquals("AUTHORIZED",ready.status());assertFalse(ready.approvalRequired());
    run.setUserRequest("Please review A.txt");assertEquals("REJECTED",service.prepare(run,specification,null).status());
    run.setUserRequest("Codex で A.txt を実装してください");properties.setImplementationEnabled(false);assertEquals("REJECTED",service.prepare(run,specification,null).status());verifyNoInteractions(delegation);
  }
  @Test void disabledPolicyDoesNotImplicitlyGrantAutomaticExecutionAndDenialWins() {
    policy(Set.of(ActionCapability.values()),Set.of(),false);assertEquals("AWAITING_APPROVAL",service.prepare(run,specification,null).status());
    policy(Set.of(),Set.of(ActionCapability.EXECUTE),true);run.setUserRequest("Codex で A.txt を実装してください。もう一度");
    assertEquals("REJECTED",service.prepare(run,specification,null).status());verifyNoInteractions(delegation);
  }
  @Test void explicitApprovalBindsCanonicalSpecificationAndSurvivesNewRun() throws Exception {
    policy(Set.of(ActionCapability.READ),Set.of(),true);
    var prepared=service.prepare(run,specification,null);assertEquals("AWAITING_APPROVAL",prepared.status());
    assertEquals("AWAITING_APPROVAL",service.execute(run,prepared.requestId(),prepared.specificationVersion(),prepared.specificationSha256()).status());verifyNoInteractions(delegation);
    var approval=approvals.list("project").getFirst();assertTrue(approval.argumentsPreview().contains("Replace before with after"));assertTrue(approval.argumentsPreview().contains("Greeting says after"));
    approvals.decide("project",approval.id(),true);
    run.setRunContext(new AgentRunContext("resume","session",root,"project"));run.setUserRequest("保存されたタスクを再開してください");
    when(delegation.executeSpecification(any(),anyString(),any(),anyString(),anyString(),anyInt())).thenReturn(receipt("READY_FOR_APPROVAL"));
    var result=service.execute(run,prepared.requestId(),1,prepared.specificationSha256());assertEquals("RESULT_AVAILABLE",result.status());assertEquals("NOT_VERIFIED",result.acceptanceResults().getFirst().status());
    assertEquals(result,service.execute(run,prepared.requestId(),1,prepared.specificationSha256()));verify(delegation,times(1)).executeSpecification(any(),anyString(),any(),anyString(),anyString(),anyInt());
  }
  @Test void alteredArgumentsProjectOrRecipeNeverExecute() {
    var prepared=service.prepare(run,specification,null);
    assertThrows(IllegalArgumentException.class,()->service.execute(run,prepared.requestId(),2,prepared.specificationSha256()));
    assertThrows(IllegalArgumentException.class,()->service.execute(run,prepared.requestId(),1,"a".repeat(64)));
    run.setRunContext(new AgentRunContext("run","session",root,"other"));assertThrows(IllegalArgumentException.class,()->service.execute(run,prepared.requestId(),1,prepared.specificationSha256()));
    run.setRunContext(new AgentRunContext("run","session",root,"project"));properties.setImplementationTestCommand("different");assertThrows(IllegalArgumentException.class,()->service.execute(run,prepared.requestId(),1,prepared.specificationSha256()));verifyNoInteractions(delegation);
  }
  @Test void unknownIsNeverRetriedAndSuccessorRequiresExplicitApprovalEvenUnderAutomaticPolicy() throws Exception {
    var prepared=service.prepare(run,specification,null);repository.claim(prepared.requestId(),"dead-jvm");
    assertEquals("UNKNOWN",service.execute(run,prepared.requestId(),1,prepared.specificationSha256()).status());verifyNoInteractions(delegation);
    var successor=service.prepare(run,specification,prepared.requestId());assertNotEquals(prepared.requestId(),successor.requestId());assertEquals("AWAITING_APPROVAL",successor.status());
    assertEquals(prepared.requestId(),repository.get(successor.requestId()).previousRequestId());assertEquals("AWAITING_APPROVAL",service.execute(run,successor.requestId(),1,successor.specificationSha256()).status());verifyNoInteractions(delegation);
  }
  @Test void cancellationBeforeClaimDoesNotLaunch() {
    var prepared=service.prepare(run,specification,null);run.cancel();assertThrows(java.util.concurrent.CancellationException.class,()->service.execute(run,prepared.requestId(),1,prepared.specificationSha256()));verifyNoInteractions(delegation);
  }
  IsolatedImplementationService.Receipt receipt(String status){return new IsolatedImplementationService.Receipt("receipt","project","session",root.toString(),"tree","branch","base",status,"b".repeat(64),"commit",List.of("A.txt"),Map.of(),null,"diagnostic");}
  void git(String... args)throws Exception {var command=new ArrayList<String>(List.of("git","-c","user.name=Fixture","-c","user.email=fixture@example.invalid","-c","core.hooksPath="));command.addAll(List.of(args));var p=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();assertTrue(p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,p.exitValue(),new String(p.getInputStream().readAllBytes()));}
}
