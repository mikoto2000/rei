package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

/** SpringAI画面操作画像認識モデルの結果と状態の契約を検証するテスト。 */
class SpringAiComputerVisionModelTest {
  /** 画面操作の支援で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void outputLimitStopsWithoutRepeatingTheSameExpensiveRequest() {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(""),
                        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                            .finishReason("length")
                            .build()))));
    var vision = new SpringAiComputerVisionModel(model, OpenAiChatOptions::builder, () -> false, 1);
    var error = assertThrows(IllegalArgumentException.class, () -> vision.decide(observation()));
    assertTrue(error.getMessage().contains("output token limit"));
    verify(model).call(any(Prompt.class));
  }

  /** 画面操作の支援で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void repairReceivesSafeValidationReasonAndExplicitSchema() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenReturn(
            response("{}"), response(ActionValidationTest.json("DONE", "reason", "\"Visible\"")));
    new SpringAiComputerVisionModel(model, OpenAiChatOptions::builder, () -> false, 1)
        .decide(observation());
    var prompts = ArgumentCaptor.forClass(Prompt.class);
    verify(model, times(2)).call(prompts.capture());
    assertTrue(
        prompts
            .getAllValues()
            .getFirst()
            .getInstructions()
            .getFirst()
            .getText()
            .contains("\"centerX\""));
    assertTrue(
        prompts
            .getAllValues()
            .getLast()
            .getInstructions()
            .getLast()
            .getText()
            .contains("missing fields"));
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  /** {@code observation} の条件を再現するための補助情報を返す。 */
  static ComputerObservation observation() {
    return new ComputerObservation(
        "Enter abc", ComputerUseServiceTest.screen(), List.of("Clicked editor"), 2, 20);
  }

  /** 画像目標履歴を送信することを確認する。 */
  @Test
  void sendsImageGoalHistoryAndStrictSchemaWithoutTools() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenReturn(response(ActionValidationTest.json("DONE", "reason", "\"Text visible\"")));
    var vision = new SpringAiComputerVisionModel(model, OpenAiChatOptions::builder, () -> false, 1);
    assertInstanceOf(ComputerAction.Done.class, vision.decide(observation()));
    var prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(model).call(prompt.capture());
    var user = (UserMessage) prompt.getValue().getInstructions().getLast();
    assertEquals(1, user.getMedia().size());
    assertTrue(user.getText().contains("Enter abc"));
    assertTrue(user.getText().contains("Clicked editor"));
    assertTrue(user.getText().contains("2/20"));
    var options = (OpenAiChatOptions) prompt.getValue().getOptions();
    assertEquals(ResponseFormat.Type.JSON_SCHEMA, options.getResponseFormat().getType());
    assertEquals(Boolean.TRUE, options.getResponseFormat().getStrict());
    assertNull(options.getToolChoice());
    assertTrue(options.getToolCallbacks().isEmpty());
  }

  /** 画面操作の支援で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void invalidOutputRetriesWithinBudgetAndThenFails() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenReturn(
            response("{}"), response(ActionValidationTest.json("DONE", "reason", "\"Visible\"")));
    var vision = new SpringAiComputerVisionModel(model, OpenAiChatOptions::builder, () -> false, 1);
    assertInstanceOf(ComputerAction.Done.class, vision.decide(observation()));
    verify(model, times(2)).call(any(Prompt.class));
    reset(model);
    when(model.call(any(Prompt.class))).thenReturn(response("{}"));
    assertThrows(IllegalArgumentException.class, () -> vision.decide(observation()));
    verify(model, times(2)).call(any(Prompt.class));
  }

  /** 画面操作の支援の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationBetweenRepairsPreventsAnotherRequest() {
    var model = mock(ChatModel.class);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    when(model.call(any(Prompt.class)))
        .thenAnswer(
            call -> {
              cancelled.set(true);
              return response("{}");
            });
    var vision =
        new SpringAiComputerVisionModel(model, OpenAiChatOptions::builder, cancelled::get, 2);
    assertThrows(
        java.util.concurrent.CancellationException.class, () -> vision.decide(observation()));
    verify(model).call(any(Prompt.class));
  }

  /** 画面操作の支援で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void refusesAmbientToolsBeforeInference() {
    var model = mock(ChatModel.class);
    when(model.getOptions())
        .thenReturn(
            OpenAiChatOptions.builder()
                .toolCallbacks(mock(org.springframework.ai.tool.ToolCallback.class))
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () -> new SpringAiComputerVisionModel(model, OpenAiChatOptions::builder, () -> false, 1));
    verify(model, never()).call(any(Prompt.class));
  }

  /** 指定された提供元選択可能な設定を保持することを確認する。 */
  @Test
  void preservesSuppliedProviderOptionsWithoutMutatingSharedDefaults() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenReturn(response(ActionValidationTest.json("DONE", "reason", "\"Visible\"")));
    var defaults =
        OpenAiChatOptions.builder()
            .model("local-vision")
            .temperature(.25)
            .maxCompletionTokens(4096)
            .toolCallbacks(mock(org.springframework.ai.tool.ToolCallback.class))
            .toolChoice("auto")
            .build();
    new SpringAiComputerVisionModel(model, defaults::mutate, () -> false, 0).decide(observation());
    var prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(model).call(prompt.capture());
    var sent = (OpenAiChatOptions) prompt.getValue().getOptions();
    assertEquals("local-vision", sent.getModel());
    assertEquals(.25, sent.getTemperature());
    assertEquals(4096, sent.getMaxCompletionTokens());
    assertTrue(sent.getToolCallbacks().isEmpty());
    assertNull(sent.getToolChoice());
    assertEquals(1, defaults.getToolCallbacks().size());
    assertEquals("auto", defaults.getToolChoice());
    assertNull(defaults.getResponseFormat());
  }

  /** 画面操作の支援で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void refusesRawToolsInRequestOptionsBeforeInference() {
    var model = mock(ChatModel.class);
    var vision =
        new SpringAiComputerVisionModel(
            model,
            () ->
                OpenAiChatOptions.builder()
                    .extraBody(
                        Map.of(
                            "tools",
                            List.of(
                                Map.of("type", "function", "function", Map.of("name", "shell"))))),
            () -> false,
            0);
    assertThrows(IllegalArgumentException.class, () -> vision.decide(observation()));
    verify(model, never()).call(any(Prompt.class));
  }
}
