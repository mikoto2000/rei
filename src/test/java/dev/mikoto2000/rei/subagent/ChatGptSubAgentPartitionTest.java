package dev.mikoto2000.rei.subagent;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.chatgpt.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatGptSubAgentPartitionTest {
  @TempDir Path root;
  final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
  PlanClient client;
  ToolPermissionGuard guard;
  SubAgentRegistry registry;
  RunRegistry runs;
  AgentRunContext owner;
  LlmCallReservation budget;
  Clock clock=Clock.systemUTC();
  List<AgentEvent> events;
  List<String> tasks;
  @BeforeEach void setup() throws Exception {
    Files.writeString(root.resolve("review.yaml"),"""
        id: design-review
        name: Design
        description: Review
        systemPrompt: Review supplied documents
        tools: []
        maxSteps: 8
        maxRepairs: 2
        timeout: 120s
        """);
    registry=new SubAgentRegistry(root,new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()),model->true));
    assertThat(registry.reload()).isEmpty();
    client=mock(PlanClient.class);guard=mock(ToolPermissionGuard.class);runs=new RunRegistry(clock);
    owner=new AgentRunContext("parent","session",root,"project");runs.register(owner);runs.transition(owner.runId(),RunStatus.RUNNING,null);
    budget=mock(LlmCallReservation.class);when(budget.tryReserve()).thenReturn(true);
    events=new ArrayList<>();tasks=new ArrayList<>();
    doAnswer(call->{
      assertThat(call.<String>getArgument(0)).isEqualTo("default");assertThat(call.<String>getArgument(1)).isEqualTo("model");
      ((Runnable)call.getArgument(8)).run();String task=call.getArgument(3);tasks.add(task);
      assertThat(PlanClient.encodedRequestBytes("model",call.getArgument(2),List.of(new PlanClient.Input("user",task))))
          .isLessThanOrEqualTo(call.getArgument(5));
      return response(task);
    }).when(client).infer(anyString(),anyString(),anyString(),anyString(),any(),anyInt(),anyInt(),any(),any());
  }
  ChatGptSubAgentBackend backend(ToolPermissionGuard permission) {
    return new ChatGptSubAgentBackend(client,new PlanProperties(true,root,Set.of("design-review")),registry,permission,
        new CommandCancellationService(),new AgentEventFactory(clock),events::add,clock,runs);
  }
  ChatGptSubAgentBackend.Request request(List<String> sources) {
    return new ChatGptSubAgentBackend.Request("design-review","chatgpt-plan","model","default","Review",sources,"{\"decision\":\"accept\"}");
  }
  List<String> files(int count,String content) throws Exception {
    var names=new ArrayList<String>();for(int i=0;i<count;i++){String name="source-"+i+".md";names.add(name);Files.writeString(root.resolve(name),content);}
    return names;
  }
  PlanSseAssembler.Result response(String task) throws Exception {
    var input=json.readTree(task);
    var wrapper=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(ChatGptSubAgentBackendTest.VALID);
    wrapper.set("scope",input.path("scope"));var refs=wrapper.putArray("references");
    for(var source:input.path("sources")) {
      var ref=refs.addObject();for(String field:List.of("referenceId","path","sha256"))ref.set(field,source.get(field));
      String content=source.path("content").asText();ref.put("quote",content.substring(0,Math.min(content.length(),32)));
    }
    return new PlanSseAssembler.Result(PlanSseAssembler.State.COMPLETED,wrapper.toString(),17,"response",null);
  }
  @Test void recursiveSplitRunsSequentiallyInOriginalOrderAndKeepsValidatedGroupResults() throws Exception {
    var names=files(8,"Plain paragraph.\n"+"x".repeat(70000));
    var receipt=backend(guard).run(request(names),owner,false,budget);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(receipt.groups()).hasSize(4);assertThat(tasks).hasSize(4);
    assertThat(receipt.groups().stream().flatMap(g->g.scope().stream()).toList()).isEqualTo(names);
    assertThat(receipt.groups()).allSatisfy(g->{assertThat(g.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
      assertThat(g.result().subAgentRunId()).isEqualTo(receipt.requestId());
      assertThat(g.result().structuredOutput()).isNotNull();assertThat(g.totalTokens()).isEqualTo(17);});
    assertThat(receipt.totalTokens()).isEqualTo(68);assertThat(receipt.result().structuredOutput()).isNull();
    var host=json.readTree(receipt.result().output());assertThat(host.path("groups").size()).isEqualTo(4);
    assertThat(host.path("remainingScopes").isEmpty()).isTrue();assertThat(host.path("unverified").toString()).contains("Cross-group");
    verify(guard,times(1)).check(eq("requestChatGptPlanSubAgent"),anyString(),eq(owner));
    verify(budget,times(4)).tryReserve();verify(budget,times(4)).recordTotalTokens(17);
    for(String name:names)assertThat(Files.readString(root.resolve(name))).hasSize(70017);
    var comparison=PlanReviewComparison.compare(List.of(new ParallelSubAgentDelegator.Item("review","design-review",
        ParallelSubAgentDelegator.Status.COMPLETED,receipt.result(),receipt)));
    assertThat(comparison.truthVerified()).isFalse();assertThat(comparison.evidence().get("review")).hasSize(8);
  }
  @Test void doubleEncodedQuotesTriggerSplittingEvenWhenRawTotalFits() throws Exception {
    var names=files(2,"\"".repeat(45000));
    var receipt=backend(guard).run(request(names),owner,false,budget);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);assertThat(tasks).hasSize(2);
  }
  @Test void thirtyMinuteDeadlineIsSharedAcrossGroupsWithoutResettingParentAccounting() throws Exception {
    var definition=root.resolve("review.yaml");
    Files.writeString(definition,Files.readString(definition).replace("timeout: 120s","timeout: 30m"));
    assertThat(registry.reload()).isEmpty();
    var remaining=new ArrayList<Duration>();
    doAnswer(call->{
      remaining.add(call.getArgument(4));
      ((Runnable)call.getArgument(8)).run();String task=call.getArgument(3);tasks.add(task);
      Thread.sleep(20);return response(task);
    }).when(client).infer(anyString(),anyString(),anyString(),anyString(),any(),anyInt(),anyInt(),any(),any());
    var service=backend(guard);
    service.configureExecution(new PlanExecutionProperties(false,null,null,null,null,null,Duration.ofMinutes(30),false));
    var receipt=service.run(request(files(8,"x".repeat(70000))),owner,false,budget);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(remaining).hasSize(4);
    assertThat(remaining.getFirst()).isGreaterThan(Duration.ofMinutes(29)).isLessThanOrEqualTo(Duration.ofMinutes(30));
    for(int i=1;i<remaining.size();i++)assertThat(remaining.get(i)).isLessThan(remaining.get(i-1).minusMillis(15));
    verify(budget,times(4)).tryReserve();verify(budget,times(4)).recordTotalTokens(17);
    assertThat(receipt.totalTokens()).isEqualTo(68);
  }
  @Test void failureInTheSecondGroupStopsThirdAndRetainsFirstResult() throws Exception {
    var names=files(8,"x".repeat(70000));var count=new AtomicInteger();
    doAnswer(call->{((Runnable)call.getArgument(8)).run();String task=call.getArgument(3);tasks.add(task);
      if(count.incrementAndGet()==2)return new PlanSseAssembler.Result(PlanSseAssembler.State.FAILED,"",17,"response",ChatGptException.Code.USAGE_LIMIT);
      return response(task);
    }).when(client).infer(anyString(),anyString(),anyString(),anyString(),any(),anyInt(),anyInt(),any(),any());
    var receipt=backend(guard).run(request(names),owner,false,budget);
    assertThat(tasks).hasSize(2);assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.FAILED);
    assertThat(receipt.groups()).hasSize(2);assertThat(receipt.groups().getFirst().result().structuredOutput()).isNotNull();
    assertThat(receipt.groups().getLast().error()).isEqualTo(ChatGptException.Code.USAGE_LIMIT);
    assertThat(json.readTree(receipt.result().output()).path("remainingScopes").size()).isEqualTo(2);
  }
  @Test void exhaustedSharedBudgetDoesNotStartTheNextGroup() throws Exception {
    when(budget.tryReserve()).thenReturn(true,false);
    var receipt=backend(guard).run(request(files(4,"x".repeat(70000))),owner,false,budget);
    assertThat(tasks).hasSize(1);assertThat(receipt.groups().getFirst().state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(receipt.result().output()).contains("SHARED_LLM_CALL_BUDGET_EXHAUSTED");
  }
  @Test void policyRevokedBetweenGroupsStopsBeforeAnotherInference() throws Exception {
    doNothing().doThrow(new ToolPermissionException("requestChatGptPlanSubAgent",PermissionDecision.DENY))
        .when(guard).checkAuthorizedPlanPolicy(anyString(),anyString(),eq(owner));
    var receipt=backend(guard).run(request(files(4,"x".repeat(70000))),owner,false,budget);
    assertThat(tasks).hasSize(1);assertThat(receipt.groups().getFirst().state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(receipt.error()).isEqualTo(ChatGptException.Code.PERMISSION_DENIED);
    assertThat(receipt.result().output()).contains("TOOL_PERMISSION_DENIED");
  }
  @Test void ambiguousSecondDispatchStopsWithoutRetryingOrStartingLaterGroups() throws Exception {
    var count=new AtomicInteger();
    doAnswer(call->{((Runnable)call.getArgument(8)).run();String task=call.getArgument(3);tasks.add(task);
      if(count.incrementAndGet()==2)throw ChatGptException.of(ChatGptException.Code.TRANSPORT);return response(task);
    }).when(client).infer(anyString(),anyString(),anyString(),anyString(),any(),anyInt(),anyInt(),any(),any());
    var receipt=backend(guard).run(request(files(8,"x".repeat(70000))),owner,false,budget);
    assertThat(tasks).hasSize(2);assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.OUTCOME_UNKNOWN);
    assertThat(receipt.totalTokens()).isNull();assertThat(receipt.groups().getFirst().totalTokens()).isEqualTo(17);
    assertThat(receipt.groups().getLast().state()).isEqualTo(ChatGptSubAgentBackend.State.OUTCOME_UNKNOWN);
  }
  @Test void plannedGroupsExceedingConfiguredIterationsAreRejectedBeforeApprovalOrDispatch() throws Exception {
    var backend=backend(guard);backend.configureExecution(new PlanExecutionProperties(true,1,0,1,262144,1048576,Duration.ofSeconds(120),false));
    var receipt=backend.run(request(files(4,"x".repeat(70000))),owner,false,budget);
    assertThat(receipt.result().output()).contains("GROUP_CALL_BUDGET_EXCEEDED");verifyNoInteractions(client);
    verify(guard,never()).check(eq("requestChatGptPlanSubAgent"),anyString(),eq(owner));
  }
  @Test void nativeToolReservationsConsumeTheSameRoleLimitAcrossGroups() throws Exception {
    when(client.authority()).thenReturn("local-native-transport");
    Files.writeString(root.resolve("review.yaml"),Files.readString(root.resolve("review.yaml"))
        .replace("tools: []","tools: [readMultiFile]").replace("maxSteps: 8","maxSteps: 2"));
    var nativeRegistry=new SubAgentRegistry(root,new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of("readMultiFile")),model->true));
    assertThat(nativeRegistry.reload()).isEmpty();
    var nativeBudget=new java.util.concurrent.atomic.AtomicReference<LlmCallReservation>();
    doAnswer(call->{nativeBudget.set(call.getArgument(0));return null;}).when(client).nativeBudget(any(),anyInt());
    doAnswer(call->{((Runnable)call.getArgument(8)).run();String task=call.getArgument(3);tasks.add(task);
      assertThat(nativeBudget.get().tryReserve()).isTrue();return response(task);
    }).when(client).infer(anyString(),anyString(),anyString(),anyString(),any(),anyInt(),anyInt(),any(),any());
    var backend=new ChatGptSubAgentBackend(client,new PlanProperties(true,root,Set.of("design-review")),nativeRegistry,guard,
        new CommandCancellationService(),new AgentEventFactory(clock),events::add,clock,runs,"codex-app-server");
    var request=new ChatGptSubAgentBackend.Request("design-review","codex-app-server","model","default","Review",files(4,"x".repeat(70000)),"{\"decision\":\"accept\"}");
    var receipt=backend.run(request,owner,false,budget);
    assertThat(tasks).hasSize(1);assertThat(receipt.result().output()).contains("GROUP_CALL_BUDGET_EXCEEDED");
    verify(budget,times(2)).tryReserve();
  }
  @Test void cancellationBeforeTheNextSendKeepsCompletedGroupAndSendsNothingFurther() throws Exception {
    var cancellation=new CommandCancellationService();var checks=new AtomicInteger();
    doAnswer(call->{if(checks.incrementAndGet()==2)cancellation.cancel();return null;})
        .when(guard).checkAuthorizedPlanPolicy(anyString(),anyString(),eq(owner));
    doAnswer(call->{if(call.<java.util.function.BooleanSupplier>getArgument(7).getAsBoolean())throw ChatGptException.of(ChatGptException.Code.CANCELLED);
      ((Runnable)call.getArgument(8)).run();String task=call.getArgument(3);tasks.add(task);return response(task);
    }).when(client).infer(anyString(),anyString(),anyString(),anyString(),any(),anyInt(),anyInt(),any(),any());
    var backend=new ChatGptSubAgentBackend(client,new PlanProperties(true,root,Set.of("design-review")),registry,guard,
        cancellation,new AgentEventFactory(clock),events::add,clock,runs);
    var receipt=backend.run(request(files(4,"x".repeat(70000))),owner,false,budget);
    assertThat(tasks).hasSize(1);assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.STOP_CONFIRMED);
    assertThat(receipt.groups().getFirst().state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(receipt.groups().getLast().error()).isEqualTo(ChatGptException.Code.CANCELLED);
  }
  @Test void subsequentGroupsUseTheRemainingDeadlineRatherThanANewTimeout() throws Exception {
    var durations=new ArrayList<Duration>();
    doAnswer(call->{((Runnable)call.getArgument(8)).run();durations.add(call.getArgument(4));
      String task=call.<List<PlanClient.Input>>getArgument(3).getFirst().content();tasks.add(task);
      if(durations.size()==1)Thread.sleep(25);return response(task);
    }).when(client).inferHistory(anyString(),anyString(),anyString(),anyList(),any(),anyInt(),anyInt(),any(),any());
    var backend=backend(guard);backend.configureExecution(new PlanExecutionProperties(true,2,0,1,262144,1048576,Duration.ofSeconds(2),false));
    var receipt=backend.run(request(files(4,"x".repeat(70000))),owner,false,budget);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);assertThat(durations).hasSize(2);
    assertThat(durations.getLast()).isLessThan(durations.getFirst().minusMillis(10));
  }
  @Test void aSingleEncodedOversizeOrUnreadableLaterSourceNeverStartsAPartialPlan() throws Exception {
    var names=files(2,"\"".repeat(100000));
    var failed=backend(guard).run(request(names),owner,false,budget);
    assertThat(failed.error()).isEqualTo(ChatGptException.Code.SEND_LIMIT);assertThat(failed.result().output()).contains("UNSPLITTABLE_INPUT");
    verifyNoInteractions(client);clearInvocations(client);
    var missing=backend(guard).run(request(List.of(names.getFirst(),"missing.md")),owner,false,budget);
    assertThat(missing.error()).isEqualTo(ChatGptException.Code.SOURCE_UNAVAILABLE);verifyNoInteractions(client);
  }
  @Test @Tag("integration") void oneApprovalCoversTheWholePlanAndChangedLaterSourceNeedsNewApproval() throws Exception {
    var names=files(4,"x".repeat(70000));
    var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("approvals.db"));
    var approvals=new ToolApprovalRepository(source,clock);
    var permission=new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(ActionCapability.READ),Set.of(),Map.of())),new AgentEventFactory(clock),events::add);
    permission.setApprovals(approvals);var backend=backend(permission);
    var pending=backend.run(request(names),owner,false,budget);assertThat(pending.error()).isEqualTo(ChatGptException.Code.CONSENT_REQUIRED);assertThat(tasks).isEmpty();
    var first=approvals.list("project").getFirst();approvals.decide("project",first.id(),true);
    Files.writeString(root.resolve(names.getLast()),"y".repeat(70000));
    var changed=backend.run(request(names),owner,false,budget);assertThat(changed.error()).isEqualTo(ChatGptException.Code.CONSENT_REQUIRED);assertThat(tasks).isEmpty();
    var current=approvals.list("project").stream().filter(a->a.status().equals("PENDING")).findFirst().orElseThrow();
    assertThat(current.id()).isNotEqualTo(first.id());approvals.decide("project",current.id(),true);
    var complete=backend.run(request(names),owner,false,budget);assertThat(complete.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(tasks).hasSize(2);assertThat(approvals.get("project",current.id()).status()).isEqualTo("CONSUMED");
    backend.run(request(names),owner,false,budget);assertThat(tasks).hasSize(2);
  }
}
