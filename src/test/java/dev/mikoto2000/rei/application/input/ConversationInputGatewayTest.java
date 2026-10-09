package dev.mikoto2000.rei.application.input;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;

class ConversationInputGatewayTest {
  @TempDir Path temp;
  final Clock clock = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);
  ProjectService projects;
  SessionLifecycle lifecycle;
  ShellConversationService shell;
  ConversationInputRouter router;
  final List<Runnable> tasks = new ArrayList<>();
  final List<String> executed = new ArrayList<>();
  final List<AgentRunContext> owners = new ArrayList<>();
  @BeforeEach void setup() {
    projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    lifecycle = new SessionLifecycle(new FileSessionRepository(temp.resolve("sessions.json")), clock);
    router = new ConversationInputRouter(tasks::add, (owner, prompt, queue) -> {
      owners.add(owner); executed.add(prompt);
    });
    shell = new ShellConversationService(projects, lifecycle, router::submit, false, router, clock);
  }
  ConversationInput voice(ConversationTarget target, String text) {
    return new ConversationInput(UUID.randomUUID(), InputSource.VOICE, target, text, clock.instant());
  }
  @Test void keyboardAndMockVoiceUseSameSessionRunnerAndShellAuthority() {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      shell.submit("keyboard");
      target = shell.captureTarget();
    }
    var input = voice(target, "voice");
    var accepted = shell.submit(input);
    assertThat(shell.submit(input)).isEqualTo(accepted);
    assertThat(tasks).hasSize(1);
    tasks.removeFirst().run(); tasks.removeFirst().run();
    assertThat(executed).containsExactly("keyboard", "voice");
    assertThat(owners).extracting(AgentRunContext::conversationId).containsOnly(target.sessionId());
    assertThat(owners).extracting(AgentRunContext::requestSource).containsOnly(AgentRunContext.RequestSource.SHELL);
    assertThat(owners).extracting(AgentRunContext::mode).containsOnly(AgentRunContext.Mode.EXCLUSIVE);
    assertThat(owners).extracting(AgentRunContext::voiceInput).containsExactly(false,true);
  }
  @Test void capturedSessionDoesNotFollowLaterSelectionOrWorkerThreadLocal() {
    ConversationTarget old;
    try (var scope = projects.newClient().open()) {
      old = shell.captureTarget();
      shell.newConversation();
      assertThat(shell.currentSessionId()).isNotEqualTo(old.sessionId());
    }
    var accepted = shell.submit(voice(old, "old session"));
    assertThat(accepted.conversationId()).isEqualTo(old.sessionId());
    tasks.removeFirst().run();
  }
  @Test void voiceCannotExecuteSlashCommandsEvenWithLeadingWhitespace() {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) { target = shell.captureTarget(); }
    assertThatThrownBy(() -> shell.submit(voice(target, "  /voice off")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(tasks).isEmpty();
  }
  @Test void voicePendingIsBoundedAndCancellationReleasesCapacity() {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      shell.submit("blocking keyboard");
      target = shell.captureTarget();
    }
    var one = voice(target, "one"); var two = voice(target, "two"); var three = voice(target, "three");
    shell.submit(one); shell.submit(two); shell.submit(three);
    assertThat(shell.pending(target)).extracting(ConversationInput::inputId)
        .containsExactly(one.inputId(), two.inputId(), three.inputId());
    var four = voice(target, "four");
    assertThatThrownBy(() -> shell.submit(four)).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    assertThat(shell.cancelPending(target, two.inputId())).isTrue();
    assertThat(shell.cancelPending(target, two.inputId())).isFalse();
    shell.submit(four);
    while (!tasks.isEmpty()) tasks.removeFirst().run();
    assertThat(executed).containsExactly("blocking keyboard", "one", "three", "four");
    assertThat(shell.pending(target)).isEmpty();
    assertThat(shell.submit(two).runId()).isNotBlank(); // cancelled ID remains deduplicated
    assertThat(tasks).isEmpty();
  }
  @Test void cancellationCannotCrossSessionBoundary() {
    ConversationTarget first, second;
    try (var scope = projects.newClient().open()) {
      shell.submit("block"); first = shell.captureTarget();
      shell.newConversation(); second = shell.captureTarget();
    }
    var input = voice(first, "pending"); shell.submit(input);
    assertThat(shell.cancelPending(second, input.inputId())).isFalse();
    assertThat(shell.pending(first)).containsExactly(input);
  }
  @Test void reusedIdWithChangedPayloadIsRejected() {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) { target = shell.captureTarget(); }
    var input = voice(target, "first"); shell.submit(input);
    assertThatThrownBy(() -> shell.submit(new ConversationInput(input.inputId(), InputSource.VOICE,
        target, "changed", input.createdAt()))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void admissionFailureCanBeRetriedWithoutPoisoningId() {
    var tries = new java.util.concurrent.atomic.AtomicInteger();
    var gateway = new ConversationInputGateway(lifecycle, (owner, prompt) -> {
      if (tries.getAndIncrement() == 0) throw new java.util.concurrent.RejectedExecutionException();
      router.submit(owner, prompt);
    }, router, clock);
    ConversationTarget target;
    try (var scope = projects.newClient().open()) { target = shell.captureTarget(); }
    var input = voice(target, "retry");
    assertThatThrownBy(() -> gateway.submit(input, AgentRunContext.Mode.EXCLUSIVE))
        .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    assertThat(gateway.submit(input, AgentRunContext.Mode.EXCLUSIVE)).isNotNull();
    assertThat(tasks).hasSize(1);
  }
  @Test void foreignProjectSessionIsRejectedBeforeDispatch() {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) { target = shell.captureTarget(); }
    var foreign = new ConversationTarget(new ProjectContext(UUID.randomUUID().toString(), "foreign", temp), target.sessionId());
    assertThatThrownBy(() -> shell.submit(voice(foreign, "wrong owner")))
        .isInstanceOf(dev.mikoto2000.rei.application.run.SessionConflictException.class);
    assertThat(tasks).isEmpty();
  }
  @Test void ledgerIsBoundedAndNeverEvictsActiveInputsEvenAfterRetention() {
    var mutableClock=org.mockito.Mockito.mock(Clock.class);
    org.mockito.Mockito.when(mutableClock.instant()).thenReturn(clock.instant());
    var gateway=new ConversationInputGateway(lifecycle,router::submit,router,mutableClock,2,Duration.ofMinutes(30));
    ConversationTarget target;
    try(var scope=projects.newClient().open()){target=shell.captureTarget();}
    var first=voice(target,"first");var second=voice(target,"second");var third=voice(target,"third");
    gateway.submit(first,AgentRunContext.Mode.EXCLUSIVE);gateway.submit(second,AgentRunContext.Mode.EXCLUSIVE);
    org.mockito.Mockito.when(mutableClock.instant()).thenReturn(clock.instant().plusSeconds(1801));
    assertThat(gateway.submit(first,AgentRunContext.Mode.EXCLUSIVE).conversationId()).isEqualTo(target.sessionId());
    assertThatThrownBy(()->gateway.submit(third,AgentRunContext.Mode.EXCLUSIVE))
        .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    while(!tasks.isEmpty())tasks.removeFirst().run();
    gateway.submit(new ConversationInput(third.inputId(),third.source(),third.target(),third.text(),mutableClock.instant()),AgentRunContext.Mode.EXCLUSIVE);
    assertThat(tasks).hasSize(1);
  }
  @Test void concurrentDuplicateEventsHaveOnlyOneAdmission() throws Exception {
    ConversationTarget target;
    try(var scope=projects.newClient().open()){target=shell.captureTarget();}
    var input=voice(target,"once");
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(4)){
      var calls=new ArrayList<java.util.concurrent.Future<AgentRunContext>>();
      for(int i=0;i<8;i++)calls.add(executor.submit(()->shell.submit(input)));
      var owner=calls.getFirst().get();
      for(var call:calls)assertThat(call.get()).isEqualTo(owner);
    }
    assertThat(tasks).hasSize(1);
  }
  @Test void mockVoiceAndKeyboardKeepToolApprovalRequired() {
    ConversationTarget target;
    try(var scope=projects.newClient().open()){shell.submit("keyboard");target=shell.captureTarget();}
    var voiceOwner=shell.submit(voice(target,"voice"));
    tasks.removeFirst().run();tasks.removeFirst().run();
    var guard=new dev.mikoto2000.rei.core.policy.ToolPermissionGuard(
        new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
            new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,Set.of(),Set.of(),Map.of())),
        new dev.mikoto2000.rei.event.AgentEventFactory(clock),event->{});
    for(var owner:owners)assertThatThrownBy(()->guard.check("writeMultiFile","{}",owner))
        .isInstanceOf(dev.mikoto2000.rei.core.policy.ToolPermissionException.class);
    assertThat(voiceOwner.requestSource()).isEqualTo(owners.getFirst().requestSource());
  }  @Test void voiceIsRejectedWhenExistingRunQueueIsUnavailable() {
    ConversationTarget target;
    try(var scope=projects.newClient().open()){target=shell.captureTarget();}
    var gateway=new ConversationInputGateway(lifecycle,(owner,prompt)->{},null,clock);
    assertThatThrownBy(()->gateway.submit(voice(target,"voice"),AgentRunContext.Mode.EXCLUSIVE))
        .isInstanceOf(IllegalStateException.class);
  }
  @Test void voiceCancellationKeepsExistingRunRegistryConsistent() {
    var registry=new dev.mikoto2000.rei.application.run.RunRegistry(clock);
    var bus=new dev.mikoto2000.rei.event.InMemoryAgentEventBus();
    var factory=new dev.mikoto2000.rei.event.AgentEventFactory(clock);
    try(var runs=new dev.mikoto2000.rei.application.run.RunService(registry,bus,factory,
        new dev.mikoto2000.rei.core.service.CommandCancellationService(),router::cancelQueued)){
      var registered=new ShellConversationService(projects,lifecycle,(owner,prompt)->{
        registry.register(owner);
        router.submit(owner,prompt,work->runs.execute(owner,work));
      },false,router,clock);
      registered.onPendingCancellation(owner->runs.cancelQueuedOnly(owner.runId()).accepted());
      ConversationTarget target;
      try(var scope=projects.newClient().open()){registered.submit("block");target=registered.captureTarget();}
      var input=voice(target,"cancel");var owner=registered.submit(input);
      assertThat(registered.cancelPending(target,input.inputId())).isTrue();
      assertThat(registry.get(owner.runId()).status()).isEqualTo(dev.mikoto2000.rei.application.run.RunStatus.CANCELLED);
      while(!tasks.isEmpty())tasks.removeFirst().run();
      assertThat(executed).containsExactly("block");
    }
  }  @Test void expiredCompletedInputCannotBeReplayedAfterLedgerPruning() {
    var mutableClock=org.mockito.Mockito.mock(Clock.class);
    org.mockito.Mockito.when(mutableClock.instant()).thenReturn(clock.instant());
    var gateway=new ConversationInputGateway(lifecycle,router::submit,router,mutableClock,1,Duration.ofMinutes(30));
    ConversationTarget target;
    try(var scope=projects.newClient().open()){target=shell.captureTarget();}
    var input=voice(target,"once");
    gateway.submit(input,AgentRunContext.Mode.EXCLUSIVE);tasks.removeFirst().run();
    org.mockito.Mockito.when(mutableClock.instant()).thenReturn(clock.instant().plusSeconds(1801));
    assertThatThrownBy(()->gateway.submit(input,AgentRunContext.Mode.EXCLUSIVE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(tasks).isEmpty();
  }  @Test void keyboardInputCanAlsoCancelThroughCommonGateway() {
    ConversationTarget target;
    try(var scope=projects.newClient().open()){target=shell.captureTarget();}
    var input=new ConversationInput(UUID.randomUUID(),InputSource.KEYBOARD,target,"cancel keyboard",clock.instant());
    shell.submit(input);
    assertThat(shell.cancelPending(target,input.inputId())).isTrue();
    while(!tasks.isEmpty())tasks.removeFirst().run();
    assertThat(executed).isEmpty();
  }}
