package dev.mikoto2000.rei.topic;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.contextbudget.*;
import dev.mikoto2000.rei.event.*;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;

class BehaviorConversationIntegrationTest {
  @TempDir Path dir;
  final Clock clock=Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"),ZoneOffset.UTC);
  final com.fasterxml.jackson.databind.ObjectMapper mapper=new com.fasterxml.jackson.databind.ObjectMapper()
      .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  ConversationLogStore logs;
  ConversationTurnStore turns;
  FileSessionRepository sessions;
  ProjectService projects;
  ProjectClient client;
  ProjectContext project;
  final List<AgentEvent> events=new ArrayList<>();
  DefaultAgentActivityTracker tracker;
  dev.mikoto2000.rei.ui.shell.sound.AgentMessageNarrator narrator;
  EventAgentMessagePublisher publisher;
  String oldData;
  @BeforeEach void setup() {
    oldData=System.getProperty("rei.data-dir");System.setProperty("rei.data-dir",dir.toString());
    logs=new ConversationLogStore(null,clock,mapper);turns=new ConversationTurnStore(dir);
    sessions=new FileSessionRepository(dir.resolve("sessions.json"));
    projects=new ProjectService(dir,new ProjectRegistry(dir.resolve("projects.json")),sessions);
    client=projects.newClient();project=projects.currentContext();
    tracker=new DefaultAgentActivityTracker(clock);narrator=mock(dev.mikoto2000.rei.ui.shell.sound.AgentMessageNarrator.class);
    publisher=publisher(logs,turns,sessions);
  }
  @AfterEach void cleanup() {
    if(oldData==null)System.clearProperty("rei.data-dir");else System.setProperty("rei.data-dir",oldData);
  }
  EventAgentMessagePublisher publisher(ConversationLogStore store,ConversationTurnStore turnStore,SessionRepository repo) {
    var bus=new InMemoryAgentEventBus();bus.subscribe(events::add);
    var result=new EventAgentMessagePublisher(store,new AgentEventFactory(clock),bus,tracker,narrator);
    result.behaviorHistory(new BehaviorConversationHistoryAppender(store,turnStore,repo,projects));return result;
  }
  AgentMessage notice(String id) {
    return new AgentMessage(id,"assistant","そろそろ戻ろう。",MessageOrigin.BEHAVIOR,clock.instant(),
        Map.of("severity","WARNING","triggerType","CONTINUOUS_ENTERTAINMENT","rawEvidence","PRIVATE"));
  }
  @Test void scheduledWorkerUsesActiveShellSessionAndRestartReplayKeepsOriginalProject() throws Exception {
    try(var scope=client.open();var binding=projects.notificationsFollow(client)) {
      var lifecycle=new SessionLifecycle(sessions,clock);
      var shell=new ShellConversationService(projects,lifecycle,(c,p)->{});
      var active=shell.submit("start");
      var userActivity=tracker.lastUserActivityAt();
      try(var worker=Executors.newSingleThreadExecutor()) {worker.submit(()->publisher.publish(notice("b-1"))).get(5,TimeUnit.SECONDS);}
      assertThat(events).hasSize(3).allSatisfy(e->{
        assertThat(e.sessionId()).isEqualTo(active.conversationId());
        assertThat(e.projectId()).isEqualTo(project.id());
      });
      assertThat(tracker.lastUserActivityAt()).isEqualTo(userActivity);
      assertThat(logs.readConversation(active.conversationId())).singleElement().satisfies(e->{
        assertThat(e.source()).isEqualTo("BEHAVIOR_NOTIFICATION");
        assertThat(e.sourceId()).isEqualTo("b-1");
        assertThat(e.metadata()).containsOnlyKeys("severity","triggerType");
      });
      projects.cd(Files.createDirectory(dir.resolve("other")).toString());
      var restarted=publisher(new ConversationLogStore(null,clock,mapper),new ConversationTurnStore(dir),
          new FileSessionRepository(dir.resolve("sessions.json")));
      restarted.publish(notice("b-1"));
      assertThat(events).hasSize(3);
      assertThat(new ConversationTurnStore(dir).findTurns(active.conversationId(),null,10)).singleElement().satisfies(t->{
        assertThat(t.userMessage()).isEmpty();assertThat(t.sourceId()).isEqualTo("b-1");
        assertThat(t.assistantMessage()).isEqualTo(notice("b-1").content());
        assertThat(t.createdAt()).isEqualTo(clock.instant());
      });
      projects.cd(dir.toString());
      assertThat(projects.currentSessionId()).isEqualTo(active.conversationId());
      var output=new java.io.StringWriter();
      var command=new picocli.CommandLine(new dev.mikoto2000.rei.ui.shell.HistoryCommand(
          mock(HistoryShellService.class),new SessionQueryService(sessions,turns),projects));
      command.setOut(new java.io.PrintWriter(output));
      assertThat(command.execute("show")).isZero();
      assertThat(output.toString()).contains("Rei:","そろそろ戻ろう。").doesNotContain("User:");
    }
  }
  @Test void noActiveSessionUsesOrdinaryDefaultAndNextUserContinuesIt() {
    try(var scope=client.open();var binding=projects.notificationsFollow(client)) {
      publisher.publish(notice("b-1"));publisher.publish(notice("b-2"));
      var id=project.conversationId("chat:main");
      assertThat(projects.currentSessionId()).isEqualTo(id);
      assertThat(logs.readConversation(id)).hasSize(2);
      var next=new ShellConversationService(projects,new SessionLifecycle(sessions,clock),(c,p)->{}).submit("わかった");
      assertThat(next.conversationId()).isEqualTo(id);
      assertThat(sessions.findPage(project.id(),null,10)).hasSize(1);
    }
  }
  @Test void headlessUsesMostRecentlyAdmittedConversationOrStartupDefault() {
    var lifecycle=new SessionLifecycle(sessions,clock);lifecycle.onSelected(projects::rememberConversation);
    publisher.publish(notice("default"));
    assertThat(logs.readConversation(project.conversationId("chat:main"))).hasSize(1);
    var accepted=lifecycle.submit(project,null,"web request",dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.WEB,c->{});
    publisher.publish(notice("web"));
    assertThat(logs.readConversation(accepted.conversationId())).hasSize(1);
  }
  @Test void partialProjectionIsRepairedWithoutRedisplayOrRegeneration() {
    var id=project.conversationId("chat:main");
    logs.appendNotification(id,"repair",notice("repair").content(),clock.instant(),Map.of());
    publisher.publish(notice("repair"));
    assertThat(events).isEmpty();verifyNoInteractions(narrator);
    assertThat(turns.findTurns(id,null,10)).hasSize(1);assertThat(sessions.findById(id)).isPresent();
  }
  @Test void failedHistoryDoesNotStopNotificationOrNarration() {
    var broken=mock(ConversationLogStore.class);
    when(broken.appendNotification(anyString(),anyString(),anyString(),any(),any())).thenThrow(new IllegalStateException());
    assertThatCode(()->publisher(broken,turns,sessions).publish(notice("fail"))).doesNotThrowAnyException();
    assertThat(events).hasSize(3);verify(narrator).onPublished(notice("fail"));
  }
  @Test void restartedContextHasConsecutiveAssistantMessagesAndCompressionPreservesOriginals() {
    var id=project.conversationId("chat:main");
    logs.append(id,"user","作業をする "+"details ".repeat(500));logs.append(id,"assistant","始めよう。");
    publisher.publish(notice("context"));logs.append(id,"user","わかった。");
    var before=logs.readConversation(id);
    var restored=new ConversationLogStore(null,clock,mapper);
    var summaries=new ConversationSummaryRepository(dir);
    var advisor=new ContextHistoryAdvisor(new ConversationTurnStore(dir),MessageWindowChatMemory.builder().maxMessages(2).build(),summaries,restored);
    var request=ChatClientRequest.builder().prompt(new Prompt(new UserMessage("わかった。"),
        org.springframework.ai.openai.OpenAiChatOptions.builder().build())).context(Map.of(ChatMemory.CONVERSATION_ID,id)).build();
    var assembled=advisor.before(request,null).prompt();
    assertThat(assembled.getInstructions()).extracting(Message::getMessageType)
        .containsExactly(MessageType.USER,MessageType.ASSISTANT,MessageType.ASSISTANT,MessageType.USER);
    assertThat(assembled.getInstructions().get(2).getMetadata()).containsEntry("source","BEHAVIOR_NOTIFICATION").containsEntry("sourceId","context");
    var props=new ContextCompressionProperties();props.setThreshold(300);props.setHardLimit(600);props.setRecentTokens(80);props.setSummaryTokens(100);
    var tokens=TokenEstimator.conservative();
    new ContextAssembler(props,tokens,summaries,new ToolResultCompressor(new RawToolResultStore(dir),tokens,100,70),
        (p,m,b,r)->"作業に戻ると合意",null,null).assemble(assembled,id,"next",()->{});
    assertThat(restored.readConversation(id)).containsExactlyElementsOf(before);
    assertThat(summaries.read(id+"/log").throughSequence()).isGreaterThan(0);
  }
  @Test void capturedDestinationSurvivesSwitchBeforeAppend() throws Exception {
    try(var scope=client.open()) {
      var appender=new BehaviorConversationHistoryAppender(logs,turns,sessions,projects);
      var target=appender.capture(notice("captured"));
      projects.cd(Files.createDirectory(dir.resolve("other")).toString());
      appender.append(target,notice("captured"));
      assertThat(logs.readConversation(project.conversationId("chat:main"))).hasSize(1);
      assertThat(projects.currentSessionId()).isNull();
    }
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"NOTICE","WARNING","STRONG_WARNING"})
  void everyEmittedSeverityIsAnAssistantMessage(String severity) {
    var message=new AgentMessage("severity-"+severity,"assistant","戻ろう。",MessageOrigin.BEHAVIOR,clock.instant(),Map.of("severity",severity));
    publisher.publish(message);
    assertThat(logs.readConversation(project.conversationId("chat:main"))).singleElement().satisfies(e->{
      assertThat(e.speaker()).isEqualTo("assistant");assertThat(e.metadata()).containsEntry("severity",severity);
    });
  }
  @Test void legacyJsonWithoutMetadataStillReads() throws Exception {
    var entry=mapper.readValue("{\"conversationId\":\"chat:main\",\"scope\":\"chat\",\"speaker\":\"assistant\",\"timestamp\":\"2026-09-25T12:00:00Z\",\"content\":\"old\"}",ConversationLogEntry.class);
    assertThat(entry.source()).isNull();assertThat(entry.metadata()).isEmpty();
    var turn=mapper.readValue("{\"runId\":\"old\",\"request\":\"q\",\"status\":\"COMPLETED\",\"assistantMessage\":\"a\",\"createdAt\":\"2026-09-25T12:00:00Z\"}",ConversationTurnStore.Turn.class);
    assertThat(turn.source()).isNull();assertThat(turn.metadata()).isEmpty();
  }
}
