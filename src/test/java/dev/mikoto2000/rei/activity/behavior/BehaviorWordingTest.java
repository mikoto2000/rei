package dev.mikoto2000.rei.activity.behavior;

import static dev.mikoto2000.rei.activity.behavior.BehaviorEvaluatorTest.assess;
import static dev.mikoto2000.rei.activity.behavior.BehaviorEvaluatorTest.record;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 作業習慣Wordingの結果と状態の契約を検証するテスト。 */
class BehaviorWordingTest {
  /** プロンプトについて、応答の方針を再利用することを確認する。 */
  @Test
  void promptReusesPersonaAndOnlyStructuredAssessmentWithoutTools() throws Exception {
    var model = mock(ChatModel.class);
    var a = assess(3600, record(0, 3600, "social"));
    when(model.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("そろそろ区切りをつけようか。")))));
    var generator =
        new LlmBehaviorMessageGenerator(
            () -> model,
            () -> OpenAiChatOptions.builder().model("test").build(),
            () -> "既存のれいのキャラクター");
    assertEquals("そろそろ区切りをつけようか。", generator.generate(new BehaviorNotification("episode", a)));
    var request = org.mockito.ArgumentCaptor.forClass(Prompt.class);
    verify(model).call(request.capture());
    String system = request.getValue().getSystemMessage().getText(),
        user = request.getValue().getUserMessage().getText();
    assertTrue(system.contains("既存のれいのキャラクター"));
    assertTrue(system.contains("再判定・変更せず"));
    assertTrue(system.contains("締切"));
    assertTrue(user.contains("WARNING"));
    assertTrue(user.contains("60.0"));
    assertFalse(user.contains("observed context"));
    assertFalse(user.contains("foreground"));
    var options = (OpenAiChatOptions) request.getValue().getOptions();
    assertNull(options.getToolChoice());
    assertTrue(options.getToolCallbacks().isEmpty());
    assertEquals("test", options.getModel());
  }

  /** 作業習慣の支援で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void weakEvidenceOmitsSpecificServices() throws Exception {
    var model = mock(ChatModel.class);
    var a = assess(3600, record(0, 3600, "social"));
    a =
        new BehaviorAssessment(
            a.severity(),
            a.reason(),
            a.evaluatedAt(),
            a.continuousEntertainmentSeconds(),
            a.windows(),
            a.dominantCategories(),
            List.of("secret-service"),
            .55,
            a.latestObservedAt(),
            true,
            null,
            a.episodeStartedAt(),
            true);
    when(model.call(any(Prompt.class)))
        .thenAnswer(
            inv -> {
              assertFalse(
                  ((Prompt) inv.getArgument(0))
                      .getUserMessage()
                      .getText()
                      .contains("secret-service"));
              return new ChatResponse(
                  List.of(new Generation(new AssistantMessage("観測できた範囲では、少し長いみたい。"))));
            });
    new LlmBehaviorMessageGenerator(
            () -> model, () -> OpenAiChatOptions.builder().build(), () -> "persona")
        .generate(new BehaviorNotification("e", a));
  }

  /** 作業習慣の支援で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidWordingIsNotDelivered() {
    var model = mock(ChatModel.class);
    var generator =
        new LlmBehaviorMessageGenerator(
            () -> model, () -> OpenAiChatOptions.builder().build(), () -> "persona");
    var a = assess(3600, record(0, 3600, "social"));
    for (String text : List.of("", "怠けている", "あ".repeat(401))) {
      when(model.call(any(Prompt.class)))
          .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
      assertThrows(
          IllegalStateException.class, () -> generator.generate(new BehaviorNotification("e", a)));
    }
  }
}
