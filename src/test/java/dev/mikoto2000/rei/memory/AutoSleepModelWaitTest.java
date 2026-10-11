package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import dev.mikoto2000.rei.llm.*;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import reactor.core.publisher.Flux;

class AutoSleepModelWaitTest {
  final LlmModelProvider provider=mock(LlmModelProvider.class);
  final ChatModel model=mock(ChatModel.class);
  LlmMemoryProcessor processor(){
    when(provider.memoryChatModel()).thenReturn(model);
    when(provider.chatOptions(LlmFeature.MEMORY,null)).thenReturn(OpenAiChatOptions.builder().build());
    return new LlmMemoryProcessor(provider,new MemoryProperties(true,20,80,10,3,2000,60,null));
  }
  @Test void ownCompletionTimeoutAndOverallDeadlineUseDistinctCodes(){
    when(model.stream(any(Prompt.class))).thenReturn(Flux.never());
    var processor=processor();
    assertEquals("MODEL_TIMEOUT",assertThrows(AutoSleepExecution.Failure.class,()->processor.call("system","data",
        new AutoSleepExecution(Duration.ofMillis(30),Duration.ofSeconds(10)))).code);
    assertEquals("AUTO_SLEEP_DEADLINE_EXCEEDED",assertThrows(AutoSleepExecution.Failure.class,()->processor.call("system","data",
        new AutoSleepExecution(Duration.ofSeconds(10),Duration.ofMillis(30)))).code);
  }
  @Test void providerFailureIsNotInferredFromExceptionMessage(){
    when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("Timeout on blocking read")));
    assertEquals("MODEL_REQUEST_FAILED",assertThrows(AutoSleepExecution.Failure.class,()->processor().call("system","data",
        new AutoSleepExecution(Duration.ofMillis(30),Duration.ofSeconds(10)))).code);
  }
  @Test void interruptDuringExtendedWaitCancelsSubscription() throws Exception {
    var subscribed=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.<org.springframework.ai.chat.model.ChatResponse>never()
        .doOnSubscribe(s->subscribed.countDown()).doOnCancel(cancelled::countDown));
    var processor=processor();var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var thread=new Thread(()->{try{processor.call("system","data",new AutoSleepExecution(Duration.ofSeconds(480),Duration.ofSeconds(600)));}catch(Throwable e){error.set(e);}});
    thread.start();assertTrue(subscribed.await(2,TimeUnit.SECONDS));thread.interrupt();thread.join(2000);
    assertFalse(thread.isAlive());assertTrue(cancelled.await(2,TimeUnit.SECONDS));assertTrue(dev.mikoto2000.rei.core.chat.RunCancellation.isCancellation(error.get()));
  }
  @Test void timeoutRemainsFailureWhenUnknownUsageAlsoStopsBudget(){
    when(model.stream(any(Prompt.class))).thenReturn(Flux.never());
    var budget=mock(ModelCallBudget.class);
    doThrow(new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN)).when(budget).recordTotalTokens(null);
    var error=assertThrows(AutoSleepExecution.Failure.class,()->processor().call("system","data",new AutoSleepExecution(Duration.ofMillis(30),Duration.ofSeconds(10)).accounting(budget)));
    assertEquals("MODEL_TIMEOUT",error.code);assertTrue(java.util.Arrays.stream(error.getSuppressed()).anyMatch(e->e instanceof dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException));verify(budget).recordTotalTokens(null);
  }
}
