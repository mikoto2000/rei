package dev.mikoto2000.rei.core.chat;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.timing.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import reactor.core.publisher.Flux;
class ChatExecutionTimingTest {
 ChatExecutionService service(Flux<ChatResponse> stream) {
  var client=mock(ChatClient.class);var request=mock(ChatClient.ChatClientRequestSpec.class,RETURNS_DEEP_STUBS);var models=mock(ModelHolderService.class);
  when(models.get()).thenReturn("test");when(client.prompt(any(Prompt.class))).thenReturn(request);when(request.advisors(any(java.util.function.Consumer.class))).thenReturn(request);when(request.stream().chatResponse()).thenReturn(stream);
  return new ChatExecutionService(client,models,new CommandCancellationService(),Optional.empty());
 }
 final AgentRunContext owner=new AgentRunContext("run","session",java.nio.file.Path.of("."),"project");
 @Test void realChatBoundaryRecordsTerminalStateWithCaptureAbsent() {
  var store=new TimingStore(true,10,20,100,Duration.ofHours(1),Clock.systemUTC(),System::nanoTime);var chat=service(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))))));chat.setTiming(new TimingExecution(store));
  var result=chat.execute(owner,"question",new UserInterventionQueue());assertTrue(result.success());var recorded=store.latest("project","session").orElseThrow();assertEquals(TimingRecorder.Status.SUCCESS,recorded.status());assertFalse(recorded.toString().contains("answer"));assertFalse(recorded.toString().contains("question"));
 }
 @Test void recorderFailureDoesNotChangeRealChatFailureOrSuccess() {
  var recorder=mock(TimingRecorder.class);when(recorder.enabled()).thenReturn(true);when(recorder.beginRun(anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("PRIVATE"));
  var chat=service(Flux.error(new IllegalStateException("MODEL-PRIVATE")));chat.setTiming(new TimingExecution(recorder));assertFalse(chat.execute(owner,"question",new UserInterventionQueue()).success());
 }
}
