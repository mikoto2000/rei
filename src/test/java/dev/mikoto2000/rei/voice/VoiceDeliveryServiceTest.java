package dev.mikoto2000.rei.voice;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import dev.mikoto2000.rei.application.input.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
class VoiceDeliveryServiceTest {
  @TempDir Path root;
  final Clock clock=Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"),ZoneOffset.UTC);
  ProjectService projects;ShellConversationService shell;VoiceDeliveryService delivery;
  final List<Runnable> tasks=new ArrayList<>();final List<String> prompts=new ArrayList<>();
  @BeforeEach void setup() {
    projects=new ProjectService(root,new ProjectRegistry(root.resolve("projects.json")));
    var router=new ConversationInputRouter(tasks::add,(owner,prompt,mailbox)->prompts.add(prompt));
    shell=new ShellConversationService(projects,new SessionLifecycle(new FileSessionRepository(root.resolve("sessions.json")),clock),router::submit,false,router,clock);
    delivery=new VoiceDeliveryService(shell,clock,new VoiceEventPublisher());
  }
  ConversationInput input(ConversationTarget target){return new ConversationInput(UUID.randomUUID(),InputSource.VOICE,target,"recognized",clock.instant());}
  @Test void correctionReviewIsNotToolApprovalAndCannotDispatchWithoutExplicitConfirmation() {
    var client=projects.newClient();ConversationTarget target;
    try(var scope=client.open()){target=shell.captureTarget();delivery.bind(client,target);}
    var raw=input(target);delivery.requireReview(raw);assertThat(tasks).isEmpty();
    assertThat(delivery.pending()).containsExactly(raw);delivery.confirm(raw.inputId(),null);
    assertThat(tasks).hasSize(1);tasks.removeFirst().run();assertThat(prompts).containsExactly(raw.text());
  }
  @Test void switchingAwayAndBackInvalidatesCapturedBinding() {
    var client=projects.newClient();ConversationTarget target;
    try(var scope=client.open()){target=shell.captureTarget();delivery.bind(client,target);shell.newConversation();shell.resume(target.sessionId());}
    assertThat(delivery.targetIsCurrent(target)).isFalse();
    assertThatThrownBy(()->delivery.accept(input(target))).isInstanceOf(IllegalStateException.class);assertThat(tasks).isEmpty();
  }
  @Test void stopIdentityCannotBecomeAnAgentInputAfterDisablingInterrupt() {
    var options=new java.util.concurrent.atomic.AtomicReference<>(new VoiceAdvancedOptions(false,"れい",true,false,VoiceAdvancedOptions.defaults().ttsVoice(),800));
    delivery=new VoiceDeliveryService(shell,clock,new VoiceEventPublisher(),options::get);
    ConversationTarget target;try(var scope=projects.newClient().open()){target=shell.captureTarget();delivery.bind(shell.captureClient(),target);}
    var stop=new ConversationInput(UUID.randomUUID(),InputSource.VOICE,target,"実行を停止",clock.instant());
    delivery.accept(stop);options.set(VoiceAdvancedOptions.defaults());delivery.accept(stop);
    assertThat(tasks).isEmpty();
    assertThatThrownBy(()->delivery.accept(new ConversationInput(stop.inputId(),InputSource.VOICE,target,"different",clock.instant())))
        .isInstanceOf(IllegalArgumentException.class);
  }
  @Test void ordinaryInputIdentityCannotBeReusedForAStopPayload() {
    var options=new VoiceAdvancedOptions(false,"れい",true,false,VoiceAdvancedOptions.defaults().ttsVoice(),800);
    delivery=new VoiceDeliveryService(shell,clock,new VoiceEventPublisher(),()->options);
    ConversationTarget target;try(var scope=projects.newClient().open()){target=shell.captureTarget();delivery.bind(shell.captureClient(),target);}
    var ordinary=input(target);delivery.accept(ordinary);
    assertThatThrownBy(()->delivery.accept(new ConversationInput(ordinary.inputId(),InputSource.VOICE,target,"実行を停止",clock.instant())))
        .isInstanceOf(IllegalArgumentException.class);
  }
  @Test void explicitStopCancelsOnlyCapturedClientsActiveRunAndIsIdempotent() throws Exception {
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    var cancelled=new ArrayList<String>();
    var router=new ConversationInputRouter(tasks::add,(owner,prompt,mailbox)->{
      entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
    });
    shell=new ShellConversationService(projects,new SessionLifecycle(new FileSessionRepository(root.resolve("stop.json")),clock),router::submit,false,router,clock);
    shell.onActiveCancellation(context->{cancelled.add(context.runId());return true;});
    var options=new VoiceAdvancedOptions(false,"れい",true,false,VoiceAdvancedOptions.defaults().ttsVoice(),800);
    delivery=new VoiceDeliveryService(shell,clock,new VoiceEventPublisher(),()->options);
    var first=projects.newClient();ConversationTarget target;AgentRunContext run;
    try(var scope=first.open()){target=shell.captureTarget();delivery.bind(first,target);run=shell.submit("work");}
    var thread=new Thread(tasks.removeFirst());thread.start();
    try {
      assertThat(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var other=projects.newClient();
      try(var scope=other.open()){shell.resume(target.sessionId());delivery.bind(other,target);}
      delivery.accept(new ConversationInput(UUID.randomUUID(),InputSource.VOICE,target,"実行を停止",clock.instant()));
      assertThat(cancelled).isEmpty();
      try(var scope=first.open()){delivery.bind(first,target);}
      var stop=new ConversationInput(UUID.randomUUID(),InputSource.VOICE,target,"実行を停止。",clock.instant());
      delivery.accept(stop);delivery.accept(stop);
      assertThat(cancelled).containsExactly(run.runId());assertThat(tasks).isEmpty();
    } finally {release.countDown();thread.join(3000);}
  }
  @Test void completedVoiceRunOwnershipCannotCrossShellClientsEvenInSameSession() {
    var owners=new ArrayList<AgentRunContext>();
    var router=new ConversationInputRouter(tasks::add,(owner,prompt,mailbox)->owners.add(owner));
    shell=new ShellConversationService(projects,new SessionLifecycle(new FileSessionRepository(root.resolve("owners.json")),clock),router::submit,false,router,clock);
    delivery=new VoiceDeliveryService(shell,clock,new VoiceEventPublisher());
    var first=projects.newClient();ConversationTarget target;
    try(var scope=first.open()){target=shell.captureTarget();delivery.bind(first,target);}
    delivery.accept(input(target));tasks.removeFirst().run();
    assertThat(delivery.ownsRun(owners.getFirst())).isTrue();
    var other=projects.newClient();
    try(var scope=other.open()){shell.resume(target.sessionId());delivery.bind(other,target);}
    assertThat(delivery.ownsRun(owners.getFirst())).isFalse();
  }
  @Test void capturedClientAllowsWorkerWithoutThreadLocalButNotAfterSessionChange() {
    var client=projects.newClient();ConversationTarget target;
    try(var scope=client.open()){target=shell.captureTarget();delivery.bind(client,target);}
    delivery.accept(input(target));tasks.removeFirst().run();assertThat(prompts).containsExactly("recognized");
    try(var scope=client.open()){shell.newConversation();}
    assertThat(delivery.targetIsCurrent(target)).isFalse();assertThatThrownBy(()->delivery.accept(input(target))).isInstanceOf(IllegalStateException.class);
  }
  @Test void anotherClientSelectionCannotRedirectOrStopCapturedVoiceClient() {
    var first=projects.newClient();ConversationTarget target;
    try(var scope=first.open()){target=shell.captureTarget();delivery.bind(first,target);}
    try(var scope=projects.newClient().open()){shell.newConversation();}
    assertThat(delivery.targetIsCurrent(target)).isTrue();delivery.accept(input(target));tasks.removeFirst().run();assertThat(prompts).containsExactly("recognized");
  }
  @Test void confirmationPublishesOnlyIdentityAndCorrectionReachesCommonGateway() {
    var events=new ArrayList<VoiceEventPublisher.Event>();var publisher=new VoiceEventPublisher();publisher.subscribe(events::add);
    delivery=new VoiceDeliveryService(shell,clock,publisher);delivery.setConfirmation(true);
    ConversationTarget target;try(var scope=projects.newClient().open()){target=shell.captureTarget();delivery.bind(shell.captureClient(),target);}
    var input=input(target);delivery.accept(input);assertThat(tasks).isEmpty();
    assertThat(events).anyMatch(e->e.type()==VoiceEventPublisher.Type.REVIEW_REQUIRED&&e.detail().equals(input.inputId().toString()));
    assertThat(events).noneMatch(e->e.detail().contains("recognized"));delivery.confirm(input.inputId(),"corrected");
    tasks.removeFirst().run();assertThat(prompts).containsExactly("corrected");assertThat(delivery.pending()).isEmpty();
  }
}
