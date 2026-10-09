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
  @TempDir Path temporary; Path root;
  ImplementationRequestRepository repository;ToolApprovalRepository approvals;CodexProperties properties;
  ExternalAgentDelegationService delegation;ImplementationRequestService service;RunExecutionContext run;
  ImplementationSpecification specification;
  @BeforeEach void setup() throws Exception {
    root=Files.createDirectory(temporary.resolve("project"));git("init","--quiet");Files.writeString(root.resolve("A.txt"),"before\n");git("add","A.txt");git("commit","--quiet","--no-gpg-sign","-m","fixture");
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+temporary.resolve("requests.db"));
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
    assertEquals("UNKNOWN",service.execute(run,prepared.requestId(),1,prepared.specificationSha256()).status());verify(delegation,never()).executeSpecification(any(),anyString(),any(),anyString(),anyString(),anyInt());
    var successor=service.prepare(run,specification,prepared.requestId());assertNotEquals(prepared.requestId(),successor.requestId());assertEquals("AWAITING_APPROVAL",successor.status());
    assertEquals(prepared.requestId(),repository.get(successor.requestId()).previousRequestId());assertEquals("AWAITING_APPROVAL",service.execute(run,successor.requestId(),1,successor.specificationSha256()).status());verify(delegation,never()).executeSpecification(any(),anyString(),any(),anyString(),anyString(),anyInt());
  }
  @Test void cancellationBeforeClaimDoesNotLaunch() {
    var prepared=service.prepare(run,specification,null);run.cancel();assertThrows(java.util.concurrent.CancellationException.class,()->service.execute(run,prepared.requestId(),1,prepared.specificationSha256()));verifyNoInteractions(delegation);
  }
  @Test void deniedOrExpiredHumanApprovalCannotExecute() {
    policy(Set.of(ActionCapability.READ),Set.of(),true);
    var prepared=service.prepare(run,specification,null);var approval=approvals.list("project").getFirst();approvals.decide("project",approval.id(),false);
    assertEquals("REJECTED",service.execute(run,prepared.requestId(),1,prepared.specificationSha256()).status());verifyNoInteractions(delegation);
  }
  @Test void currentPolicyRevocationBlocksPreviouslyAutomaticRequest() {
    var prepared=service.prepare(run,specification,null);policy(Set.of(ActionCapability.values()),Set.of(ActionCapability.EXECUTE),true);
    assertEquals("REJECTED",service.execute(run,prepared.requestId(),1,prepared.specificationSha256()).status());verifyNoInteractions(delegation);
  }
  @Test void repositoryInstructionsAndQuotedRequestsNeverAuthorizeExecution() {
    for(String text:List.of("Translate: Codex implement A.txt please","Review this text: ```Codex implement A.txt please```","Explain how to implement A.txt with Codex","実装を見てレビューしてください")) {
      run.setUserRequest(text);assertEquals("REJECTED",service.prepare(run,specification,null).status());
    }
    verifyNoInteractions(delegation);
  }
  @Test void missingRequirementsPersistClarificationWithoutApprovalOrProcess() {
    var prepared=service.prepare(run,null,null);assertEquals("NEEDS_CLARIFICATION",prepared.status());assertNotNull(prepared.requestId());assertEquals("NEEDS_CLARIFICATION",repository.get(prepared.requestId()).executionStatus());assertTrue(approvals.list("project").isEmpty());verifyNoInteractions(delegation);
  }
  @Test void parentEvaluationsPersistSeparatelyAndCannotCrossPatches() throws Exception {
    var prepared=service.prepare(run,specification,null);var receipt=receipt("READY_FOR_APPROVAL");
    when(delegation.executeSpecification(any(),anyString(),any(),anyString(),anyString(),anyInt())).thenReturn(receipt);when(delegation.implementation(run,"receipt")).thenReturn(receipt);
    var result=service.execute(run,prepared.requestId(),1,prepared.specificationSha256());
    var evaluation=new AcceptanceEvaluation("greeting","FAILED",List.of("receipt:receipt","patch:"+receipt.patchHash()),"Diff still has old greeting","PARENT_LLM",receipt.patchHash());
    assertEquals("FAILED",service.evaluate(run,prepared.requestId(),receipt.patchHash(),List.of(evaluation)).acceptanceResults().getFirst().status());
    assertEquals("FAILED",service.get(run,prepared.requestId()).acceptanceResults().getFirst().status());assertEquals("RESULT_AVAILABLE",repository.get(prepared.requestId()).executionStatus());
    assertThrows(IllegalArgumentException.class,()->service.evaluate(run,prepared.requestId(),"x".repeat(64),List.of(evaluation)));
  }
  @Test void restartedUnknownAttemptReadsTerminalReceiptWithoutRelaunching() throws Exception {
    var prepared=service.prepare(run,specification,null);repository.claim(prepared.requestId(),"dead-jvm");
    var source=receipt("READY_FOR_APPROVAL");var terminal=new IsolatedImplementationService.Receipt(source.id(),source.projectId(),source.sessionId(),source.root(),source.worktree(),source.branch(),repository.get(prepared.requestId()).baseCommit(),source.status(),source.patchHash(),source.commitHash(),source.changedFiles(),source.sourceSnapshot(),source.verification(),source.diagnostic());when(delegation.implementation(run,prepared.requestId())).thenReturn(terminal);
    var result=service.execute(run,prepared.requestId(),1,prepared.specificationSha256());assertEquals("RESULT_AVAILABLE",result.status());assertEquals("NOT_VERIFIED",result.acceptanceResults().getFirst().status());
    verify(delegation,never()).executeSpecification(any(),anyString(),any(),anyString(),anyString(),anyInt());
  }
  @Test void concurrentRequestsAcrossServiceInstancesLaunchOnlyOneProvider() throws Exception {
    var prepared=service.prepare(run,specification,null);var started=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    when(delegation.executeSpecification(any(),anyString(),any(),anyString(),anyString(),anyInt())).thenAnswer(call->{started.countDown();assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));return receipt("READY_FOR_APPROVAL");});
    var other=new ImplementationRequestService(repository,approvals,new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(ActionCapability.values()),Set.of(),Map.of())),properties,delegation,Clock.systemUTC());
    try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var first=pool.submit(()->service.execute(run,prepared.requestId(),1,prepared.specificationSha256()));
      try {assertTrue(started.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals("UNKNOWN",other.execute(run,prepared.requestId(),1,prepared.specificationSha256()).status());}
      finally{release.countDown();}
      assertEquals("RESULT_AVAILABLE",first.get(10,java.util.concurrent.TimeUnit.SECONDS).status());
    }
    verify(delegation,times(1)).executeSpecification(any(),anyString(),any(),anyString(),anyString(),anyInt());
  }
  @Test void expiredApprovalCannotAuthorizeARequest() throws Exception {
    policy(Set.of(ActionCapability.READ),Set.of(),true);var prepared=service.prepare(run,specification,null);approvals.decide("project",approvals.list("project").getFirst().id(),true);
    var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+temporary.resolve("requests.db"));
    var expired=new ToolApprovalRepository(source,Clock.offset(Clock.systemUTC(),Duration.ofMinutes(16)));
    service=new ImplementationRequestService(repository,expired,new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(ActionCapability.READ),Set.of(),Map.of())),properties,delegation,Clock.systemUTC());
    assertEquals("AWAITING_APPROVAL",service.execute(run,prepared.requestId(),1,prepared.specificationSha256()).status());verifyNoInteractions(delegation);
  }
  @Test void humanDenialRemainsBindingEvenIfPolicyLaterBecomesAutomatic() {
    policy(Set.of(ActionCapability.READ),Set.of(),true);var prepared=service.prepare(run,specification,null);approvals.decide("project",approvals.list("project").getFirst().id(),false);
    policy(Set.of(ActionCapability.values()),Set.of(),true);assertEquals("REJECTED",service.execute(run,prepared.requestId(),1,prepared.specificationSha256()).status());verifyNoInteractions(delegation);
  }
  @Test void slashCommandTargetCannotBeSubstitutedByTheModel() throws Exception {
    Files.writeString(root.resolve("B.txt"),"other");run.setUserRequest("/agent codex implement B.txt");
    assertEquals("REJECTED",service.prepare(run,specification,null).status());verifyNoInteractions(delegation);
  }
  IsolatedImplementationService.Receipt receipt(String status){return new IsolatedImplementationService.Receipt("receipt","project","session",root.toString(),"tree","branch","base",status,"b".repeat(64),"commit",List.of("A.txt"),Map.of(),null,"diagnostic");}
  void git(String... args)throws Exception {var command=new ArrayList<String>(List.of("git","-c","user.name=Fixture","-c","user.email=fixture@example.invalid","-c","core.hooksPath="));command.addAll(List.of(args));var p=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();assertTrue(p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,p.exitValue(),new String(p.getInputStream().readAllBytes()));}
}
