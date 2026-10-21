package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 画面操作利用アプリケーションの結果と状態の契約を検証するテスト。 */
@SpringBootTest(properties = {"spring.ai.openai.api-key=test-key", "rei.computer-use.enabled=true"})
@org.junit.jupiter.api.Tag("integration")
class ComputerUseApplicationTest {
  /** 検証に使う応答生成に使うモデル。 */
  @MockitoBean ChatModel chatModel;

  /** 検証に使う埋め込み表現モデル。 */
  @MockitoBean EmbeddingModel embeddingModel;

  /** 検証に使うベクトル保存先。 */
  @MockitoBean VectorStore vectorStore;

  /** 検証に使う保存と検索の窓口。 */
  @MockitoBean VectorDocumentRepository repository;

  /** 検証に使う取得記録。 */
  @MockitoBean ScreenCapture capture;

  /** 検証に使う画像認識。 */
  @MockitoBean ComputerVisionModel vision;

  /** 検証に使う入力。 */
  @MockitoBean ComputerInput input;

  /** 検証に使うstabilizer。 */
  @MockitoBean UiStabilizer stabilizer;

  /** 検証に使うツール一覧。 */
  @Autowired ComputerUseTools tools;

  /** 検証に使うクライアント一覧。 */
  @Autowired dev.mikoto2000.rei.llm.LlmChatClientProvider clients;

  /** 検証に使う履歴。 */
  @Autowired org.springframework.ai.chat.memory.ChatMemory history;

  /** 正確に一度だけを利用することを確認する。 */
  @Test
  void actualChatRequestAdvertisesComputerUseExactlyOnceAndOtherFeaturesDoNot() {
    when(chatModel.getOptions())
        .thenReturn(
            org.springframework.ai.openai.OpenAiChatOptions.builder().model("test-model").build());
    var captured =
        new java.util.concurrent.atomic.AtomicReference<
            org.springframework.ai.chat.prompt.Prompt>();
    when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenAnswer(
            invocation -> {
              captured.set(invocation.getArgument(0));
              return reactor.core.publisher.Flux.just(
                  new org.springframework.ai.chat.model.ChatResponse(
                      java.util.List.of(
                          new org.springframework.ai.chat.model.Generation(
                              new org.springframework.ai.chat.messages.AssistantMessage(
                                  "Observed tool definitions")))));
            });
    for (String feature :
        java.util.List.of(
            dev.mikoto2000.rei.llm.LlmFeature.CHAT,
            dev.mikoto2000.rei.llm.LlmFeature.SEARCH,
            dev.mikoto2000.rei.llm.LlmFeature.MEMORY)) {
      String conversation = "computer-tool-advertisement-" + java.util.UUID.randomUUID();
      try {
        clients
            .chatClient(feature)
            .prompt(
                new org.springframework.ai.chat.prompt.Prompt(
                    new org.springframework.ai.chat.messages.UserMessage("List available tools"),
                    org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model("test-model")
                        .build()))
            .advisors(
                a ->
                    a.param(
                        org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID,
                        conversation))
            .stream()
            .chatResponse()
            .collectList()
            .block();
        var options =
            (org.springframework.ai.model.tool.ToolCallingChatOptions) captured.get().getOptions();
        var names =
            options.getToolCallbacks().stream().map(c -> c.getToolDefinition().name()).toList();
        assertEquals(
            feature.equals(dev.mikoto2000.rei.llm.LlmFeature.CHAT) ? 1 : 0,
            names.stream().filter("computerUse"::equals).count(),
            feature + " request must advertise the correct tools");
        assertFalse(names.contains("captureScreen"));
        assertFalse(names.contains("click"));
        assertFalse(names.contains("typeText"));
        String system =
            captured.get().getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage)
                .map(m -> m.getText())
                .collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(
            feature.equals(dev.mikoto2000.rei.llm.LlmFeature.CHAT),
            system.contains("Desktop task orchestration"));
        if (feature.equals(dev.mikoto2000.rei.llm.LlmFeature.CHAT))
          assertTrue(names.contains("runCommand"));
      } finally {
        history.clear(conversation);
      }
    }
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(capture, vision, input, stabilizer);
  }

  /** 画面操作の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void applicationWiresWorkflowWithoutTouchingDesktopOrNetwork() throws Exception {
    when(capture.captureScreen()).thenReturn(ComputerUseServiceTest.screen());
    when(vision.decide(any())).thenReturn(new ComputerAction.Done("Visible"));
    assertEquals(ComputerUseResult.Status.DONE, tools.computerUse("goal").status());
    verify(capture).captureScreen();
    verifyNoInteractions(input, stabilizer);
  }
}
