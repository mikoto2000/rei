package dev.mikoto2000.rei.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor;
import dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
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

  @SuppressWarnings("unchecked")
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
    ((Map<String, PersistentCheckpoint>) ReflectionTestUtils.getField(service, "active"))
        .put(state.runId(), state);
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
      var memory = MessageWindowChatMemory.builder().maxMessages(30).build();
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
          turns.finish(owner, ConversationTurnStore.Status.FAILED, null);
        } else if (i < texts.size() - 1) memory.add("chat", new UserMessage(texts.get(i)));
      }
      var beforeLogs = logs.readConversation("chat");
      var beforeTurns = turns.read("chat");
      var request =
          ChatClientRequest.builder()
              .prompt(new Prompt(new UserMessage(current)))
              .context(
                  Map.of(
                      ChatMemory.CONVERSATION_ID,
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
      try (var scope = AgentRunScope.open(new AgentRunContext("current", "chat", root, "A"))) {
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

  @Test
  void currentControlRequestIsNeverRemovedEvenWhenIdenticalToHistoricalRequest() {
    var state =
        PersistentCheckpoint.initial("task", "A", "chat", "old", root, "review docs")
            .resume("current");
    try (var service = service(state);
        var scope = AgentRunScope.open(new AgentRunContext("current", "chat", root, "A"))) {
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
    try (var service = service(state);
        var scope =
            AgentRunScope.open(
                new AgentRunContext(
                    "current",
                    "chat",
                    root,
                    "A",
                    AgentRunContext.RequestSource.SHELL,
                    mode,
                    false))) {
      var messages = List.<Message>of(new UserMessage(legacy("task")), new UserMessage("next"));
      var request = ChatClientRequest.builder().prompt(new Prompt(messages)).build();
      var result = new ResumeContextAdvisor(service).before(request, null).prompt();
      assertThat(result.getInstructions().stream().filter(UserMessage.class::isInstance))
          .containsExactlyElementsOf(messages);
    }
  }

  @Test
  void ordinaryRunsKeepPriorRequests() {
    var state = PersistentCheckpoint.initial("task", "A", "chat", "current", root, "review docs");
    try (var service = service(state);
        var scope = AgentRunScope.open(new AgentRunContext("current", "chat", root, "A"))) {
      var messages = List.<Message>of(new UserMessage(legacy("task")), new UserMessage("next"));
      var request = ChatClientRequest.builder().prompt(new Prompt(messages)).build();
      var result = new ResumeContextAdvisor(service).before(request, null).prompt();
      assertThat(result.getInstructions().stream().filter(UserMessage.class::isInstance))
          .containsExactlyElementsOf(messages);
    }
  }
}
