package dev.mikoto2000.rei.activity.behavior;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static dev.mikoto2000.rei.activity.behavior.BehaviorEvaluatorTest.assess;
import static dev.mikoto2000.rei.activity.behavior.BehaviorEvaluatorTest.record;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
class NativeBehaviorOptionsTest {
 @Test void wordingUsesItsOwnNativeRequestWithoutChangingTheAssessment() throws Exception {
  var model=mock(ChatModel.class);
  var options=OpenAiResponsesChatOptions.builder().model("native-wording").maxOutputTokens(73).build();
  when(model.call(any(Prompt.class))).thenAnswer(inv->{
    Prompt prompt=inv.getArgument(0);var sent=(OpenAiResponsesChatOptions)prompt.getOptions();
    assertThat(sent.getToolCallbacks()).isEmpty();assertThat(sent.getToolChoice()).isNull();
    assertThat(sent.getMaxOutputTokens()).isEqualTo(73);
    assertThat(sent.getToolContext().get("rei.llm.replay-owner")).asString().startsWith("feature:");
    assertThat(prompt.getUserMessage().getText()).contains("WARNING");
    return new ChatResponse(List.of(new Generation(new AssistantMessage("ここで区切りをつけよう。"))));
  });
  var generator=LlmBehaviorMessageGenerator.class.getConstructor(Supplier.class,Supplier.class,Supplier.class)
      .newInstance((Supplier<ChatModel>)()->model,(Supplier<OpenAiResponsesChatOptions>)()->options,(Supplier<String>)()->"persona");
  assertThat(generator.generate(new BehaviorNotification("fixture",assess(3600,record(0,3600,"social")))))
      .isEqualTo("ここで区切りをつけよう。");
  assertThat(options.getToolContext()).doesNotContainKey("rei.llm.replay-owner");
 }
}
