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