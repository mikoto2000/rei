package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import dev.mikoto2000.rei.chatgpt.ChatGptDelegationGuidance;
import dev.mikoto2000.rei.core.service.*;
import reactor.core.publisher.Flux;

class ChatGptGuidanceInjectionTest {
  @Test void currentGuidanceReachesTheChatModelAsSystemContextWithTheHumanRequestUnchanged() {
    var client=mock(ChatClient.class);var request=mock(ChatClient.ChatClientRequestSpec.class,RETURNS_DEEP_STUBS);
    var holder=mock(ModelHolderService.class);when(holder.get()).thenReturn("test");
    when(client.prompt(any(Prompt.class))).thenReturn(request);
    when(request.advisors(any(java.util.function.Consumer.class))).thenReturn(request);
    when(request.stream().chatResponse()).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))))));
    var chat=new ChatExecutionService(client,holder,new CommandCancellationService(),Optional.empty());
    var guidance=mock(ChatGptDelegationGuidance.class);
    when(guidance.prompt()).thenReturn("ChatGPT delegation: delegateTask backendId=chatgpt-plan accountRef=default modelId=gpt-6.1-sol");
    chat.setDelegationGuidance(guidance);
    String human="全勉強会資料を ChatGPT にレビューして貰ってください";
    assertTrue(chat.execute(new AgentRunContext("run","session",Path.of("."),"project"),human,new UserInterventionQueue()).success());
    var prompt=ArgumentCaptor.forClass(Prompt.class);verify(client).prompt(prompt.capture());
    assertTrue(prompt.getValue().getInstructions().stream().anyMatch(m->m instanceof SystemMessage && m.getText().contains("backendId=chatgpt-plan")));
    assertEquals(human,prompt.getValue().getInstructions().getLast().getText());
  }
}
