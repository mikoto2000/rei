package dev.mikoto2000.rei.voice;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
class VoicePlaybackServiceTest {
  AgentRunContext context(){return new AgentRunContext("run","session",Path.of("."),"project").asVoiceInput();}
  @Test void queueIsBoundedAndStopDiscardsQueuedReplies() throws Exception {
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    var calls=new AtomicInteger();var events=new java.util.concurrent.CopyOnWriteArrayList<VoiceEventPublisher.Event>();
    var publisher=new VoiceEventPublisher();publisher.subscribe(events::add);
    VoiceSpeechOutput output=(voice,text,current)->{calls.incrementAndGet();entered.countDown();release.await();};
    var options=new VoiceAdvancedOptions(false,"れい",false,true,VoiceAdvancedOptions.defaults().ttsVoice(),800);
    try(var service=new VoicePlaybackService(output,new VoiceAudioGate(System::nanoTime),()->options,c->true,publisher)){
      service.offer(context(),"first");assertThat(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      service.offer(context(),"second");service.offer(context(),"third");service.offer(context(),"private fourth");
      assertThat(events).anyMatch(e->e.type()==VoiceEventPublisher.Type.TTS_DROPPED);
      assertThat(events).noneMatch(e->e.detail().contains("private"));
      service.stop();release.countDown();
    } finally {release.countDown();}
    assertThat(calls).hasValue(1);
  }
  @Test void defaultOffAndUnownedRepliesNeverReachOutput(){
    var calls=new AtomicInteger();var options=new AtomicReference<>(VoiceAdvancedOptions.defaults());
    VoiceSpeechOutput output=(voice,text,current)->calls.incrementAndGet();
    try(var service=new VoicePlaybackService(output,new VoiceAudioGate(System::nanoTime),options::get,c->false,new VoiceEventPublisher())){
      assertThat(service.offer(context(),"hello")).isFalse();
      options.set(new VoiceAdvancedOptions(false,"れい",false,true,options.get().ttsVoice(),800));
      assertThat(service.offer(context(),"hello")).isFalse();assertThat(calls).hasValue(0);
    }
  }
  @Test void ownedVoiceUsesSuppressionAndSelectionChangeStopsIt(){
    var gate=new VoiceAudioGate(System::nanoTime);var selected=new AtomicBoolean(true);var entered=new AtomicBoolean();var exited=new AtomicBoolean();
    VoiceSpeechOutput output=(voice,text,current)->{
      assertThat(gate.suppressed()).isTrue();entered.set(true);
      while(current.getAsBoolean())Thread.sleep(10);exited.set(true);
    };
    var options=new VoiceAdvancedOptions(false,"れい",false,true,VoiceAdvancedOptions.defaults().ttsVoice(),800);
    try(var service=new VoicePlaybackService(output,gate,()->options,c->selected.get(),new VoiceEventPublisher())){
      assertThat(service.offer(context(),"hello")).isTrue();
      await().atMost(Duration.ofSeconds(2)).until(entered::get);selected.set(false);
      await().atMost(Duration.ofSeconds(2)).until(exited::get);
      assertThat(gate.suppressed()).isTrue();
    }
  }
  @Test void keyboardResponsesAndLongMessagesAreNotSpoken(){
    var calls=new AtomicInteger();VoiceSpeechOutput output=(voice,text,current)->calls.incrementAndGet();
    var options=new VoiceAdvancedOptions(false,"れい",false,true,VoiceAdvancedOptions.defaults().ttsVoice(),800);
    try(var service=new VoicePlaybackService(output,new VoiceAudioGate(System::nanoTime),()->options,c->true,new VoiceEventPublisher())){
      assertThat(service.offer(new AgentRunContext("keyboard","session",Path.of("."),"project"),"hello")).isFalse();
      assertThat(service.offer(context(),"a".repeat(16385))).isTrue();assertThat(calls).hasValue(0);
    }
  }
}
