package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exercises the real envelope -> admission/outbox and external reply ledger boundary. */
@Tag("integration")
class SlackBotReplyRoutingTest {
  @TempDir Path dir;
  SlackStore inbox;
  SlackRuntime runtime;
  SlackAgentRepository requests;
  SlackAgentService agents;
  SlackAgentProperties settings;
  SlackEnvelopeReceiver receiver;
  RunRegistry registry;
  ConversationInputRouter router;
  AgentRunContext owner;
  Clock clock = Clock.systemUTC();

  @BeforeEach
  void setup() {
    inbox = new SlackStore(dir);
    var properties = new SlackProperties();
    properties.setAllowedWorkspaceIds(Set.of("T1"));
    properties.setAllowedChannelIds(Set.of("C1"));
    // Deliberately include bot users: sender classification must precede human allowlists.
    properties.setAllowedUserIds(Set.of("U1", "U2", "UREI"));
    var projects = new ProjectRegistry(dir.resolve("projects.json"));
    var project = projects.resolve(dir);
    properties.setChannelProjects(Map.of("C1", project.id()));
    var sessions = new SessionLifecycle(mock(SessionRepository.class), clock);
    registry = new RunRegistry(clock);
    router = mock(ConversationInputRouter.class);
    var runs =
        new RunService(
            registry,
            mock(AgentEventBus.class),
            new AgentEventFactory(clock),
            new CommandCancellationService(),
            router::cancelQueued);
    runtime =
        new SlackRuntime(
            properties, inbox, "UREI", projects, sessions, registry, runs, router, clock);
    settings = new SlackAgentProperties();
    settings.setEnabled(true);
    settings.setAllowedWorkspaceIds(Set.of("T1"));
    settings.setAllowedChannelIds(Set.of("C1"));
    settings.setAgents(
        Map.of("tenten", new SlackAgentProperties.Agent("T1", "U2", "B2", "C1", "U2", "")));
    var source = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("external.db"));
    requests = new SlackAgentRepository(source, clock);
    agents =
        new SlackAgentService(
            settings,
            requests,
            new SlackAgentContinuationStore(source),
            registry,
            runs,
            router,
            clock);
    agents.attach(
        "UREI",
        "T1",
        row -> {
          fail("Receiving a reply must not send a question");
          return null;
        });
    receiver = new SlackEnvelopeReceiver(runtime, "T1", agents);
    owner = new AgentRunContext("parent", "session", dir, project.id());
    registry.register(owner);
    registry.transition("parent", RunStatus.RUNNING, null);
    registry.transition("parent", RunStatus.WAITING, null);
  }

  SlackAgentRepository.Request waiting() {
    var q = requests.create(owner, "call", "tenten", "question", "", 300, null, settings);
    assertTrue(requests.claimSend(q.id()));
    requests.sent(q.id(), "100.1");
    return q;
  }

  String envelope(
      String id,
      String team,
      String channel,
      String thread,
      String user,
      String bot,
      String subtype,
      String text)
      throws Exception {
    var event = new LinkedHashMap<String, Object>();
    event.put("type", "message");
    event.put("channel", channel);
    event.put("channel_type", "channel");
    event.put("ts", id + ".1");
    if (thread != null) event.put("thread_ts", thread);
    if (user != null) event.put("user", user);
    if (bot != null) event.put("bot_id", bot);
    if (subtype != null) event.put("subtype", subtype);
    event.put("text", text);
    return new ObjectMapper()
        .writeValueAsString(
            Map.of(
                "type",
                "events_api",
                "envelope_id",
                id,
                "payload",
                Map.of("type", "event_callback", "event_id", id, "team_id", team, "event", event)));
  }

  void receive(String id, String user, String bot, String subtype, String text) throws Exception {
    assertEquals(id, receiver.receive(envelope(id, "T1", "C1", "100.1", user, bot, subtype, text)));
    runtime.tick();
  }

  void noHumanAdmission() {
    assertTrue(inbox.pending().isEmpty());
    assertTrue(inbox.replies().isEmpty());
    assertEquals(Set.of("parent"), Set.copyOf(registry.runIds()));
    verifyNoInteractions(router);
    assertEquals(RunStatus.WAITING, registry.get("parent").status());
  }

  @Test
  void humanRequestStillPostsAcceptanceEvenWhenBodyLooksLikeBotReceipt() throws Exception {
    // Mock repository admission invokes the existing SessionLifecycle acceptance callback.
    var repo = mock(SessionRepository.class);
    var saved = new HashMap<String, SessionMetadata>();
    when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(saved.get(i.getArgument(0))));
    doAnswer(
            i -> {
              var metadata = (SessionMetadata) i.getArgument(0);
              saved.put(metadata.sessionId(), metadata);
              ((Runnable) i.getArgument(1)).run();
              return null;
            })
        .when(repo)
        .accept(any(), any());
    var projects = new ProjectRegistry(dir.resolve("projects.json"));
    var project = projects.resolve(dir);
    var properties = new SlackProperties();
    properties.setAllowedWorkspaceIds(Set.of("T1"));
    properties.setAllowedChannelIds(Set.of("C1"));
    properties.setAllowedUserIds(Set.of("U1"));
    properties.setChannelProjects(Map.of("C1", project.id()));
    var runs =
        new RunService(
            registry,
            mock(AgentEventBus.class),
            new AgentEventFactory(clock),
            new CommandCancellationService(),
            router::cancelQueued);
    runtime =
        new SlackRuntime(
            properties,
            inbox,
            "UREI",
            projects,
            new SessionLifecycle(repo, clock),
            registry,
            runs,
            router,
            clock);
    receiver = new SlackEnvelopeReceiver(runtime, "T1", agents);
    assertEquals(
        "101",
        receiver.receive(
            envelope("101", "T1", "C1", null, "U1", null, null, "<@UREI> 受付しました。Run: human text")));
    runtime.tick();
    var transport = mock(SlackTransport.class);
    when(transport.send(any())).thenReturn(SlackTransport.Delivery.sent("200.1"));
    runtime.send(transport);
    verify(transport).send(argThat(row -> row.text().startsWith("受付しました。Run: ")));
    assertEquals(2, registry.runIds().size());
    verify(router).submit(any(AgentRunContext.class), anyString(), any());
  }

  @Test
  void tentenReplyIsSavedWithoutReceiptOrNewRun() throws Exception {
    var q = waiting();
    receive("101", "U2", "B2", "bot_message", "answer");
    assertEquals("answer", requests.responses(q.id()).getFirst().text());
    assertEquals("WAITING", requests.get(q.id()).state());
    noHumanAdmission();
  }

  @Test
  void multipleRepliesAndDuplicateEventsNeverCreateAnotherRequestOrRun() throws Exception {
    var q = waiting();
    receive("101", "U2", "B2", null, "受付しました。Run: other");
    receive("102", "U2", "B2", null, "answer");
    receive("102", "U2", "B2", null, "answer");
    assertEquals(2, requests.responses(q.id()).size());
    assertEquals(1, requests.forRun("parent").size());
    noHumanAdmission();
  }

  @Test
  void unauthorizedBotIsIgnored() throws Exception {
    var q = waiting();
    receive("101", "U1", "BOTHER", "bot_message", "<@UREI> hello");
    assertTrue(requests.responses(q.id()).isEmpty());
    noHumanAdmission();
  }

  @Test
  void ownBotPostIsIgnored() throws Exception {
    var q = waiting();
    receive("101", "UREI", "BREI", null, "<@UREI> hello");
    assertTrue(requests.responses(q.id()).isEmpty());
    noHumanAdmission();
    receive("102", "UREI", null, null, "<@UREI> hello");
    noHumanAdmission();
  }

  @Test
  void configuredBotUserWithoutBotIdCannotFallBackToHumanAdmission() throws Exception {
    var q = waiting();
    receive("101", "U2", null, null, "<@UREI> hello");
    assertTrue(requests.responses(q.id()).isEmpty());
    noHumanAdmission();
  }

  @Test
  void botSubtypeWithoutBotIdCannotFallBackToHumanAdmission() throws Exception {
    waiting();
    receive("101", "U1", null, "bot_message", "<@UREI> hello");
    noHumanAdmission();
  }

  @Test
  void configuredBotWithoutActiveRequestOrAfterDisconnectNeverFallsBack() throws Exception {
    receive("101", "U2", null, null, "<@UREI> hello");
    noHumanAdmission();
    agents.detach();
    receive("102", "U2", null, null, "<@UREI> hello");
    noHumanAdmission();
  }

  @Test
  void mismatchedIdentityWorkspaceChannelThreadAndFinishedRequestsAreIgnored() throws Exception {
    var q = waiting();
    for (var raw :
        List.of(
            envelope("101", "T2", "C1", "100.1", "U2", "B2", null, "answer"),
            envelope("102", "T1", "C2", "100.1", "U2", "B2", null, "answer"),
            envelope("103", "T1", "C1", "999.1", "U2", "B2", null, "answer"),
            envelope("104", "T1", "C1", "100.1", "U1", "B2", null, "answer")))
      assertNotNull(receiver.receive(raw));
    assertTrue(requests.responses(q.id()).isEmpty());
    requests.cancelRequest(q.id());
    receive("105", "U2", "B2", null, "late answer");
    assertTrue(requests.responses(q.id()).isEmpty());
    noHumanAdmission();
  }
}
