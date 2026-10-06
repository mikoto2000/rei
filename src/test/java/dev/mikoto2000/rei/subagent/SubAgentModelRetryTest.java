package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.*;
import com.openai.core.http.Headers;
import com.openai.errors.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;

@Tag("integration")
class SubAgentModelRetryTest {
  private static final Headers EMPTY_HEADERS = Headers.builder().build();
  @TempDir Path dir;
  SubAgentRunnerTest fixture(){var f=new SubAgentRunnerTest();f.directory=dir;return f;}
  void configure(SubAgentRunner runner,int retries){var props=new SubAgentProperties();props.setMaxTransientModelRetries(retries);runner.setStandaloneBudgetProperties(props);}
  static Stream<Throwable> transientSdkFailures() {
    return Stream.<Throwable>of(
        RateLimitException.builder().headers(EMPTY_HEADERS).build(),
        InternalServerException.builder().headers(EMPTY_HEADERS).statusCode(500).build(),
        InternalServerException.builder().headers(EMPTY_HEADERS).statusCode(503).build(),
        UnexpectedStatusCodeException.builder().headers(EMPTY_HEADERS).statusCode(408).build(),
        UnexpectedStatusCodeException.builder().headers(EMPTY_HEADERS).statusCode(409).build(),
        UnexpectedStatusCodeException.builder().statusCode(418).headers(Headers.builder().put("X-Should-Retry", "true").build()).build(),
        new OpenAIIoException("secret provider diagnostics", new java.net.ConnectException("connection refused")),
        new OpenAIIoException("secret provider diagnostics", new java.net.SocketTimeoutException("read timed out")),
        new OpenAIRetryableException("secret provider diagnostics"))
        .flatMap(error -> Stream.of(error, new CompletionException(error), new ExecutionException(error)));
  }
  static Stream<Throwable> permanentSdkFailures() {
    return Stream.<Throwable>of(
        BadRequestException.builder().headers(EMPTY_HEADERS).build(),
        UnauthorizedException.builder().headers(EMPTY_HEADERS).build(),
        PermissionDeniedException.builder().headers(EMPTY_HEADERS).build(),
        NotFoundException.builder().headers(EMPTY_HEADERS).build(),
        UnprocessableEntityException.builder().headers(EMPTY_HEADERS).build(),
        UnexpectedStatusCodeException.builder().headers(EMPTY_HEADERS).statusCode(418).build(),
        RateLimitException.builder().headers(Headers.builder().put("X-Should-Retry", "false").build()).build(),
        new OpenAIInvalidDataException("malformed response"),
        new OpenAIException("unclassified provider failure"),
        new IllegalArgumentException("application failure", RateLimitException.builder().headers(EMPTY_HEADERS).build()))
        .flatMap(error -> Stream.of(error, new CompletionException(error)));
  }
  @ParameterizedTest @MethodSource("transientSdkFailures")
  void optInTransientFailureBeforeAnyChunkRetriesTheSamePromptWithinSharedSteps(Throwable failure) throws Exception {
    var f=fixture();var calls=new AtomicInteger();var original=new java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.prompt.Prompt>();var runner=f.runner(prompt->{if(calls.incrementAndGet()==1){original.set(prompt);return Flux.error(failure);}assertSame(original.get(),prompt);return Flux.just(f.answer(SubAgentResultParserTest.VALID));},"2s");configure(runner,1);var result=runner.run("reviewer","task",null);
    assertEquals(SubAgentResult.Status.COMPLETED,result.status());assertEquals(2,calls.get());assertEquals(1,result.modelRetryAttempts());assertEquals(List.of("TRANSIENT_MODEL_FAILURE_BEFORE_RESPONSE"),result.modelRetryHistory());assertFalse(result.toString().contains("secret provider diagnostics"));assertEquals(0,result.repairAttempts());
  }
  @ParameterizedTest @MethodSource("permanentSdkFailures")
  void permanentSdkFailuresNeverConsumeTheModelRetryAllowance(Throwable failure) throws Exception {
    var f=fixture();var calls=new AtomicInteger();var runner=f.runner(prompt->{calls.incrementAndGet();return Flux.error(failure);},"2s");configure(runner,3);var result=runner.run("reviewer","task",null);
    assertEquals(SubAgentResult.Status.FAILED,result.status());assertEquals(1,calls.get());assertEquals(0,result.modelRetryAttempts());assertTrue(result.modelRetryHistory().isEmpty());
  }
  @ParameterizedTest @MethodSource("transientSdkFailures")
  void sdkFailureAfterAnyChunkIsRecordedWithoutRetry(Throwable failure) throws Exception {
    var f=fixture();var calls=new AtomicInteger();var runner=f.runner(prompt->{calls.incrementAndGet();return Flux.concat(Flux.just(f.answer("partial")),Flux.error(failure));},"2s");configure(runner,3);var result=runner.run("reviewer","task",null);
    assertEquals(SubAgentResult.Status.FAILED,result.status());assertEquals(1,calls.get());assertEquals(0,result.modelRetryAttempts());assertEquals(List.of("TRANSIENT_MODEL_FAILURE_AFTER_PARTIAL_RESPONSE"),result.modelRetryHistory());
  }
  @Test void defaultsPartialResponsesAndNonTransientFailuresAreNeverRetried() throws Exception {
    var f=fixture();var calls=new AtomicInteger();var defaultRunner=f.runner(prompt->{calls.incrementAndGet();return Flux.error(new OpenAIIoException("failure"));},"2s");assertEquals(SubAgentResult.Status.FAILED,defaultRunner.run("reviewer","task",null).status());assertEquals(1,calls.get());calls.set(0);
    var partial=f.runner(prompt->{calls.incrementAndGet();return Flux.concat(Flux.just(f.answer("partial")),Flux.error(new OpenAIIoException("failure")));},"2s");configure(partial,3);assertEquals(SubAgentResult.Status.FAILED,partial.run("reviewer","task",null).status());assertEquals(1,calls.get());calls.set(0);
    var permanent=f.runner(prompt->{calls.incrementAndGet();return Flux.error(new IllegalArgumentException("permanent"));},"2s");configure(permanent,3);assertEquals(SubAgentResult.Status.FAILED,permanent.run("reviewer","task",null).status());assertEquals(1,calls.get());
  }
  @Test void retriesShareStepAndCallBudgetsAndUnknownUsageStopsBeforeASecondCall() throws Exception {
    var f=fixture();var calls=new AtomicInteger();var stream=(java.util.function.Function<org.springframework.ai.chat.prompt.Prompt,Flux<org.springframework.ai.chat.model.ChatResponse>>)prompt->{calls.incrementAndGet();return Flux.error(new OpenAIIoException("failure"));};
    f.maxSteps=1;var steps=f.runner(stream,"2s");configure(steps,3);var exhausted=steps.run("reviewer","task",null);assertEquals(SubAgentResult.Status.MAX_STEPS_EXCEEDED,exhausted.status());assertEquals(1,calls.get());assertEquals(0,exhausted.modelRetryAttempts());
    f.maxSteps=4;calls.set(0);var bounded=f.runner(stream,"2s");var props=new SubAgentProperties();props.setMaxTransientModelRetries(3);props.setStandaloneMaxLlmCalls(1);bounded.setStandaloneBudgetProperties(props);var stopped=bounded.run("reviewer","task",null);assertEquals(1,calls.get());assertEquals(0,stopped.modelRetryAttempts());assertTrue(stopped.output().contains("SHARED_LLM_BUDGET_EXHAUSTED"));
    calls.set(0);props.setStandaloneMaxLlmCalls(0);props.setStandaloneMaxTotalTokens(5);var unknown=bounded.run("reviewer","task",null);assertEquals(1,calls.get());assertEquals(0,unknown.modelRetryAttempts());assertTrue(unknown.output().contains("TOKEN_USAGE_UNKNOWN"));
  }
  @Test void retryLimitIsPerInvocationAndTimeoutIncludesBackoff() throws Exception {
    var f=fixture();f.maxSteps=8;var calls=new AtomicInteger();var runner=f.runner(prompt->{calls.incrementAndGet();return Flux.error(new OpenAIIoException("failure"));},"2s");configure(runner,2);var result=runner.run("reviewer","task",null);assertEquals(SubAgentResult.Status.FAILED,result.status());assertEquals(3,calls.get());assertEquals(2,result.modelRetryAttempts());assertEquals(3,result.modelRetryHistory().size());
    calls.set(0);var timeout=f.runner(prompt->{calls.incrementAndGet();return Flux.error(new OpenAIIoException("failure"));},"50ms");configure(timeout,3);var timed=timeout.run("reviewer","task",null);assertEquals(SubAgentResult.Status.TIMEOUT,timed.status());assertTrue(calls.get()<=1);assertEquals(0,timed.modelRetryAttempts());
  }
  @Test void transientFailureAfterACompletedToolDoesNotReplayTheToolOrItsReceipt() throws Exception {
    var f=fixture();f.maxSteps=3;var calls=new AtomicInteger();var runner=f.runner(prompt->{int call=calls.incrementAndGet();if(call==1)return Flux.just(new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("tool-id","function","readMultiFile","{}"))).build()))));assertTrue(prompt.getInstructions().stream().anyMatch(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance));return call==2?Flux.error(new OpenAIIoException("temporary")):Flux.just(f.answer(SubAgentResultParserTest.VALID));},"2s");configure(runner,1);var result=runner.run("reviewer","task",null);assertEquals(SubAgentResult.Status.COMPLETED,result.status());assertEquals(3,calls.get());assertEquals(1,f.toolCalls.get());assertEquals(1,result.modelRetryAttempts());
  }
  @Test void toolFailureCannotConsumeTheModelRetryAllowanceOrReplayAnAction() throws Exception {
    var f=fixture();var invoked=new AtomicInteger();var calls=new AtomicInteger();var callback=new org.springframework.ai.tool.ToolCallback(){public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition(){return org.springframework.ai.tool.definition.ToolDefinition.builder().name("readMultiFile").description("fixture").inputSchema("{}").build();}public String call(String input){invoked.incrementAndGet();throw new OpenAIIoException("Tool transport failure");}};
    var runner=f.runner(prompt->{if(calls.incrementAndGet()==1)return Flux.just(new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("tool-id","function","readMultiFile","{}"))).build()))));return Flux.just(f.answer(SubAgentResultParserTest.VALID));},"2s",()->List.of(callback));configure(runner,3);var result=runner.run("reviewer","task",null);assertEquals(1,invoked.get());assertEquals(0,result.modelRetryAttempts());assertTrue(result.modelRetryHistory().isEmpty());
  }
  @Test void repairAndSemanticJudgeShareTheSameInvocationWideRetryLimit() throws Exception {
    var f=fixture();f.maxSteps=6;f.maxRepairs=1;var calls=new AtomicInteger();var runner=f.runner(prompt->{int call=calls.incrementAndGet();return call==2?Flux.just(f.answer("{}")):Flux.error(new OpenAIIoException("temporary"));},"2s");configure(runner,1);var repaired=runner.run("reviewer","task",null);assertEquals(SubAgentResult.Status.FAILED,repaired.status());assertEquals(3,calls.get());assertEquals(1,repaired.modelRetryAttempts());assertEquals(1,repaired.repairAttempts());assertEquals(1,repaired.validationHistory().size());
    f.maxRepairs=0;f.evidenceValidation=true;f.requiredCallsConfiguration="semanticValidation: true\n";calls.set(0);var judged=f.runner(prompt->{int call=calls.incrementAndGet();if(call==1)return Flux.just(f.answer("{\"status\":\"PARTIAL\",\"summary\":\"unknown\",\"result\":{\"evidence\":[]},\"warnings\":[]}"));assertTrue(prompt.getInstructions().getFirst().getText().contains("independent semantic validator"));return call==2?Flux.error(new OpenAIIoException("temporary")):Flux.just(f.answer("{\"valid\":true,\"issues\":[]}"));},"2s");configure(judged,1);var result=judged.run("reviewer","task",null);assertEquals(SubAgentResult.Status.COMPLETED,result.status());assertEquals(3,calls.get());assertEquals(1,result.modelRetryAttempts());
  }
  @Test void explicitChildCancellationInterruptsPendingBackoffBeforeRetry() throws Exception {
    var f=fixture();var calls=new AtomicInteger();var failed=new java.util.concurrent.CountDownLatch(1);var result=new java.util.concurrent.atomic.AtomicReference<SubAgentResult>();var runner=f.runner(prompt->{calls.incrementAndGet();return Flux.<org.springframework.ai.chat.model.ChatResponse>error(new OpenAIIoException("temporary")).doOnError(error->failed.countDown());},"2s");configure(runner,3);var thread=Thread.ofPlatform().start(()->result.set(runner.run("reviewer","task",null)));assertTrue(failed.await(1,java.util.concurrent.TimeUnit.SECONDS));String id=f.events.stream().filter(e->e.payload() instanceof dev.mikoto2000.rei.event.SubAgentLifecyclePayload).map(e->((dev.mikoto2000.rei.event.SubAgentLifecyclePayload)e.payload()).subAgentRunId()).findFirst().orElseThrow();assertTrue(runner.cancel(id));thread.join(2000);assertFalse(thread.isAlive());assertEquals(SubAgentResult.Status.CANCELLED,result.get().status());assertEquals(1,calls.get());assertEquals(0,result.get().modelRetryAttempts());
  }
  @Test void configurationBindsExplicitlyAndOldResultConstructorsPreserveZeroRetries() {
    var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of("rei.subagents.max-transient-model-retries","2"));var bound=new org.springframework.boot.context.properties.bind.Binder(source).bind("rei.subagents",org.springframework.boot.context.properties.bind.Bindable.of(SubAgentProperties.class)).get();assertEquals(2,bound.getMaxTransientModelRetries());assertEquals(0,new SubAgentProperties().getMaxTransientModelRetries());assertThrows(IllegalArgumentException.class,()->bound.setMaxTransientModelRetries(4));var legacy=new SubAgentResult("reviewer","run",SubAgentResult.Status.FAILED,"stopped",java.time.Instant.EPOCH,java.time.Instant.EPOCH);assertEquals(0,legacy.modelRetryAttempts());assertTrue(legacy.modelRetryHistory().isEmpty());
  }
}
