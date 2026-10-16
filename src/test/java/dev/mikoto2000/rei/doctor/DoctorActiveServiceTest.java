package dev.mikoto2000.rei.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.core.dependency.JavaHttpDependencyProbe;
import dev.mikoto2000.rei.core.policy.PermissionDecision;
import dev.mikoto2000.rei.core.policy.ToolPermissionException;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.externalagent.ClaudeCodeProperties;
import dev.mikoto2000.rei.externalagent.CodexProperties;
import dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner;
import dev.mikoto2000.rei.externalagent.ExternalAgentResult;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.voice.VoiceProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

class DoctorActiveServiceTest {
  @TempDir Path root;
  final MockEnvironment env = new MockEnvironment();
  final VoiceProperties voice = new VoiceProperties();
  final CodexProperties codex = new CodexProperties();
  final ClaudeCodeProperties claude = new ClaudeCodeProperties();
  final LlmModelProvider models = mock(LlmModelProvider.class);
  final JavaHttpDependencyProbe http = mock(JavaHttpDependencyProbe.class);
  final DoctorMicrophoneProbe microphone = mock(DoctorMicrophoneProbe.class);
  final ExternalAgentProcessRunner processes = mock(ExternalAgentProcessRunner.class);
  final ToolPermissionGuard guard = mock(ToolPermissionGuard.class);
  final AgentEventPublisher events = mock(AgentEventPublisher.class);

  RunExecutionContext run(int calls) {
    var run =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(0, calls),
            new ProgressEvaluator(root, null),
            new AgentEventFactory(Clock.systemUTC()),
            events);
    run.setRunContext(
        new AgentRunContext(
            "run", "session", root, "project", AgentRunContext.RequestSource.SHELL));
    run.setToolPermissionGuard(guard);
    return run;
  }

  DoctorActiveService service() {
    env.withProperty("spring.ai.openai.base-url", "http://127.0.0.1:9999");
    return new DoctorActiveService(
        new DoctorService(env, voice, codex, claude, new LlmProperties()),
        env,
        models,
        new ModelHolderService("selected"),
        http,
        codex,
        claude,
        voice,
        microphone,
        processes,
        Clock.systemUTC());
  }

  DoctorRequest request(String... checks) {
    var args = new ArrayList<String>();
    for (String c : checks) {
      args.add("--check");
      args.add(c);
    }
    return DoctorRequest.parse(args.toArray(String[]::new));
  }

  @Test
  void permissionFailureDoesNotExecuteAndOtherChecksContinue() {
    doThrow(new ToolPermissionException("doctorConnectivity", PermissionDecision.DENY))
        .when(guard)
        .check(eq("doctorConnectivity"), anyString(), any());
    when(processes.run(anyList(), any(), anyString(), any(), any(), anyInt(), any()))
        .thenReturn(
            new ExternalAgentProcessRunner.Output(
                ExternalAgentResult.Status.SUCCESS,
                "SECRET CLI OUTPUT",
                "SECRET STDERR",
                0,
                2,
                false));
    var report = service().active(run(1), request("connectivity", "codex"));
    assertEquals(DoctorResult.Status.SKIPPED, report.results().getFirst().status());
    assertEquals(DoctorResult.Status.OK, report.results().getLast().status());
    assertFalse(report.render().contains("SECRET"));
    verifyNoInteractions(http, models, microphone);
    verify(processes)
        .run(eq(List.of("codex", "--version")), eq(root), eq(""), any(), any(), eq(4096), any());
  }

  @Test
  void plannedEventsPrecedePermissionAndNetworkResultDoesNotClaimInference() {
    when(http.connectivity(anyString(), any(), any()))
        .thenReturn(
            new JavaHttpDependencyProbe.Connectivity(
                JavaHttpDependencyProbe.ConnectivityReason.RESPONSE, 401));
    var report = service().active(run(1), request("connectivity"));
    assertEquals(DoctorResult.Status.WARNING, report.results().getFirst().status());
    var order = inOrder(events, guard, http);
    order.verify(events).publishBoundary(argThat(e -> e.type() == AgentEventType.TOOL_PLANNED));
    order.verify(guard).check(eq("doctorConnectivity"), anyString(), any());
    order.verify(events).publishBoundary(argThat(e -> e.type() == AgentEventType.TOOL_STARTED));
    order.verify(http).connectivity(eq("http://127.0.0.1:9999/v1/models"), any(), any());
    verifyNoInteractions(models, microphone, processes);
  }

  @Test
  void inferenceIsOneBudgetedToolFreeFixedPromptWithoutResponseExposure() {
    var model = mock(ChatModel.class);
    when(models.subAgentChatModel()).thenReturn(model);
    when(model.stream(any(Prompt.class)))
        .thenAnswer(
            call -> {
              var prompt = (Prompt) call.getArgument(0);
              var options = (org.springframework.ai.openai.OpenAiChatOptions) prompt.getOptions();
              assertTrue(options.getToolCallbacks().isEmpty());
              assertEquals("none", options.getToolChoice());
              assertEquals("selected", options.getModel());
              assertTrue(prompt.getUserMessage().getText().length() < 100);
              return Flux.just(
                  new ChatResponse(
                      List.of(new Generation(new AssistantMessage("SECRET MODEL OUTPUT")))));
            });
    assertEquals(
        DoctorResult.Status.OK,
        service().active(run(1), request("inference")).results().getFirst().status());
    assertFalse(service().active(run(0), request("inference")).render().contains("SECRET"));
    verify(model, times(1)).stream(any(Prompt.class));
  }

  @Test
  void disabledAndCancelledRunsHaveNoSideEffects() {
    var service = service();
    env.withProperty("rei.doctor.enabled", "false");
    assertEquals(
        DoctorResult.Status.SKIPPED,
        service.active(run(1), request("connectivity")).results().getFirst().status());
    env.withProperty("rei.doctor.enabled", "true");
    var cancelled = run(1);
    cancelled.cancel();
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> service.active(cancelled, request("codex")));
    verifyNoInteractions(models, http, microphone, processes, guard);
  }

  @Test
  void cancellationStopsSilentModelStreamAndDoesNotStartLaterChecks() throws Exception {
    var model = mock(ChatModel.class);
    when(models.subAgentChatModel()).thenReturn(model);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var disposed = new java.util.concurrent.CountDownLatch(1);
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            Flux.<ChatResponse>never()
                .doOnSubscribe(s -> entered.countDown())
                .doOnCancel(disposed::countDown));
    var execution = run(1);
    var service = service();
    env.withProperty("rei.doctor.active-timeout", "5s");
    try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var pending = worker.submit(() -> service.active(execution, request("inference", "codex")));
      assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
      execution.cancel();
      var failure =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> pending.get(1, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(RunCancellation.isCancellation(failure));
      assertTrue(disposed.await(1, java.util.concurrent.TimeUnit.SECONDS));
      verifyNoInteractions(processes);
    }
  }

  @Test
  void credentialUrlIsRejectedBeforeNetworkAndNeverDisplayed() {
    var service = service();
    env.withProperty(
        "spring.ai.openai.base-url", "http://user:URL-SECRET@localhost/v1?token=QUERY-SECRET");
    var report = service.active(run(1), request("connectivity"));
    assertEquals(DoctorResult.Status.ERROR, report.results().getFirst().status());
    assertFalse(report.render().contains("SECRET"));
    verifyNoInteractions(http);
  }

  @Test
  void inferenceOptionsAreCapturedBeforePermissionAndApprovalInputContainsOnlyFingerprint() {
    var configured =
        new java.util.concurrent.atomic.AtomicReference<>(
            org.springframework.ai.openai.OpenAiChatOptions.builder()
                .model("before")
                .apiKey("CONFIG-SECRET")
                .baseUrl("http://localhost/v1")
                .build());
    when(models.chatOptions(anyString(), anyString())).thenAnswer(call -> configured.get());
    doAnswer(
            call -> {
              assertFalse(((String) call.getArgument(1)).contains("SECRET"));
              configured.set(
                  org.springframework.ai.openai.OpenAiChatOptions.builder().model("after").build());
              return null;
            })
        .when(guard)
        .check(eq("doctorInference"), anyString(), any());
    var model = mock(ChatModel.class);
    when(models.subAgentChatModel()).thenReturn(model);
    when(model.stream(any(Prompt.class)))
        .thenAnswer(
            call -> {
              var options =
                  (org.springframework.ai.openai.OpenAiChatOptions)
                      ((Prompt) call.getArgument(0)).getOptions();
              assertEquals("before", options.getModel());
              return Flux.just(
                  new ChatResponse(List.of(new Generation(new AssistantMessage("OK")))));
            });
    assertEquals(
        DoctorResult.Status.OK,
        service().active(run(1), request("inference")).results().getFirst().status());
  }
}
