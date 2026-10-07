package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import reactor.core.publisher.Flux;

@org.junit.jupiter.api.Tag("integration")
class SubAgentRunnerTest {
  @Test void durableChildCancellationCanResumeUnderTheSameHumanWithConsumedCallsRetained() throws Exception {
    var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("children.db"));
    var repository=new DurableSubAgentRepository(source,Clock.systemUTC());
    var properties=new SubAgentProperties();properties.setDurableEnabled(true);
    var entered=new CountDownLatch(1);var calls=new AtomicInteger();maxSteps=3;schema="{\"type\":\"object\"}";
    String finalOutput="{\"status\":\"SUCCESS\",\"summary\":\"continued result\",\"result\":{},\"warnings\":[]}";
    var runner=runner(prompt->{if(calls.incrementAndGet()==1){entered.countDown();return Flux.never();}return Flux.just(answer(finalOutput));},"120s");
    runner.configureDurable(properties,repository);
    var parent=new AgentRunContext("parent","session",directory,"project");
    var reservations=new AtomicInteger();var shared=new dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation(){
      public boolean tryReserve(){return reservations.incrementAndGet()<=5;}public int remaining(){return 5-reservations.get();}
    };
    var operation=CompletableFuture.supplyAsync(()->{try(var scope=AgentRunScope.open(parent)){return runner.run("reviewer","inspect",null,shared);}});
    assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();var child=repository.list(parent,0,10).getFirst();
    assertThat(runner.cancel(child.run())).isTrue();assertThat(operation.get(5,TimeUnit.SECONDS).status()).isEqualTo(SubAgentResult.Status.CANCELLED);
    child=repository.get(parent,child.id());assertThat(child.consumedCalls()).isEqualTo(1);
    var checkpoint=child;
    assertThatThrownBy(()->runner.resumeDurable(parent,checkpoint.id(),checkpoint.revision(),"ordinary review",shared)).isInstanceOf(IllegalArgumentException.class);
    var restartedRunner=runner(prompt->Flux.just(answer(finalOutput)),"120s");
    restartedRunner.configureDurable(properties,new DurableSubAgentRepository(source,Clock.systemUTC()));
    var continued=restartedRunner.resumeDurable(parent,child.id(),child.revision(),"/subagent resume "+child.id()+" "+child.revision(),shared);
    assertThat(continued.status()).isEqualTo(SubAgentResult.Status.COMPLETED);
    assertThat(continued.durableTaskId()).isEqualTo(child.id());
    var complete=repository.get(parent,child.id());assertThat(complete.status()).isEqualTo("COMPLETED");
    assertThat(complete.consumedCalls()).isEqualTo(2);assertThat(reservations.get()).isEqualTo(2);
    assertThat(complete.result()).isEqualTo(finalOutput);assertThat(calls.get()).isEqualTo(1);
  }
  @Test void durableToolFailureIsUnknownAndCannotBeAutomaticallyResumed()throws Exception {
    var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("children.db"));
    var repository=new DurableSubAgentRepository(source,Clock.systemUTC());var properties=new SubAgentProperties();properties.setDurableEnabled(true);
    var runner=runner(prompt->Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call","function","readMultiFile","{}"))).build())))),"120s",()->List.of(new ToolCallback(){
      public ToolDefinition getToolDefinition(){return callback().getToolDefinition();}public String call(String input){toolCalls.incrementAndGet();throw new IllegalStateException("unknown transport result");}
    }));runner.configureDurable(properties,repository);
    var parent=new AgentRunContext("parent","session",directory,"project");var shared=new dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation(){public boolean tryReserve(){return true;}public int remaining(){return 3;}};
    var runs=new dev.mikoto2000.rei.application.run.RunRegistry(Clock.systemUTC());runs.register(parent);runs.transition(parent.runId(),dev.mikoto2000.rei.application.run.RunStatus.RUNNING,null);
    var lifecycle=new dev.mikoto2000.rei.application.run.RunService(runs,new InMemoryAgentEventBus(),new AgentEventFactory(Clock.systemUTC()),cancellation,id->false);runner.setTaskTracking(true,runs,lifecycle);
    try(var scope=AgentRunScope.open(parent)){assertThat(runner.run("reviewer","inspect",null,shared).status()).isEqualTo(SubAgentResult.Status.FAILED);}
    var child=repository.list(parent,0,10).getFirst();assertThat(child.status()).isEqualTo("UNKNOWN");assertThat(child.operations()).hasSize(1);assertThat(child.operations().getFirst().status()).isEqualTo("UNKNOWN");
    assertThat(runs.get(child.run()).status()).isEqualTo(dev.mikoto2000.rei.application.run.RunStatus.UNKNOWN);
    assertThatThrownBy(()->runner.resumeDurable(parent,child.id(),child.revision(),"subagent resume "+child.id()+" "+child.revision(),shared)).isInstanceOf(IllegalArgumentException.class);assertThat(toolCalls.get()).isEqualTo(1);
    lifecycle.close();
  }
  @Test void taskTrackingUsesTheExistingChildRunAndCommonCancellation() throws Exception {
    var runs=new dev.mikoto2000.rei.application.run.RunRegistry(Clock.systemUTC());
    var bus=new InMemoryAgentEventBus();
    var factory=new AgentEventFactory(Clock.systemUTC());
    var service=new dev.mikoto2000.rei.application.run.RunService(runs,bus,factory,cancellation,id->false);
    var parent=new AgentRunContext("parent","session",directory,"project");runs.register(parent);runs.transition("parent",dev.mikoto2000.rei.application.run.RunStatus.RUNNING,null);
    var started=new CountDownLatch(1);
    var runner=runner(p->{started.countDown();return Flux.never();},"120s");
    runner.setTaskTracking(true,runs,service);
    var operation=CompletableFuture.supplyAsync(()->{try(var scope=AgentRunScope.open(parent)){return runner.run("reviewer","review",null);}});
    assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
    String child=runs.runIds().stream().filter(id->!id.equals("parent")).findFirst().orElseThrow();
    assertThat(runs.get(child).childOrigin().sessionId()).isEqualTo("session");
    assertThat(runs.get(child).status()).isEqualTo(dev.mikoto2000.rei.application.run.RunStatus.RUNNING);
    service.cancel(child);
    assertThat(operation.get(5,TimeUnit.SECONDS).status()).isEqualTo(SubAgentResult.Status.CANCELLED);
    assertThat(runs.get(child).status()).isEqualTo(dev.mikoto2000.rei.application.run.RunStatus.CANCELLED);
    assertThat(runs.get("parent").status()).isEqualTo(dev.mikoto2000.rei.application.run.RunStatus.RUNNING);
    service.close();
  }
  @Test void commonPolicyBlocksChildToolBeforeInvocation() throws Exception {
    var runner=runner(prompt -> Flux.just(new ChatResponse(List.of(new Generation(
        AssistantMessage.builder().content("").toolCalls(List.of(
            new AssistantMessage.ToolCall("call","function","readMultiFile","{}"))).build())))),"120s");
    var common=new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
        new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,Set.of(),Set.of(),Map.of()));
    runner.setToolPermissionGuard(new dev.mikoto2000.rei.core.policy.ToolPermissionGuard(common,
        new AgentEventFactory(Clock.systemUTC()),events::add));
    assertThat(runner.run("reviewer","review","context").status()).isEqualTo(SubAgentResult.Status.FAILED);
    assertThat(toolCalls.get()).isZero();
    assertThat(events).anyMatch(e -> e.payload() instanceof ToolFailedPayload failed
        && "PermissionRequired".equals(failed.error().errorType()));
  }
  @TempDir Path directory;
  final List<AgentEvent> events = new CopyOnWriteArrayList<>();
  final CommandCancellationService cancellation = new CommandCancellationService();
  final AtomicInteger toolCalls = new AtomicInteger();
  String schema;
  boolean evidenceValidation;
  String requiredCallsConfiguration="";
  int maxRepairs;
  int maxSteps = 2;
  final SubAgentToolPolicy policy = new SubAgentToolPolicy(Set.of("readMultiFile", "runCommand", "delegateTask"));
  ToolCallback callback() {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("readMultiFile").description("read").inputSchema("{}").build(); }
      public String call(String input) { toolCalls.incrementAndGet(); return "private intermediate result"; }
    };
  }
  SubAgentRunner runner(Function<Prompt, Flux<ChatResponse>> response, String timeout) throws Exception {
    return runner(response, timeout, () -> List.of(callback()));
  }
  SubAgentRunner runner(Function<Prompt, Flux<ChatResponse>> response, String timeout,
      java.util.function.Supplier<List<ToolCallback>> toolFactory) throws Exception {
    return runner(response, timeout, toolFactory,
        ignored -> ToolCallingChatOptions.builder().model("inherited-model").build());
  }
  SubAgentRunner runner(Function<Prompt, Flux<ChatResponse>> response, String timeout,
      java.util.function.Supplier<List<ToolCallback>> toolFactory,
      Function<String, ToolCallingChatOptions> options) throws Exception {
    String yaml = SubAgentConfigurationTest.yaml("reviewer").replace("120s", timeout);
    yaml = yaml.replace("maxSteps: 2", "maxSteps: " + maxSteps);
    if (maxRepairs > 0) yaml += "maxRepairs: " + maxRepairs + "\n";
    if (evidenceValidation) yaml += "evidenceTools: [readMultiFile]\n";
    yaml+=requiredCallsConfiguration;
    if (schema != null) {
      Files.writeString(directory.resolve("result.schema.json"), schema);
      yaml += "resultSchema: result.schema.json\n";
    }
    Files.writeString(directory.resolve("reviewer.yaml"), yaml);
    var registry = new SubAgentRegistry(directory, new SubAgentDefinitionLoader(policy, model -> true));
    registry.reload();
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt prompt) { throw new AssertionError("must use cancellable streaming"); }
      public Flux<ChatResponse> stream(Prompt prompt) { return response.apply(prompt); }
    };
    return new SubAgentRunner(registry, policy, ignored -> model,
        options, toolFactory, cancellation, new AgentEventFactory(Clock.systemUTC()), events::add, Clock.systemUTC());
  }
  ChatResponse answer(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
  @Test void repairRetainsOriginalValidationErrorAndStopsAtConfiguredLimit() throws Exception {
    maxRepairs = 1; maxSteps = 4;
    var calls = new AtomicInteger();
    var result = runner(p -> { calls.incrementAndGet(); return Flux.just(answer("{}")); }, "2s").run("reviewer", "task", null);
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.FAILED);
    assertThat(calls).hasValue(2);
    assertThat(result.repairAttempts()).isEqualTo(1);
    assertThat(result.validationHistory()).hasSize(2);
    assertThat(result.validationHistory().getFirst()).isNotEmpty();
    assertThat(result.validationErrors()).isEqualTo(result.validationHistory().getLast());
  }
  @Test void repairedResultKeepsHistoryAndSharesOriginalStepBudget() throws Exception {
    maxRepairs = 3;
    var calls = new AtomicInteger();
    var success = runner(p -> {
      if (calls.incrementAndGet()==1) return Flux.just(answer("{}"));
      assertThat(p.getInstructions().getLast().getText()).contains("Repair", "validation diagnostics");
      return Flux.just(answer(SubAgentResultParserTest.VALID));
    }, "2s").run("reviewer", "task", null);
    assertThat(success.status()).isEqualTo(SubAgentResult.Status.COMPLETED);
    assertThat(success.validationErrors()).isEmpty();
    assertThat(success.validationHistory()).hasSize(1);
    assertThat(success.repairAttempts()).isEqualTo(1);
    maxSteps = 1; calls.set(0);
    var exhausted = runner(p -> { calls.incrementAndGet(); return Flux.just(answer("{}")); }, "2s").run("reviewer", "task", null);
    assertThat(exhausted.status()).isEqualTo(SubAgentResult.Status.MAX_STEPS_EXCEEDED);
    assertThat(calls).hasValue(1); assertThat(exhausted.validationHistory()).hasSize(1);
    assertThat(exhausted.repairAttempts()).isZero();
  }
  @Test void repairDoesNotRetryProviderFailureAndTimeoutPreservesOriginalError() throws Exception {
    maxRepairs = 1;
    var calls = new AtomicInteger();
    var failed = runner(p -> { calls.incrementAndGet(); return Flux.error(new IllegalStateException("private failure")); }, "2s").run("reviewer", "task", null);
    assertThat(failed.status()).isEqualTo(SubAgentResult.Status.FAILED); assertThat(calls).hasValue(1);
    assertThat(failed.validationHistory()).isEmpty();
    calls.set(0);
    var timed = runner(p -> calls.incrementAndGet()==1 ? Flux.just(answer("{}")) : Flux.never(), "300ms").run("reviewer", "task", null);
    assertThat(timed.status()).isEqualTo(SubAgentResult.Status.TIMEOUT);
    assertThat(calls).hasValue(2); assertThat(timed.validationHistory()).hasSize(1);
  }
  @Test void evidenceValidationRejectsUnperformedWorkWithoutReturningRawAnswer() throws Exception {
    evidenceValidation = true;
    String raw = "{\"status\":\"SUCCESS\",\"summary\":\"private fabricated result\",\"result\":{\"evidence\":[]},\"warnings\":[]}";
    var result = runner(p -> Flux.just(answer(raw)), "2s").run("reviewer", "review", null);
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.FAILED);
    assertThat(result.output()).doesNotContain("private fabricated");
    assertThat(result.validationErrors()).anyMatch(e -> e.path().equals("/status"));
    assertThat(toolCalls).hasValue(0);
    assertThat(events.getLast().type()).isEqualTo(AgentEventType.SUBAGENT_FAILED);
  }
  @Test void repairPreservesToolReceiptsWithoutReplayingCompletedCalls() throws Exception {
    maxRepairs = 1; maxSteps = 3; evidenceValidation = true;
    var calls = new AtomicInteger();
    var runner = runner(p -> {
      int step = calls.incrementAndGet();
      if (step==1) return Flux.just(tool("readMultiFile"));
      if (step==2) return Flux.just(answer("{\"status\":\"SUCCESS\",\"summary\":\"done\",\"result\":{\"evidence\":[]},\"warnings\":[]}"));
      var response=p.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
          .map(ToolResponseMessage.class::cast).findFirst().orElseThrow().getResponses().getFirst().responseData();
      var receipt=new SubAgentResultParser().parse(response);
      var claim=Map.of("evidenceId",receipt.get("evidenceId").asString(),"tool","readMultiFile",
          "outputSha256",receipt.get("outputSha256").asString(),"quote","private intermediate result");
      return Flux.just(answer(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
          "status","SUCCESS","summary","reviewed","result",Map.of("evidence",List.of(claim)),"warnings",List.of()))));
    }, "2s");
    var result=runner.run("reviewer","review",null);
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.COMPLETED);
    assertThat(result.repairAttempts()).isEqualTo(1); assertThat(result.validationHistory()).hasSize(1);
    assertThat(toolCalls).hasValue(1); assertThat(calls).hasValue(3);
  }
  @Test void cancellingDuringRepairDisposesUpstreamAndKeepsOriginalDiagnostics() throws Exception {
    maxRepairs = 1;
    var calls=new AtomicInteger();var started=new CountDownLatch(1);var disposed=new CountDownLatch(1);
    var runner=runner(p -> calls.incrementAndGet()==1 ? Flux.just(answer("{}"))
        : Flux.<ChatResponse>never().doOnSubscribe(s->started.countDown()).doOnCancel(disposed::countDown),"5s");
    var result=new AtomicReference<SubAgentResult>();
    Thread child=Thread.ofVirtual().start(()->result.set(runner.run("reviewer","task",null)));
    assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
    String id=events.stream().filter(e->e.type()==AgentEventType.SUBAGENT_STARTED).findFirst().orElseThrow().runId();
    assertThat(runner.cancel(id)).isTrue();child.join(2000);
    assertThat(child.isAlive()).isFalse();assertThat(disposed.await(1,TimeUnit.SECONDS)).isTrue();
    assertThat(result.get().status()).isEqualTo(SubAgentResult.Status.CANCELLED);
    assertThat(result.get().validationHistory()).hasSize(1);assertThat(calls).hasValue(2);
  }
  @Test void evidenceReceiptsAreCapturedAfterActualToolAndValidatedBeforeCompletion() throws Exception {
    evidenceValidation = true;
    var calls = new AtomicInteger();
    var runner = runner(p -> {
      assertThat(p.getInstructions().getFirst().getText()).contains("result.evidence", "readMultiFile");
      if (calls.incrementAndGet()==1) return Flux.just(tool("readMultiFile"));
      var response = p.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
          .map(ToolResponseMessage.class::cast).findFirst().orElseThrow().getResponses().getFirst().responseData();
      var receipt = new SubAgentResultParser().parse(response);
      var claim = Map.of("evidenceId",receipt.get("evidenceId").asString(),"tool","readMultiFile",
          "outputSha256",receipt.get("outputSha256").asString(),"quote","private intermediate result");
      String raw = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
          "status","SUCCESS","summary","reviewed","result",Map.of("evidence",List.of(claim)),"warnings",List.of()));
      return Flux.just(answer(raw));
    }, "2s");
    var result = runner.run("reviewer", "review", null);
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.COMPLETED);
    assertThat(result.validationErrors()).isEmpty();
    assertThat(toolCalls).hasValue(1); assertThat(calls).hasValue(2);
  }
  @Test void exactArgumentContractsAreValidatedOnActualRunnerCalls() throws Exception {
    evidenceValidation=true;
    for(String arguments:List.of("{}","{paths: [missing.md]}")) {
      requiredCallsConfiguration="requiredToolCalls:\n  - tool: readMultiFile\n    arguments: "+arguments+"\n";
      var calls=new AtomicInteger();
      var runner=runner(prompt->{
        assertThat(prompt.getInstructions().getFirst().getText()).contains("Required exact JSON Tool calls");
        if(calls.incrementAndGet()==1)return Flux.just(tool("readMultiFile"));
        var response=prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
            .map(ToolResponseMessage.class::cast).findFirst().orElseThrow().getResponses().getFirst().responseData();
        var receipt=new SubAgentResultParser().parse(response);
        var claim=Map.of("evidenceId",receipt.get("evidenceId").asString(),"tool","readMultiFile",
            "outputSha256",receipt.get("outputSha256").asString(),"quote","private intermediate result");
        return Flux.just(answer(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
            "status","SUCCESS","summary","reviewed","result",Map.of("evidence",List.of(claim)),"warnings",List.of()))));
      },"2s");
      var result=runner.run("reviewer","review",null);
      assertThat(result.status()).isEqualTo(arguments.equals("{}")?SubAgentResult.Status.COMPLETED:SubAgentResult.Status.FAILED);
      assertThat(result.validationErrors().toString()).doesNotContain("private intermediate result","missing.md");
    }
  }
  @Test void invalidJsonAndInvalidEnvelopeFailWithoutRetryOrRawOutput() throws Exception {
    for (String raw : List.of("private-secret", "```json\n{}\n```", "{}",
        "{\"status\":\"DONE\",\"summary\":\"private-secret\",\"result\":{},\"warnings\":[]}")) {
      AtomicInteger calls = new AtomicInteger();
      var runner = runner(p -> { calls.incrementAndGet(); return Flux.just(answer(raw)); }, "2s");
      var result = runner.run("reviewer", "task", null);
      assertThat(result.status()).isEqualTo(SubAgentResult.Status.FAILED);
      assertThat(result.output()).doesNotContain("private-secret");
      assertThat(result.validationErrors()).isNotEmpty();
      assertThat(result.structuredOutput()).isNull();
      assertThat(calls).hasValue(1);
      assertThat(events.getLast().type()).isEqualTo(AgentEventType.SUBAGENT_FAILED);
    }
  }
  @Test void outputContractRejectsContradictorySuccessAndUsesBoundedRepairHistory() throws Exception {
    evidenceValidation=true;
    maxRepairs=1;
    maxSteps=3;
    requiredCallsConfiguration="requiredToolCalls:\n  - tool: readMultiFile\n    arguments: {}\n    expectedOutput: {found: true}\n";
    var calls=new AtomicInteger();
    var runner=runner(prompt->{
      int attempt=calls.incrementAndGet();
      if(attempt==1)return Flux.just(tool("readMultiFile"));
      var response=prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
          .map(ToolResponseMessage.class::cast).findFirst().orElseThrow().getResponses().getFirst().responseData();
      var receipt=new SubAgentResultParser().parse(response);
      var claim=Map.of("evidenceId",receipt.get("evidenceId").asString(),"tool","readMultiFile",
          "outputSha256",receipt.get("outputSha256").asString(),"quote","false");
      return Flux.just(answer(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
          "status",attempt==2?"SUCCESS":"PARTIAL","summary","reported observation",
          "result",Map.of("evidence",List.of(claim)),"warnings",List.of()))));
    },"2s",()->List.of(new ToolCallback() {
      public ToolDefinition getToolDefinition(){return callback().getToolDefinition();}
      public String call(String input){toolCalls.incrementAndGet();return "{\"found\":false}";}
    }));
    var result=runner.run("reviewer","read required file",null);
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.COMPLETED);
    assertThat(result.structuredOutput().status()).isEqualTo(SubAgentOutput.Status.PARTIAL);
    assertThat(result.repairAttempts()).isEqualTo(1);
    assertThat(result.validationHistory()).hasSize(1);
    assertThat(result.validationHistory().toString()).contains("requiredToolCalls[0]").doesNotContain("found");
    assertThat(calls).hasValue(3);
    assertThat(toolCalls).hasValue(1);
  }
  @Test void validEnvelopeIsReturnedThroughDelegationWithJsonInstructions() throws Exception {
    var runner = runner(p -> {
      assertThat(p.getInstructions().getFirst().getText()).contains("Return exactly one JSON object", "Do not output Markdown");
      return Flux.just(answer(SubAgentResultParserTest.VALID));
    }, "2s");
    var result = new SubAgentTools(runner, null).delegateTask("reviewer", "task", null);
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.COMPLETED);
    assertThat(result.output()).isEqualTo(SubAgentResultParserTest.VALID);
    assertThat(result.structuredOutput().status()).isEqualTo(SubAgentOutput.Status.SUCCESS);
    assertThat(result.structuredOutput().summary()).isEqualTo("done");
    assertThat(result.validationErrors()).isEmpty();
  }
  @Test void specificSchemaIsInPromptAndRejectsInvalidResultBeforeCompletion() throws Exception {
    schema = SubAgentResultValidatorTest.REVIEW_SCHEMA;
    for (String severity : List.of("HIGH", "CRITICAL")) {
      AtomicInteger calls = new AtomicInteger();
      String raw = """
          {"status":"PARTIAL","summary":"reviewed","result":{"findings":[
          {"severity":"%s","message":"problem"}]},"warnings":["limited scope"]}
          """.formatted(severity);
      var runner = runner(p -> {
        calls.incrementAndGet();
        assertThat(p.getInstructions().getFirst().getText()).contains("findings", "HIGH", "additionalProperties");
        return Flux.just(answer(raw));
      }, "2s");
      var result = runner.run("reviewer", "task", null);
      assertThat(calls).hasValue(1);
      if (severity.equals("HIGH")) {
        assertThat(result.status()).isEqualTo(SubAgentResult.Status.COMPLETED);
        assertThat(result.structuredOutput().status()).isEqualTo(SubAgentOutput.Status.PARTIAL);
        assertThat(result.structuredOutput().warnings()).containsExactly("limited scope");
      } else {
        assertThat(result.status()).isEqualTo(SubAgentResult.Status.FAILED);
        assertThat(result.validationErrors()).anyMatch(e -> e.path().equals("/result/findings/0/severity"));
        assertThat(events.getLast().type()).isEqualTo(AgentEventType.SUBAGENT_FAILED);
      }
    }
  }
  @Test void childPreservesWebRequestSource() throws Exception {
    var runner = runner(p -> {
      var options = (ToolCallingChatOptions) p.getOptions();
      var owner = (AgentRunContext) options.getToolContext().get(AgentRunContext.class.getName());
      assertThat(owner.requestSource()).isEqualTo(AgentRunContext.RequestSource.WEB);
      return Flux.just(answer(SubAgentResultParserTest.VALID));
    }, "2s");
    var parent = new AgentRunContext("web", "session", directory, "project", AgentRunContext.RequestSource.WEB);
    try (var scope = AgentRunScope.open(parent)) {
      assertThat(runner.run("reviewer", "task", null).status()).isEqualTo(SubAgentResult.Status.COMPLETED);
    }
  }
  ChatResponse tool(String name) {
    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(
        new AssistantMessage.ToolCall("call-1", "function", name, "{}"))).build())));
  }
  @Test void reviewerHasIndependentHistoryAndRestrictedToolsAndCorrelatedEvents() throws Exception {
    List<Prompt> requests = new CopyOnWriteArrayList<>();
    var runner = runner(p -> { requests.add(p); return Flux.just(requests.size() == 1 ? tool("readMultiFile") : answer(SubAgentResultParserTest.VALID)); }, "2s");
    var parent = new AgentRunContext("parent", "chat:main", directory);
    try (var scope = AgentRunScope.open(parent)) {
      var result = runner.run("reviewer", "review this", "explicit context");
      assertThat(result.status()).isEqualTo(SubAgentResult.Status.COMPLETED);
      assertThat(result.output()).isEqualTo(SubAgentResultParserTest.VALID);
      assertThat(AgentRunScope.current()).isEqualTo(parent);
      assertThat(result.startedAt()).isBeforeOrEqualTo(result.completedAt());
    }
    assertThat(requests.getFirst().getInstructions()).hasSize(2);
    assertThat(requests.getFirst().getInstructions().getFirst().getText()).startsWith("Review independently.\n")
        .contains("Return exactly one JSON object");
    assertThat(requests.getFirst().getInstructions().getLast().getText()).isEqualTo("review this\n\nContext:\nexplicit context");
    assertThat(requests.get(1).getInstructions()).anyMatch(m -> m instanceof ToolResponseMessage);
    var options = (ToolCallingChatOptions) requests.getFirst().getOptions();
    assertThat(options.getToolCallbacks()).extracting(c -> c.getToolDefinition().name()).containsExactly("readMultiFile");
    assertThat(toolCalls).hasValue(1); // The application-owned loop executes the single requested tool.
    assertThat(options.getModel()).isEqualTo("inherited-model");
    assertThat(events).noneMatch(e -> e.type() == AgentEventType.MESSAGE_DELTA || e.type() == AgentEventType.MESSAGE_COMPLETED);
    var lifecycle = events.stream().filter(e -> e.payload() instanceof SubAgentLifecyclePayload).toList();
    assertThat(lifecycle).extracting(AgentEvent::type).containsExactly(AgentEventType.SUBAGENT_STARTED, AgentEventType.SUBAGENT_COMPLETED);
    assertThat(lifecycle).allMatch(e -> ((SubAgentLifecyclePayload)e.payload()).parentRunId().equals("parent"));
    assertThat(lifecycle.getFirst().runId()).isEqualTo(lifecycle.getLast().runId()).isNotEqualTo("parent");
  }
  @Test void childOptionsReplaceAmbientToolsAndContextWithoutChangingProviderOptions() throws Exception {
    ToolCallback ambient = new ToolCallback() {
      public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("runCommand").description("ambient").inputSchema("{}").build(); }
      public String call(String input) { throw new AssertionError("ambient tool must never execute"); }
    };
    var configured = org.springframework.ai.openai.OpenAiChatOptions.builder()
        .model("configured-model").temperature(0.25).maxTokens(321)
        .toolCallbacks(List.of(ambient)).toolContext(Map.of("ambient-owner", "parent")).build();
    var requests = new CopyOnWriteArrayList<Prompt>();
    var runner = runner(p -> { requests.add(p); return Flux.just(answer(SubAgentResultParserTest.VALID)); },
        "2s", () -> List.of(callback()), ignored -> configured);

    assertThat(runner.run("reviewer", "task", null).status()).isEqualTo(SubAgentResult.Status.COMPLETED);

    var actual = (org.springframework.ai.openai.OpenAiChatOptions) requests.getFirst().getOptions();
    assertThat(actual).isNotSameAs(configured);
    assertThat(actual.getModel()).isEqualTo("configured-model");
    assertThat(actual.getTemperature()).isEqualTo(0.25);
    assertThat(actual.getMaxTokens()).isEqualTo(321);
    assertThat(actual.getToolCallbacks()).extracting(c -> c.getToolDefinition().name()).containsExactly("readMultiFile");
    assertThat(actual.getToolContext()).containsOnlyKeys(AgentRunContext.class.getName());
    assertThat(configured.getToolCallbacks()).containsExactly(ambient);
    assertThat(configured.getToolContext()).containsExactlyEntriesOf(Map.of("ambient-owner", "parent"));
    assertThat(toolCalls).hasValue(0);
  }
  @Test void rawToolsCannotBypassTheChildToolAllowlist() throws Exception {
    var configured = org.springframework.ai.openai.OpenAiChatOptions.builder()
        .extraBody(Map.of("tools", List.of(Map.of("type", "function")))).build();
    var requests = new CopyOnWriteArrayList<Prompt>();
    var runner = runner(p -> { requests.add(p); return Flux.just(answer(SubAgentResultParserTest.VALID)); },
        "2s", () -> List.of(callback()), ignored -> configured);

    assertThat(runner.run("reviewer", "task", null).status()).isEqualTo(SubAgentResult.Status.FAILED);
    assertThat(requests).isEmpty();
    assertThat(toolCalls).hasValue(0);
  }
  @Test void maxStepsBoundsLlmCalls() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    var runner = runner(p -> { calls.incrementAndGet(); return Flux.just(tool("readMultiFile")); }, "2s");
    assertThat(runner.run("reviewer", "task", null).status()).isEqualTo(SubAgentResult.Status.MAX_STEPS_EXCEEDED);
    assertThat(calls.get()).isEqualTo(2);
  }
  @Test void timeoutDisposesActiveUpstream() throws Exception {
    CountDownLatch disposed = new CountDownLatch(1);
    var runner = runner(p -> Flux.<ChatResponse>never().doOnCancel(disposed::countDown), "30ms");
    assertThat(runner.run("reviewer", "task", null).status()).isEqualTo(SubAgentResult.Status.TIMEOUT);
    assertThat(disposed.await(1, TimeUnit.SECONDS)).isTrue();
  }
  @Test void parentCancellationStopsChildWithoutReplacingParentSubscription() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch disposed = new CountDownLatch(1);
    var runner = runner(p -> Flux.<ChatResponse>never().doOnSubscribe(s -> started.countDown()).doOnCancel(disposed::countDown), "5s");
    var parent = new AgentRunContext("parent", "chat:main", directory);
    AtomicReference<SubAgentResult> result = new AtomicReference<>();
    AtomicBoolean parentDisposed = new AtomicBoolean();
    try (var scope = AgentRunScope.open(parent)) {
      cancellation.begin(null);
      cancellation.register(() -> parentDisposed.set(true));
      Thread child = Thread.ofVirtual().start(() -> {
        try (var childScope = AgentRunScope.open(parent)) { result.set(runner.run("reviewer", "task", null)); }
      });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      cancellation.cancel();
      child.join(2000);
      assertThat(child.isAlive()).isFalse();
      assertThat(result.get().status()).isEqualTo(SubAgentResult.Status.CANCELLED);
      assertThat(disposed.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(parentDisposed).isTrue();
      cancellation.clear();
    }
  }
  @Test void unknownAgentFailureAndUnrequestedCallsFailClosed() throws Exception {
    var runner = runner(p -> Flux.just(tool("runCommand")), "2s");
    assertThat(runner.run("missing", "task", null).status()).isEqualTo(SubAgentResult.Status.UNKNOWN_AGENT);
    assertThat(runner.run("reviewer", "task", null).status()).isEqualTo(SubAgentResult.Status.FAILED);
    assertThat(toolCalls).hasValue(0);
    assertThat(events).anyMatch(e -> e.type() == AgentEventType.SUBAGENT_FAILED);
  }
  @Test void eventsDoNotCopySecretsOrUnboundedTask() throws Exception {
    var runner = runner(p -> Flux.error(new IllegalStateException("password=hidden")), "2s");
    runner.run("reviewer", "password=hidden " + "x".repeat(1000), null);
    assertThat(events.toString()).doesNotContain("hidden").doesNotContain("x".repeat(121));
  }
  @Test void timeoutInterruptsToolWorkerAndDoesNotStartNextIteration() throws Exception {
    CountDownLatch interrupted = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    ToolCallback blocking = new ToolCallback() {
      public ToolDefinition getToolDefinition() { return callback().getToolDefinition(); }
      public String call(String input) {
        try { new CountDownLatch(1).await(); }
        catch (InterruptedException error) { interrupted.countDown(); Thread.currentThread().interrupt(); }
        return "late result";
      }
    };
    var runner = runner(p -> { calls.incrementAndGet(); return Flux.just(tool("readMultiFile")); }, "500ms", () -> List.of(blocking));
    assertThat(runner.run("reviewer", "task", null).status()).isEqualTo(SubAgentResult.Status.TIMEOUT);
    assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(calls).hasValue(1);
  }
  @Test void siblingsHaveSeparateRunIdsAndIndividualCancellation() throws Exception {
    CountDownLatch started = new CountDownLatch(2);
    var runner = runner(p -> Flux.<ChatResponse>never().doOnSubscribe(s -> started.countDown()), "5s");
    var parent = new AgentRunContext("parent", "chat:main", directory);
    var results = new CopyOnWriteArrayList<SubAgentResult>();
    Runnable task = () -> { try (var scope = AgentRunScope.open(parent)) { results.add(runner.run("reviewer", "task", null)); } };
    var a = Thread.ofVirtual().start(task);
    var b = Thread.ofVirtual().start(task);
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    var ids = events.stream().filter(e -> e.type() == AgentEventType.SUBAGENT_STARTED).map(AgentEvent::runId).toList();
    assertThat(ids).hasSize(2).doesNotHaveDuplicates();
    assertThat(runner.cancel(ids.getFirst())).isTrue();
    assertThat(runner.cancel(ids.getLast())).isTrue();
    a.join(2000); b.join(2000);
    assertThat(results).hasSize(2).allMatch(result -> result.status() == SubAgentResult.Status.CANCELLED);
    assertThat(runner.cancel(ids.getFirst())).isFalse();
  }
  dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation(int limit,AtomicInteger used) {
    return new dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation() {
      public boolean tryReserve(){for(;;){int value=used.get();if(value>=limit)return false;if(used.compareAndSet(value,value+1))return true;}}
      public int remaining(){return Math.max(0,limit-used.get());}
    };
  }
  @Test void inheritedBudgetRejectsChildModelBeforeAnyInvocation() throws Exception {
    var calls=new AtomicInteger();var used=new AtomicInteger();
    var result=runner(p->{calls.incrementAndGet();return Flux.just(answer(SubAgentResultParserTest.VALID));},"2s").run("reviewer","task",null,reservation(0,used));
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.FAILED);assertThat(result.output()).contains("SHARED_LLM_BUDGET_EXHAUSTED");assertThat(calls).hasValue(0);assertThat(used).hasValue(0);
  }
  @Test void inheritedBudgetChargesRepairAndRetainsOriginalDiagnostics() throws Exception {
    maxRepairs=1;maxSteps=4;var calls=new AtomicInteger();var used=new AtomicInteger();
    var result=runner(p->{calls.incrementAndGet();return Flux.just(answer("{}"));},"2s").run("reviewer","task",null,reservation(1,used));
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.FAILED);assertThat(result.output()).contains("SHARED_LLM_BUDGET_EXHAUSTED");assertThat(calls).hasValue(1);assertThat(used).hasValue(1);assertThat(result.validationHistory()).hasSize(1);
  }
  @Test void actualParentChatDelegationChargesBothModelsToDurableGoalBudget() throws Exception {
    var childCalls=new AtomicInteger();var parentCalls=new AtomicInteger();
    var child=runner(p->{childCalls.incrementAndGet();return Flux.just(answer(SubAgentResultParserTest.VALID));},"2s");
    var registry=new SubAgentRegistry(directory,new SubAgentDefinitionLoader(policy,model->true));registry.reload();
    var delegate=new SubAgentTools(child,registry).callback();assertThat(delegate.getToolDefinition().inputSchema()).doesNotContain("toolContext","reservation");
    ChatModel parentModel=new ChatModel(){
      public ToolCallingChatOptions getOptions(){return ToolCallingChatOptions.builder().build();}
      public ChatResponse call(Prompt prompt){throw new UnsupportedOperationException();}
      public Flux<ChatResponse> stream(Prompt prompt){parentCalls.incrementAndGet();return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
          .toolCalls(List.of(new AssistantMessage.ToolCall("delegate","function","delegateTask","{\"agent\":\"reviewer\",\"task\":\"review\"}"))).build()))));}
    };
    var holder=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.service.ModelHolderService.class);org.mockito.Mockito.when(holder.get()).thenReturn("test");
    var client=org.springframework.ai.chat.client.ChatClient.builder(new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(parentModel))
        .defaultAdvisors(new dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor()).defaultToolCallbacks(delegate).build();
    var chat=new ChatExecutionService(new dev.mikoto2000.rei.llm.FixedLlmChatClientProvider(client),holder,new dev.mikoto2000.rei.llm.FixedLlmModelProvider(),new dev.mikoto2000.rei.llm.LlmProperties(),cancellation,Optional.empty(),Optional.empty());
    var goals=new dev.mikoto2000.rei.goal.GoalRepository(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("goals.db")),Clock.systemUTC());
    var goal=goals.create(new AgentRunContext("source","session",directory,"project"),"artifact","out.txt","a".repeat(64),3,2);var claim=goals.claim("project",goal.id());String run=goals.beginAttempt(claim);
    var reservation=new dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation(){public boolean tryReserve(){return goals.reserveLlm(claim);}public int remaining(){return goals.remainingLlm(claim);}};
    var result=chat.execute(new AgentRunContext(run,"session",directory,"project"),"review",new UserInterventionQueue(),reservation);
    assertThat(result.success()).isFalse();assertThat(parentCalls).hasValue(1);assertThat(childCalls).hasValue(1);assertThat(goals.get("project",goal.id()).llmCallsUsed()).isEqualTo(2);assertThat(goals.reserveLlm(claim)).isFalse();
  }
  @Test void parallelChildrenAtomicallyShareOneRemainingCall() throws Exception {
    var calls=new AtomicInteger();var used=new AtomicInteger();var child=runner(p->{calls.incrementAndGet();return Flux.just(answer(SubAgentResultParserTest.VALID));},"2s");
    var registry=new SubAgentRegistry(directory,new SubAgentDefinitionLoader(policy,model->true));registry.reload();
    try(var parallel=new ParallelSubAgentDelegator(child,registry,cancellation)) {
      var result=parallel.delegate(List.of(new ParallelSubAgentDelegator.Request("one","reviewer","one",null),new ParallelSubAgentDelegator.Request("two","reviewer","two",null)),reservation(1,used));
      assertThat(result.status()).isEqualTo(ParallelSubAgentDelegator.Status.PARTIAL);assertThat(calls).hasValue(1);assertThat(used).hasValue(1);
      assertThat(result.items().stream().filter(item->item.result().status()==SubAgentResult.Status.COMPLETED).count()).isEqualTo(1);
    }
  }
  @Test void inheritedBudgetChargesEveryChildToolCycleBeforeCallingModel() throws Exception {
    var calls=new AtomicInteger();var used=new AtomicInteger();
    var result=runner(p->{calls.incrementAndGet();return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call","function","readMultiFile","{}"))).build()))));},"2s").run("reviewer","task",null,reservation(1,used));
    assertThat(result.output()).contains("SHARED_LLM_BUDGET_EXHAUSTED");assertThat(calls).hasValue(1);assertThat(toolCalls).hasValue(1);assertThat(used).hasValue(1);
  }
  @Test void parallelToolContextUsesTheSameDurableGoalReservationAcrossWorkers() throws Exception {
    var calls=new AtomicInteger();var child=runner(p->{calls.incrementAndGet();return Flux.just(answer(SubAgentResultParserTest.VALID));},"2s");
    var registry=new SubAgentRegistry(directory,new SubAgentDefinitionLoader(policy,model->true));registry.reload();
    var goals=new dev.mikoto2000.rei.goal.GoalRepository(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("goals.db")),Clock.systemUTC());
    var goal=goals.create(new AgentRunContext("source","session",directory,"project"),"artifact","out.txt","a".repeat(64),3,1);var claim=goals.claim("project",goal.id());goals.beginAttempt(claim);
    var reservation=new dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation(){public boolean tryReserve(){return goals.reserveLlm(claim);}public int remaining(){return goals.remainingLlm(claim);}};
    var execution=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("parent",new dev.mikoto2000.rei.llm.OutputLimitRunBudget(2,5,reservation),null,new AgentEventFactory(Clock.systemUTC()),events::add);
    try(var parallel=new ParallelSubAgentDelegator(child,registry,cancellation)) {
      var tools=new SubAgentTools(child,registry);tools.parallelDelegator(parallel);
      assertThat(tools.callback("delegateTasks").getToolDefinition().inputSchema()).doesNotContain("toolContext","reservation");
      var result=tools.delegateTasks(List.of(new ParallelSubAgentDelegator.Request("one","reviewer","one",null),new ParallelSubAgentDelegator.Request("two","reviewer","two",null)),new ToolContext(Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY,execution)));
      assertThat(result.status()).isEqualTo(ParallelSubAgentDelegator.Status.PARTIAL);assertThat(calls).hasValue(1);assertThat(goals.get("project",goal.id()).llmCallsUsed()).isEqualTo(1);
    }
  }
}
