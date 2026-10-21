package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

/** 補助エージェント外部連携の結果と状態の契約を検証するテスト。 */
@SpringBootTest(
    properties = {"spring.ai.openai.api-key=test-key", "rei.tool-permission.enabled=true"})
@org.junit.jupiter.api.Tag("integration")
class SubAgentIntegrationTest {
  /** 検証に使うモデル。 */
  @MockitoBean ChatModel model;

  /** 検証に使う埋め込み表現。 */
  @MockitoBean EmbeddingModel embedding;

  /** 検証に使うベクトル一覧。 */
  @MockitoBean VectorStore vectors;

  /** 検証に使う保存済み文書。 */
  @MockitoBean VectorDocumentRepository documents;

  /** 検証に使う実行の担当。 */
  @Autowired SubAgentRunner runner;

  /** 検証に使う並列。 */
  @Autowired ParallelSubAgentDelegator parallel;

  /** 検証に使う登録台帳。 */
  @Autowired SubAgentRegistry registry;

  /** 検証に使う履歴。 */
  @Autowired ChatMemory history;

  /** 検証に使う作業中集合。 */
  @Autowired WorkingSet workingSet;

  /** 検証に使うクライアント一覧。 */
  @Autowired LlmChatClientProvider clients;

  /** 検証に使う権限一覧。 */
  @Autowired dev.mikoto2000.rei.core.policy.ToolPermissionPolicy permissions;

  /** 検証に使うディレクトリ。 */
  static Path directory;

  /** {@code properties} の条件を再現するため、検証用の操作や状態を扱う。 */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) throws Exception {
    directory = Files.createTempDirectory("rei-subagent-test-");
    properties.add("rei.subagents.directory", () -> directory.toString());
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void realCallbacksLeaveMainHistoryAndWorkingSetUntouchedAndParentSeesDelegation()
      throws Exception {
    when(model.getOptions())
        .thenReturn(
            org.springframework.ai.openai.OpenAiChatOptions.builder().model("test-model").build());
    Files.writeString(
        directory.resolve("reviewer.yaml"), SubAgentConfigurationTest.yaml("reviewer"));
    Files.writeString(directory.resolve("note.txt"), "independent evidence");
    assertThat(registry.reload()).isEmpty();
    assertThat(permissions.evaluate("delegateTask", "{\"agent\":\"reviewer\"}"))
        .isEqualTo(dev.mikoto2000.rei.core.policy.PermissionDecision.AUTO_APPROVE);
    assertThat(
            permissions.evaluate(
                "delegateTasks",
                "{\"requests\":[{\"agent\":\"reviewer\"},{\"agent\":\"reviewer\"}]}"))
        .isEqualTo(dev.mikoto2000.rei.core.policy.PermissionDecision.AUTO_APPROVE);
    String main = "test-parent-" + UUID.randomUUID();
    history.add(main, List.of(new UserMessage("parent private reasoning")));
    List<Prompt> requests = new CopyOnWriteArrayList<>();
    when(model.stream(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              Prompt prompt = invocation.getArgument(0);
              requests.add(prompt);
              var message =
                  requests.size() == 1
                      ? AssistantMessage.builder()
                          .content("")
                          .toolCalls(
                              List.of(
                                  new AssistantMessage.ToolCall(
                                      "read",
                                      "function",
                                      "readMultiFile",
                                      "{\"files\":[{\"path\":\"note.txt\"}]}")))
                          .build()
                      : new AssistantMessage(SubAgentResultParserTest.VALID);
              return Flux.just(new ChatResponse(List.of(new Generation(message))));
            });
    try (var scope = AgentRunScope.open(new AgentRunContext("parent", main, directory))) {
      var before = workingSet.getFiles();
      var result = runner.run("reviewer", "review note.txt", null);
      assertThat(result.status()).isEqualTo(SubAgentResult.Status.COMPLETED);
      assertThat(requests).allMatch(p -> !p.getContents().contains("parent private reasoning"));
      assertThat(requests.getLast().getInstructions())
          .filteredOn(m -> m instanceof ToolResponseMessage)
          .anyMatch(
              m ->
                  ((ToolResponseMessage) m)
                      .getResponses()
                      .toString()
                      .contains("independent evidence"));
      assertThat(workingSet.getFiles()).isEqualTo(before);
      assertThat(history.get(main))
          .extracting(Message::getText)
          .containsExactly("parent private reasoning");
      assertThat(history.get("subagent:" + result.subAgentRunId())).isEmpty();
      var batch =
          parallel.delegate(
              List.of(
                  new ParallelSubAgentDelegator.Request("first", "reviewer", "review A", null),
                  new ParallelSubAgentDelegator.Request("second", "reviewer", "review B", null)));
      assertThat(batch.status()).isEqualTo(ParallelSubAgentDelegator.Status.COMPLETED);
      assertThat(batch.items()).hasSize(2);
      assertThat(batch.items().stream().map(i -> i.result().subAgentRunId()).distinct()).hasSize(2);
      assertThat(workingSet.getFiles()).isEqualTo(before);
      assertThat(history.get(main))
          .extracting(Message::getText)
          .containsExactly("parent private reasoning");
    } finally {
      history.clear(main);
    }
    var captured = new java.util.concurrent.atomic.AtomicReference<Prompt>();
    when(model.call(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              captured.set(invocation.getArgument(0));
              return new ChatResponse(
                  List.of(new Generation(new AssistantMessage("parent response"))));
            });
    try {
      clients
          .chatClient(LlmFeature.CHAT)
          .prompt()
          .user("review")
          .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, main))
          .call()
          .content();
    } finally {
      history.clear(main);
    }
    var callbacks = ((ToolCallingChatOptions) captured.get().getOptions()).getToolCallbacks();
    assertThat(callbacks)
        .anyMatch(
            c ->
                c.getToolDefinition().name().equals("delegateTask")
                    && c.getToolDefinition().description().contains("reviewer")
                    && !c.getToolDefinition().description().contains("Review independently."));
    assertThat(callbacks)
        .filteredOn(c -> c.getToolDefinition().name().equals("delegateTasks"))
        .hasSize(1);
  }
}
