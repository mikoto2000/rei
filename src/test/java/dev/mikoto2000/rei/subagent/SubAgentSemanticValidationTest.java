package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

class SubAgentSemanticValidationTest {
  @TempDir Path directory;
  SubAgentRunnerTest fixture(int steps) {
    var fixture=new SubAgentRunnerTest(); fixture.directory=directory;
    fixture.evidenceValidation=true; fixture.maxSteps=steps;
    fixture.requiredCallsConfiguration="semanticValidation: true\n";
    return fixture;
  }
  @Test void judgePreservesModelOptionsAndIsolatesToolsWithoutMutatingOriginal() {
    var owner = new dev.mikoto2000.rei.core.chat.AgentRunContext("judge", "session", directory);
    var callback = org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
    var originalOptions = org.springframework.ai.openai.OpenAiChatOptions.builder()
        .model("judge-model").temperature(0.1).maxCompletionTokens(234)
        .toolChoice("auto")
        .toolCallbacks(List.of(callback)).toolContext(Map.of("parent-private", "value")).build();
    var original = new org.springframework.ai.chat.prompt.Prompt("inspect evidence", originalOptions);
    var definition = new SubAgentDefinition("reviewer", "Reviewer", "Review", "Inspect", List.of(),
        null, 3, java.time.Duration.ofSeconds(2), directory);
    var captured = new java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.prompt.Prompt>();
    ChatModel model = new ChatModel() {
      @Override public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        throw new AssertionError("Expected streaming");
      }
      @Override public Flux<ChatResponse> stream(org.springframework.ai.chat.prompt.Prompt prompt) {
        captured.set(prompt);
        return Flux.just(new ChatResponse(List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage(
            "{\"valid\":true,\"issues\":[]}")))));
      }
    };
    new SubAgentSemanticValidator().validate(model, original, definition, "answer", new SubAgentEvidence(),
        new AtomicInteger(2), owner, () -> {}, null).block();
    var options = (org.springframework.ai.openai.OpenAiChatOptions) captured.get().getOptions();
    assertThat(options.getModel()).isEqualTo("judge-model");
    assertThat(options.getTemperature()).isEqualTo(0.1);
    assertThat(options.getMaxCompletionTokens()).isEqualTo(234);
    assertThat(((org.springframework.ai.openai.OpenAiChatOptions) options).getToolChoice()).isEqualTo("none");
    assertThat(options.getToolCallbacks()).isEmpty();
    assertThat(options.getToolContext()).containsOnlyKeys(dev.mikoto2000.rei.core.chat.AgentRunContext.class.getName());
    assertThat(options.getToolContext().get(dev.mikoto2000.rei.core.chat.AgentRunContext.class.getName())).isSameAs(owner);
    assertThat(originalOptions.getToolChoice()).isEqualTo("auto");
    assertThat(originalOptions.getToolCallbacks()).containsExactly(callback);
    assertThat(originalOptions.getToolContext()).containsOnlyKeys("parent-private");
  }
  @Test void independentJudgeRejectsUnsupportedAnswerAndRepairRetainsDiagnosis() throws Exception {
    var fixture=fixture(4);fixture.maxRepairs=1;
    var calls=new AtomicInteger();
    var runner=fixture.runner(prompt->{
      int call=calls.incrementAndGet();
      if(call==2||call==4) {
        assertThat(prompt.getInstructions().getFirst().getText()).contains("independent semantic validator");
        assertThat(((ToolCallingChatOptions)prompt.getOptions()).getToolCallbacks()).isEmpty();
        assertThat(prompt.getInstructions()).hasSize(2);
        return Flux.just(fixture.answer(call==2
            ? "{\"valid\":false,\"issues\":[\"UNSUPPORTED_CLAIM\"]}"
            : "{\"valid\":true,\"issues\":[]}"));
      }
      return Flux.just(fixture.answer("{\"status\":\"PARTIAL\",\"summary\":\"bounded answer\",\"result\":{\"evidence\":[]},\"warnings\":[]}"));
    },"2s");
    var result=runner.run("reviewer","inspect requested file",null);
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.COMPLETED);
    assertThat(result.validationHistory()).hasSize(1);
    assertThat(result.validationHistory().getFirst().toString()).contains("UNSUPPORTED_CLAIM");
    assertThat(result.repairAttempts()).isEqualTo(1);
    assertThat(calls).hasValue(4);
  }
  @Test void judgeCannotBypassSharedBudgetOrStepLimit() throws Exception {
    for(boolean shared:List.of(false,true)) {
      var fixture=fixture(shared?5:1);var calls=new AtomicInteger();
      var runner=fixture.runner(prompt->{calls.incrementAndGet();return Flux.just(fixture.answer(
          "{\"status\":\"PARTIAL\",\"summary\":\"unknown\",\"result\":{\"evidence\":[]},\"warnings\":[]}"));},"2s");
      var parent=new dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation(){
        int remaining=1;public boolean tryReserve(){if(remaining==0)return false;remaining--;return true;}
        public int remaining(){return remaining;}
      };
      var result=runner.run("reviewer","inspect",null,shared?parent:null);
      assertThat(result.status()).isEqualTo(shared?SubAgentResult.Status.FAILED:SubAgentResult.Status.MAX_STEPS_EXCEEDED);
      assertThat(calls).hasValue(1);
    }
  }
  @Test void malformedVerdictsFailWithoutExposingModelText() throws Exception {
    for(String verdict:List.of("private-secret", "{\"valid\":true,\"issues\":[\"CONTRADICTION\"]}",
        "{\"valid\":false,\"issues\":[]}","{\"valid\":false,\"issues\":[\"private-secret\"]}",
        "{\"valid\":true,\"issues\":[],\"extra\":\"private-secret\"}")) {
      var fixture=fixture(2);var calls=new AtomicInteger();
      var result=fixture.runner(prompt->Flux.just(fixture.answer(calls.incrementAndGet()==1
          ? "{\"status\":\"PARTIAL\",\"summary\":\"unknown\",\"result\":{\"evidence\":[]},\"warnings\":[]}"
          : verdict)),"2s").run("reviewer","inspect",null);
      assertThat(result.status()).isEqualTo(SubAgentResult.Status.FAILED);
      assertThat(result.validationErrors().toString()).doesNotContain("private-secret");
      assertThat(result.output()).doesNotContain("private-secret");
      assertThat(calls).hasValue(2);
    }
  }
  @Test void judgeReceivesRunnerObservationsAndCanRejectContradiction() throws Exception {
    var fixture=fixture(3);var calls=new AtomicInteger();
    var result=fixture.runner(prompt->{
      int call=calls.incrementAndGet();
      if(call==1)return Flux.just(fixture.tool("readMultiFile"));
      if(call==3) {
        var input=new SubAgentResultParser().parse(prompt.getUserMessage().getText());
        var observations=new SubAgentResultParser().parse(input.get("observations").asString());
        assertThat(observations.size()).isEqualTo(1);
        var observed=observations.properties().iterator().next().getValue();
        assertThat(observed.get("output").asString()).isEqualTo("private intermediate result");
        assertThat(observed.get("tool").asString()).isEqualTo("readMultiFile");
        assertThat(input.get("task").asString()).isEqualTo("inspect requested file");
        return Flux.just(fixture.answer("{\"valid\":false,\"issues\":[\"CONTRADICTION\"]}"));
      }
      var response=prompt.getInstructions().stream().filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
          .map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast).findFirst().orElseThrow().getResponses().getFirst().responseData();
      var receipt=new SubAgentResultParser().parse(response);
      var claim=Map.of("evidenceId",receipt.get("evidenceId").asString(),"tool","readMultiFile",
          "outputSha256",receipt.get("outputSha256").asString(),"quote","private intermediate result");
      return Flux.just(fixture.answer(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
          "status","SUCCESS","summary","claimed completion","result",Map.of("evidence",List.of(claim)),"warnings",List.of()))));
    },"2s").run("reviewer","inspect requested file",null);
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.FAILED);
    assertThat(result.validationErrors().toString()).contains("CONTRADICTION").doesNotContain("private intermediate result");
    assertThat(calls).hasValue(3);
  }
  @Test void cancellationDuringJudgeDisposesStreamingAndCannotComplete() throws Exception {
    var fixture=fixture(2);var calls=new AtomicInteger();
    var entered=new java.util.concurrent.CountDownLatch(1);var disposed=new java.util.concurrent.CountDownLatch(1);
    var runner=fixture.runner(prompt->calls.incrementAndGet()==1 ? Flux.just(fixture.answer(
        "{\"status\":\"PARTIAL\",\"summary\":\"unknown\",\"result\":{\"evidence\":[]},\"warnings\":[]}"))
        : Flux.<ChatResponse>never().doOnSubscribe(subscription->entered.countDown()).doOnCancel(disposed::countDown),"2s");
    try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var future=executor.submit(()->runner.run("reviewer","inspect",null));
      assertThat(entered.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      String id=fixture.events.stream().filter(event->event.type()==dev.mikoto2000.rei.event.AgentEventType.SUBAGENT_STARTED).findFirst().orElseThrow().runId();
      assertThat(runner.cancel(id)).isTrue();
      assertThat(future.get(1,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(SubAgentResult.Status.CANCELLED);
      assertThat(disposed.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
    assertThat(fixture.events).noneMatch(event->event.type()==dev.mikoto2000.rei.event.AgentEventType.SUBAGENT_COMPLETED);
  }
  @Test void judgeSharesOriginalDeadlineAndCannotHangRun() throws Exception {
    var fixture=fixture(2);var calls=new AtomicInteger();var disposed=new java.util.concurrent.CountDownLatch(1);
    var result=fixture.runner(prompt->calls.incrementAndGet()==1 ? Flux.just(fixture.answer(
        "{\"status\":\"PARTIAL\",\"summary\":\"unknown\",\"result\":{\"evidence\":[]},\"warnings\":[]}"))
        : Flux.<ChatResponse>never().doOnCancel(disposed::countDown),"300ms").run("reviewer","inspect",null);
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.TIMEOUT);
    assertThat(disposed.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(calls).hasValue(2);
  }
  @Test void oversizedSemanticInputFailsBeforeCallingJudge() throws Exception {
    var fixture=fixture(2);var calls=new AtomicInteger();
    String raw=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
        "status","PARTIAL","summary","unknown","result",Map.of("evidence",List.of(),"large","x".repeat(140000)),"warnings",List.of()));
    var result=fixture.runner(prompt->{calls.incrementAndGet();return Flux.just(fixture.answer(raw));},"2s")
        .run("reviewer","inspect",null);
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.FAILED);
    assertThat(result.validationErrors().toString()).contains("Semantic input exceeds bounded limit");
    assertThat(calls).hasValue(1);
    var ledger=new SubAgentEvidence();
    for(int index=0;index<5;index++)ledger.capture("readMultiFile","{}","x".repeat(14000));
    assertThatThrownBy(ledger::semanticSnapshot).isInstanceOf(SubAgentValidationException.class);
  }
}
