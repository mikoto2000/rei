package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/** 会話形式実行の結果と状態の契約を検証するテスト。 */
class ConversationStyleExecutionTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使うプロンプト一覧。 */
  final List<Prompt> prompts = new ArrayList<>();

  /** 検証に使うツール一覧。 */
  final AtomicInteger tools = new AtomicInteger();

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  ChatExecutionService service(
      String tool, String arguments, String answer, ToolPermissionGuard guard) {
    ChatModel model =
        new ChatModel() {
          /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
          public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt p) {
            prompts.add(p);
            boolean observed =
                p.getInstructions().stream().anyMatch(m -> m instanceof ToolResponseMessage);
            var output =
                observed
                    ? new AssistantMessage(answer)
                    : AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall(
                                    UUID.randomUUID().toString(), "function", tool, arguments)))
                        .build();
            return Flux.just(new ChatResponse(List.of(new Generation(output))));
          }
        };
    ToolCallback callback =
        new ToolCallback() {
          /** 検証用に、会話の実行で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name(tool)
                .description("fixture")
                .inputSchema("{\"type\":\"object\"}")
                .build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            tools.incrementAndGet();
            try {
              if (tool.equals("readFile")) return Files.readString(root.resolve("result.txt"));
              Files.writeString(root.resolve("result.txt"), input);
              return "verified fixture file written";
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
        };
    var client =
        ChatClient.builder(new StagnationChatModel(model))
            .defaultSystem("あなたはれいです。既存キャラクター。")
            .defaultTools(callback)
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .build();
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var service =
        new ChatExecutionService(
            client, holder, new CommandCancellationService(), Optional.empty());
    service.setToolPermissionGuard(guard);
    return service;
  }

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner(String run, boolean voice, ResponseStyle style) {
    return new AgentRunContext(
        run,
        "session",
        root,
        "project",
        AgentRunContext.RequestSource.SHELL,
        AgentRunContext.Mode.EXCLUSIVE,
        voice,
        style);
  }

  /** 会話の実行で、指定した条件に対する結果と状態の変化を確認する。 */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sameToolLoopAndFullImportantAnswerWorkForBothInputSources(boolean voice) throws Exception {
    Files.writeString(root.resolve("result.txt"), "proof");
    var guard =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(new ToolPermissionProperties(false, null, null, null)),
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            e -> {});
    var answer = "重要な検証結果、失敗理由と承認事項を保持します。\n".repeat(400);
    var service = service("readFile", "{}", answer, guard);
    var result =
        service.execute(
            owner("conversation", voice, ResponseStyle.CONVERSATION),
            "詳しく調査して",
            new UserInterventionQueue());
    assertThat(result.success()).isTrue();
    assertThat(tools.get()).isEqualTo(1);
    assertThat(result.text()).isEqualTo(answer);
    assertThat(prompts)
        .hasSize(2)
        .allSatisfy(p -> assertThat(p.getSystemMessage().getText()).contains("既存キャラクター", "会話スタイル"));
    assertThat(
            service
                .execute(
                    owner("normal", voice, ResponseStyle.NORMAL),
                    "通常に戻す",
                    new UserInterventionQueue())
                .success())
        .isTrue();
    assertThat(prompts.getLast().getSystemMessage().getText()).doesNotContain("会話スタイル");
  }

  /** 会話の実行の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void voiceMutationStillRequiresExactOneUseHumanApproval() throws Exception {
    var approvals =
        new ToolApprovalRepository(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:sqlite:" + root.resolve("approvals.db")),
            Clock.systemUTC());
    var guard =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(new ToolPermissionProperties(false, null, null, null)),
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            e -> {});
    guard.setApprovals(approvals);
    var service =
        service(
            "writeMultiFile",
            "{\"files\":[{\"path\":\"result.txt\",\"content\":\"approved fixture\"}]}",
            "作業を実行して検証しました",
            guard);
    assertThat(
            service
                .execute(
                    owner("blocked", true, ResponseStyle.CONVERSATION),
                    "ファイルを書いて",
                    new UserInterventionQueue())
                .success())
        .isFalse();
    assertThat(tools.get()).isZero();
    assertThat(root.resolve("result.txt")).doesNotExist();
    var request = approvals.list("project").getFirst();
    approvals.decide("project", request.id(), true);
    assertThat(
            service
                .execute(
                    owner("approved", true, ResponseStyle.CONVERSATION),
                    "再開して",
                    new UserInterventionQueue())
                .success())
        .isTrue();
    assertThat(tools.get()).isEqualTo(1);
    assertThat(Files.readString(root.resolve("result.txt"))).contains("approved fixture");
    assertThat(
            service
                .execute(
                    owner("consumed", true, ResponseStyle.CONVERSATION),
                    "もう一度",
                    new UserInterventionQueue())
                .success())
        .isFalse();
    assertThat(tools.get()).isEqualTo(1);
  }
}
