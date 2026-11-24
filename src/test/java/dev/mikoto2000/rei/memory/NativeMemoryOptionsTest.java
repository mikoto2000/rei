package dev.mikoto2000.rei.memory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
class NativeMemoryOptionsTest {
 @Test void memoryUsesItsNativeSnapshotWithoutBorrowingConversationAuthority() {
   var models=mock(dev.mikoto2000.rei.llm.LlmModelProvider.class);
   var model=mock(ChatModel.class);
   when(models.memoryChatModel()).thenReturn(model);
   var base=OpenAiResponsesChatOptions.builder().model("memory-native").maxOutputTokens(321).build();
   when(models.requestOptions("memory",null,false)).thenReturn(base);
   var owners=new ArrayList<Object>();
   when(model.stream(any(Prompt.class))).thenAnswer(invocation->{
     Prompt prompt=invocation.getArgument(0);
     assertThat(prompt.getOptions()).isInstanceOf(OpenAiResponsesChatOptions.class);
     var sent=(OpenAiResponsesChatOptions)prompt.getOptions();
     assertThat(sent.getMaxOutputTokens()).isEqualTo(321);
     assertThat(sent.getToolCallbacks()).isEmpty();
     owners.add(sent.getToolContext().get("rei.llm.replay-owner"));
     assertThat(owners.getLast()).asString().startsWith("feature:");
     return reactor.core.publisher.Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("memory result")))));
   });
   var processor=new dev.mikoto2000.rei.memory.service.LlmMemoryProcessor(models,
     new dev.mikoto2000.rei.memory.configuration.MemoryProperties(true,20,80,10,3,2000,60,null));
   for(int request=0;request<2;request++) assertThat(processor.call("instructions","input",null)).isEqualTo("memory result");
   assertThat(owners).hasSize(2).doesNotHaveDuplicates();
   assertThat(base.getToolContext()).isNull();
   verify(models,never()).chatOptions(any(),any());
 }
}
