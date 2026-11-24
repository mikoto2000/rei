package dev.mikoto2000.rei.workcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class NativeWorkContextOptionsTest {
 @ParameterizedTest @CsvSource({"41,false","16000,false","16000,true"})
 void keepsTheNativeOutputBoundAndRejectsLocalTemplateOverrides(int providerLimit,boolean localOverride) {
  var models=mock(dev.mikoto2000.rei.llm.LlmModelProvider.class);
  var model=mock(ChatModel.class);
  var base=OpenAiResponsesChatOptions.builder().model("context-native").maxOutputTokens(providerLimit).build();
  when(models.isolatedChatModel("work-context")).thenReturn(model);
  when(models.requestOptions("work-context",null,false)).thenReturn(base);
  when(model.stream(any(Prompt.class))).thenAnswer(invocation->{
    Prompt prompt=invocation.getArgument(0);
    assertThat(prompt.getOptions()).isInstanceOf(OpenAiResponsesChatOptions.class);
    var sent=(OpenAiResponsesChatOptions)prompt.getOptions();
    assertThat(sent.getMaxOutputTokens()).isEqualTo(Math.min(providerLimit,512));
    assertThat(sent.getToolCallbacks()).isEmpty();
    assertThat(sent.getToolContext().get("rei.llm.replay-owner")).asString().startsWith("feature:");
    return reactor.core.publisher.Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"changes\":[]}")))));
  });
  var properties=new WorkContextProperties(false,true,1200,12000,2,20,4000,16,"work-context",0,2,512,localOverride?false:null);
  var extractor=new LlmWorkContextExtractor(models,properties);
  if(localOverride) {
    assertThatThrownBy(()->extractor.extract(List.of(),List.of(),"COMPLETED"))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("thinking template");
    verify(model,never()).stream(any(Prompt.class));
  } else assertThat(extractor.extract(List.of(),List.of(),"COMPLETED")).isEmpty();
  assertThat(base.getMaxOutputTokens()).isEqualTo(providerLimit);
  assertThat(base.getToolContext()).isNull();
 }
}
