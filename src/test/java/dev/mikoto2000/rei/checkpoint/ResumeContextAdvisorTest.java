package dev.mikoto2000.rei.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.chatmemory.WindowedConversationMemory;
import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor;
import dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

/** Historical host-generated control requests must not become active user instructions again. */
@Tag("integration")
class ResumeContextAdvisorTest {
  @TempDir Path root;

  private static String legacy(String task) {
    return "保存されたタスク "
        + task
        + " を明示的に再開してください。まず現在の状態を確認し、未完了・未検証の工程を再計画してください。結果不明の副作用を再実行する前に、その項目だけをユーザーに確認してください。";
  }

  private PersistentCheckpointService service(PersistentCheckpoint state) {
    var service =
        new PersistentCheckpointService(
            null,
            null,
            new CheckpointProperties(),
            new InMemoryAgentEventBus(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    ReflectionTestUtils.setField(
        service,
        "active",
        new java.util.concurrent.ConcurrentHashMap<>(Map.of(state.runId(), state)));
    return service;
  }

  @ParameterizedTest
  @ValueSource(strings = {"log", "turns", "memory"})
  void supersededControlRequestsDoNotReachTheModelButUserRequirementsSurvive(String source) {
    var state =
        PersistentCheckpoint.initial("task", "A", "chat", "old", root, "review docs")
            .resume("current");
    try (var service = service(state)) {
      String current = ReflectionTestUtils.invokeMethod(service, "resumePrompt", "task");
      String other = legacy("other-task");
      String quoted = "The following is only a quote: " + legacy("task");
      String amended = legacy("task") + " ただし書き込みは禁止です。";
      var texts =
          List.of(
              "review docs",
              legacy("task"),
              current,
              "Keep the API unchanged",
              other,
              quoted,
              amended,
              current);
      var turns = ConversationTurnStore.inMemory();
      var memory = WindowedConversationMemory.builder().maxMessages(30).build();
      var logs =
          new ConversationLogStore(
              root.resolve("logs"),
              Clock.systemUTC(),
              new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
      for (int i = 0; i < texts.size(); i++) {
        if (source.equals("log")) logs.append("chat", "user", texts.get(i));
        else if (source.equals("turns")) {
          var owner =
              new AgentRunContext(
                  i == texts.size() - 1 ? "current" : "old-" + i, "chat", root, "A");
          turns.start(owner, texts.get(i));
          // Keep historical requirements and controls eligible for this advisor's text policy.
          // The final owner is the active resumed attempt, not an already failed turn.
          if (i < texts.size() - 1)
            turns.finish(owner, ConversationTurnStore.Status.COMPLETED, null);
        } else if (i < texts.size() - 1) memory.add("chat", new UserMessage(texts.get(i)));
      }
      var beforeLogs = logs.readConversation("chat");
      var beforeTurns = turns.read("chat");
      var request =
          ChatClientRequest.builder()
              .prompt(new Prompt(new UserMessage(current)))
              .context(
                  Map.of(
                      org.springframework.ai.chat.client.advisor.api.MemoryAdvisor.CONVERSATION_ID,
                      "chat",
                      AgentRunContext.class.getName(),
                      new AgentRunContext("current", "chat", root, "A")))
              .build();
      var history =
          new ContextHistoryAdvisor(
                  turns,
                  memory,
                  new ConversationSummaryRepository(root),
                  source.equals("log") ? logs : null)
              .before(request, null);
      Prompt sent;
      var scope = AgentRunScope.open(new AgentRunContext("current", "chat", root, "A"));
      try (scope) {
        sent = new ResumeContextAdvisor(service).before(history, null).prompt();
      }
      assertThat(
              sent.getInstructions().stream()
                  .filter(UserMessage.class::isInstance)
                  .map(Message::getText))
          .containsExactly(
              "review docs", "Keep the API unchanged", other, quoted, amended, current);
      assertThat(sent.getInstructions())
          .anyMatch(m -> Boolean.TRUE.equals(m.getMetadata().get("rei.resumeContext")));
      assertThat(logs.readConversation("chat")).isEqualTo(beforeLogs);
      assertThat(turns.read("chat")).isEqualTo(beforeTurns);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void explicitResumeUsesCheckpointRequirementsWithoutReplayingFailedRawHistory(boolean useLogs)
      throws Exception {
    String originalRequest = "review the synthetic API documentation";
    String requirement = "Keep the API unchanged";
    var json = new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    var tree = json.valueToTree(
        PersistentCheckpoint.initial("task", "A", "chat", "failed", root, originalRequest));
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree)
        .putArray("instructions").add(requirement);
    var state = json.treeToValue(tree, PersistentCheckpoint.class).resume("current");
    try (var service = service(state)) {
      String control = ReflectionTestUtils.invokeMethod(service, "resumePrompt", "task");
      var failed = new AgentRunContext("failed", "chat", root, "A");
      var current = new AgentRunContext("current", "chat", root, "A");
      var turns = new ConversationTurnStore(root);
      turns.start(failed, originalRequest, Instant.EPOCH.plusSeconds(1));
      turns.finish(failed, ConversationTurnStore.Status.FAILED, "FAILED-RAW-DRAFT-ONLY");
      turns.start(current, control, Instant.EPOCH.plusSeconds(5));
      var logs = new ConversationLogStore(root.resolve("logs"),
          Clock.fixed(Instant.EPOCH.plusSeconds(2), ZoneOffset.UTC), json);
      if (useLogs) {
        logs.append("chat", "user", originalRequest);
        logs.append("chat", "user", "FAILED-RAW-INTERVENTION-ONLY");
        logs.append("chat", "assistant", "FAILED-RAW-DRAFT-ONLY");
        new ConversationLogStore(root.resolve("logs"),
            Clock.fixed(Instant.EPOCH.plusSeconds(6), ZoneOffset.UTC), json)
            .append("chat", "user", control);
      }
      var beforeTurns = turns.read("chat");
      var beforeLogs = logs.readConversation("chat");
      var request = ChatClientRequest.builder()
          .prompt(new Prompt(new UserMessage(control)))
          .context(Map.of(
              org.springframework.ai.chat.client.advisor.api.MemoryAdvisor.CONVERSATION_ID, "chat",
              AgentRunContext.class.getName(), current))
          .build();
      var history = new ContextHistoryAdvisor(turns,
          WindowedConversationMemory.builder().build(), new ConversationSummaryRepository(root),
          useLogs ? logs : null).before(request, null);
      assertThat(history.prompt().getContents())
          .doesNotContain(originalRequest, requirement, "FAILED-RAW-DRAFT-ONLY", "FAILED-RAW-INTERVENTION-ONLY");

      Prompt sent;
      var scope = AgentRunScope.open(current);
      try (scope) {
        sent = new ResumeContextAdvisor(service).before(history, null).prompt();
      }
      assertThat(sent.getInstructions().stream()
          .filter(UserMessage.class::isInstance).map(Message::getText))
          .containsExactly(control);
      assertThat(sent.getInstructions())
          .filteredOn(m -> Boolean.TRUE.equals(m.getMetadata().get("rei.resumeContext")))
          .singleElement().satisfies(m -> assertThat(m.getText())
              .contains("originalRequest: " + originalRequest,
                  "additionalUserInstructions: [\"" + requirement + "\"]"));
      assertThat(sent.getContents())
          .doesNotContain("FAILED-RAW-DRAFT-ONLY", "FAILED-RAW-INTERVENTION-ONLY");
      assertThat(sent.getInstructions().getLast().getText()).isEqualTo(control);
      assertThat(turns.read("chat")).containsExactlyElementsOf(beforeTurns);
      assertThat(logs.readConversation("chat")).containsExactlyElementsOf(beforeLogs);
      assertThat(state.instructions()).containsExactly(requirement);
    }
  }

  @Test
  void currentControlRequestIsNeverRemovedEvenWhenIdenticalToHistoricalRequest() {
    var state =
        PersistentCheckpoint.initial("task", "A", "chat", "old", root, "review docs")
            .resume("current");
    try (var service = service(state)) {
      var scope = AgentRunScope.open(new AgentRunContext("current", "chat", root, "A"));
      try (scope) {
        var current = new UserMessage(legacy("task"));
        var request =
            ChatClientRequest.builder()
                .prompt(
                    new Prompt(
                        List.of(
                            ContextHistoryAdvisor.historical(new UserMessage(legacy("task")), 1),
                            current)))
                .build();
        var result = new ResumeContextAdvisor(service).before(request, null).prompt();
        assertThat(result.getInstructions().stream().filter(UserMessage.class::isInstance))
            .containsExactly(current);
      }
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = AgentRunContext.Mode.class,
      names = {"READ_ONLY", "CONVERSATION"})
  void readOnlyResumeKeepsItsUserRequests(AgentRunContext.Mode mode) throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var tree =
        json.valueToTree(
            PersistentCheckpoint.initial("task", "A", "chat", "old", root, "review docs")
                .resume("current"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("mode", mode.name());
    var state = json.treeToValue(tree, PersistentCheckpoint.class);
    try (var service = service(state)) {
      var scope =
          AgentRunScope.open(
              new AgentRunContext(
                  "current", "chat", root, "A", AgentRunContext.RequestSource.SHELL, mode, false));
      try (scope) {
        var messages = List.<Message>of(new UserMessage(legacy("task")), new UserMessage("next"));
        var request = ChatClientRequest.builder().prompt(new Prompt(messages)).build();
        var result = new ResumeContextAdvisor(service).before(request, null).prompt();
        assertThat(result.getInstructions().stream().filter(UserMessage.class::isInstance))
            .containsExactlyElementsOf(messages);
      }
    }
  }

  @Test
  void ordinaryRunsKeepPriorRequests() {
    var state = PersistentCheckpoint.initial("task", "A", "chat", "current", root, "review docs");
    try (var service = service(state)) {
      var scope = AgentRunScope.open(new AgentRunContext("current", "chat", root, "A"));
      try (scope) {
        var messages = List.<Message>of(new UserMessage(legacy("task")), new UserMessage("next"));
        var request = ChatClientRequest.builder().prompt(new Prompt(messages)).build();
        var result = new ResumeContextAdvisor(service).before(request, null).prompt();
        assertThat(result.getInstructions().stream().filter(UserMessage.class::isInstance))
            .containsExactlyElementsOf(messages);
      }
    }
  }
}
