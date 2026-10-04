package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import reactor.core.publisher.Flux;

class LlmConversationCompressorTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void summaryBudgetOverridesInheritedLimitWithoutChangingParameterFamily(boolean completionTokens) {
    var captured = new AtomicReference<Prompt>();
    var source = completionTokens
        ? OpenAiChatOptions.builder().model("configured").maxCompletionTokens(4096).build()
        : OpenAiChatOptions.builder().model("configured").maxTokens(4096).build();
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt prompt) {
        captured.set(prompt);
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("summary")))));
      }
    };
    var models = mock(LlmModelProvider.class);
    when(models.chatModel(LlmFeature.CHAT)).thenReturn(model);
    when(models.chatOptions(LlmFeature.CHAT, "configured")).thenReturn(source);
    @SuppressWarnings("unchecked") var provider = (ObjectProvider<LlmModelProvider>) mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(models);
    var compressor = new LlmConversationCompressor(provider, new ContextCompressionProperties());
    assertThat(compressor.summarize("", List.of(), 100, new Prompt("owner", source))).isEqualTo("summary");
    var options = (OpenAiChatOptions) captured.get().getOptions();
    assertThat(options.getModel()).isEqualTo("configured");
    assertThat(options.getToolCallbacks()).isEmpty();
    if (completionTokens) {
      assertThat(options.getMaxCompletionTokens()).isEqualTo(100);
      assertThat(options.getMaxTokens()).isNull();
      assertThat(source.getMaxCompletionTokens()).isEqualTo(4096);
    } else {
      assertThat(options.getMaxTokens()).isEqualTo(100);
      assertThat(options.getMaxCompletionTokens()).isNull();
      assertThat(source.getMaxTokens()).isEqualTo(4096);
    }
  }
}
