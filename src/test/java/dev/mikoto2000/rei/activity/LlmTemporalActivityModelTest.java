package dev.mikoto2000.rei.activity;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.openai.OpenAiChatOptions;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmTemporalActivityModelTest {
  static ChatResponse response(String text,Integer tokens) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),tokens==null?ChatResponseMetadata.builder().build():
        ChatResponseMetadata.builder().usage(new DefaultUsage(1,tokens-1)).build());
  }
  @Test void boundedNoToolRequestAccountsActualTokens() throws Exception {
    var chat=mock(ChatModel.class);when(chat.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(response("{\"activity\":\"テスト修正作業の可能性があります\",\"confidence\":0.6}",100)));
    var model=new LlmTemporalActivityModel(()->chat,()->OpenAiChatOptions.builder().build());
    var output=model.infer("{}",128,1,200);assertEquals(100,output.totalTokens());assertEquals(.6,output.confidence());
    var prompt=org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);verify(chat).stream(prompt.capture());
    var options=(OpenAiChatOptions)prompt.getValue().getOptions();assertEquals(128,options.getMaxCompletionTokens());assertTrue(options.getToolCallbacks().isEmpty());
    assertTrue(prompt.getValue().getSystemMessage().getText().contains("actor=REI"));
  }
  @Test void missingOrExceededTokensBlocksRetryAndParentBudgetIsInherited() {
    for(Integer tokens:Arrays.asList(null,300)) {
      var chat=mock(ChatModel.class);when(chat.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(response("{\"activity\":\"推定不能\",\"confidence\":0}",tokens)));
      var model=new LlmTemporalActivityModel(()->chat,()->OpenAiChatOptions.builder().build());
      var parent=mock(dev.mikoto2000.rei.llm.ModelCallBudget.class);
      try(var scope=dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(parent)) {
        assertThrows(TemporalActivityInferenceService.BudgetFailure.class,()->model.infer("{}",128,1,200));verify(parent).run();verify(parent).recordTotalTokens(tokens);
      }
    }
  }
  @Test void rejectsTrailingOrAdditionalFieldsInsteadOfUsingUnstructuredClaims() {
    var chat=mock(ChatModel.class);when(chat.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(response("{\"activity\":\"claim\",\"confidence\":0.6,\"userExecuted\":true}",100)));
    assertThrows(IllegalArgumentException.class,()->new LlmTemporalActivityModel(()->chat,()->OpenAiChatOptions.builder().build()).infer("{}",128,1,200));
  }
}

