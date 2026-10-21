package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.Prompt;

/** 実行対象を限定した対話の補助の結果と状態の契約を検証するテスト。 */
class RunScopedAdvisorTest {
  /** 記憶対話の補助について、既定会話を保持することを確認する。 */
  @Test
  void memoryAdvisorRetainsDefaultConversationWithoutRunOwner() {
    var memory = org.springframework.ai.chat.memory.MessageWindowChatMemory.builder().build();
    var model =
        new org.springframework.ai.chat.model.ChatModel() {
          /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            return new org.springframework.ai.chat.model.ChatResponse(
                java.util.List.of(
                    new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage("answer"))));
          }
        };
    var client =
        ChatClient.builder(model)
            .defaultAdvisors(
                new RunScopedAdvisor(
                    org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.builder(
                            memory)
                        .build()))
            .build();
    assertThat(client.prompt("question").call().content()).isEqualTo("answer");
    assertThat(memory.get("default"))
        .extracting(org.springframework.ai.chat.messages.Message::getText)
        .containsExactly("question", "answer");
  }

  /** 会話の実行で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void nestedClientCapturesOwnerBeforeAdvisorScheduler() {
    var run = new AgentRunContext("run", "chat:main", Path.of("a"));
    BaseAdvisor delegate =
        new BaseAdvisor() {
          /** 検証用に、会話の実行で参照する名前を返す。 */
          public String getName() {
            return "test-owner";
          }

          /** 検証用に、他の補助処理と組み合わせる際の適用順序を返す。 */
          public int getOrder() {
            return 1;
          }

          /** {@code before} の条件を再現するための補助情報を返す。 */
          public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
            assertThat(AgentRunScope.current()).isEqualTo(run);
            return request;
          }

          /** {@code after} の条件を再現するための補助情報を返す。 */
          public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
            return response;
          }
        };
    var model =
        new org.springframework.ai.chat.model.ChatModel() {
          /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public org.springframework.ai.chat.model.ChatResponse call(Prompt p) {
            return new org.springframework.ai.chat.model.ChatResponse(
                java.util.List.of(
                    new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage("ok"))));
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
              Prompt p) {
            assertThat(
                    ((org.springframework.ai.model.tool.ToolCallingChatOptions) p.getOptions())
                        .getToolContext())
                .containsEntry(AgentRunContext.class.getName(), run);
            return reactor.core.publisher.Flux.just(call(p));
          }
        };
    var client =
        ChatClient.builder(model)
            .defaultOptions(org.springframework.ai.model.tool.ToolCallingChatOptions.builder())
            .defaultAdvisors(new RunScopedAdvisor(delegate))
            .build();
    try (var scope = AgentRunScope.open(run)) {
      assertThat(client.prompt("nested").stream().content().blockLast()).isEqualTo("ok");
    }
  }

  /** スレッドを復元することを確認する。 */
  @Test
  void advisorReentersCapturedContextAndRestoresThread() {
    var run = new AgentRunContext("run", "chat:main", Path.of("a"));
    BaseAdvisor delegate =
        new BaseAdvisor() {
          /** 検証用に、会話の実行で参照する名前を返す。 */
          public String getName() {
            return "test-owner";
          }

          /** 検証用に、他の補助処理と組み合わせる際の適用順序を返す。 */
          public int getOrder() {
            return 1;
          }

          /** {@code before} の条件を再現するための補助情報を返す。 */
          public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
            assertThat(AgentRunScope.current()).isEqualTo(run);
            return request;
          }

          /** {@code after} の条件を再現するための補助情報を返す。 */
          public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
            return response;
          }
        };
    var request =
        ChatClientRequest.builder()
            .prompt(new Prompt("work"))
            .context(Map.of(AgentRunContext.class.getName(), run))
            .build();
    new RunScopedAdvisor(delegate).before(request, null);
    assertThat(AgentRunScope.current()).isNull();
  }
}
