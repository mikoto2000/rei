package dev.mikoto2000.rei.interest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import dev.mikoto2000.rei.llm.*;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
class NativeInterestOptionsTest {
 @Test void extractionHasFreshNativeAuthorityAndAnAcceptedOutputBound() {
  var model=mock(ChatModel.class); var provider=mock(LlmModelProvider.class);
  var base=OpenAiResponsesChatOptions.builder().model("native-interest").maxOutputTokens(73).build();
  when(provider.requestOptions(LlmFeature.INTEREST_DISCOVERY,null,false)).thenReturn(base);
  when(provider.isolatedChatModel(LlmFeature.INTEREST_DISCOVERY)).thenReturn(model);
  when(model.stream(any(Prompt.class))).thenAnswer(inv->{
    Prompt prompt=inv.getArgument(0);var sent=(OpenAiResponsesChatOptions)prompt.getOptions();
    assertThat(sent.getMaxOutputTokens()).isEqualTo(73);assertThat(sent.getToolCallbacks()).isEmpty();
    assertThat(sent.getToolChoice()).isNull();
    assertThat(sent.getToolContext().get("rei.llm.replay-owner")).asString().startsWith("feature:");
    return reactor.core.publisher.Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("[]")))));
  });
  var extractor=new LlmInterestTopicExtractor(provider,mock(ModelHolderService.class),tools.jackson.databind.json.JsonMapper.builder().build());
  assertThat(extractor.extract(List.of(new ConversationSnippet("fixture","synthetic evidence",OffsetDateTime.now())),3)).isEmpty();
  verify(model).stream(any(Prompt.class));assertThat(base.getToolContext()).isNullOrEmpty();
 }
}
