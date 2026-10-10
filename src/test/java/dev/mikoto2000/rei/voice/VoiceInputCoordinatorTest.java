package dev.mikoto2000.rei.voice;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import dev.mikoto2000.rei.application.input.*;
import dev.mikoto2000.rei.core.project.ProjectContext;

class VoiceInputCoordinatorTest {
  final Clock clock=Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"),ZoneOffset.UTC);
  final ConversationTarget target=new ConversationTarget(new ProjectContext(UUID.randomUUID().toString(),"test",Path.of(".")),"session");
  final AudioDevice device=new AudioDevice("device","test microphone","test","vendor","1");
  static final class Source implements MicrophoneCaptureService.FrameSource {
    final BlockingQueue<float[]> frames=new LinkedBlockingQueue<>();
    final AtomicInteger reads=new AtomicInteger();
    volatile boolean closed;
    public float[] readFrame() throws InterruptedException {
      var frame=frames.take();reads.incrementAndGet();return frame.length==0?null:frame;
    }
    public void close(){closed=true;frames.offer(new float[0]);}
    void speech(int utterances){
      for(int j=0;j<utterances;j++){
        for(int i=0;i<16;i++){var frame=new float[512];Arrays.fill(frame,.6f);frames.add(frame);}
        for(int i=0;i<60;i++)frames.add(new float[512]);
      }
    }
  }
  VoiceBackend backend(SpeechRecognizer recognizer,AtomicInteger vadClosed){
    return new VoiceBackend(new VoiceActivityDetector(){
      public float probability(float[] frame){return frame[0]>.2f?1:0;}
      public void close(){vadClosed.incrementAndGet();}
    },recognizer);
  }
  @Test void enabledWakePrefixIsRemovedButVoiceOwnershipAndLiteralTextRemain() {
    var source=new Source();var submitted=new CopyOnWriteArrayList<ConversationInput>();var count=new AtomicInteger();
    var options=new VoiceAdvancedOptions(true,"れい",false,false,VoiceAdvancedOptions.defaults().ttsVoice(),800);
    try(var voice=new VoiceInputCoordinator(d->source,s->backend(new SpeechRecognizer(){
      public String recognize(SpeechSegment segment){return count.incrementAndGet()==1?"れいめいを確認":"レイ、Ａ.txt を確認";}
      public void close(){}
    },new AtomicInteger()),submitted::add,new VoiceEventPublisher(),clock,t->true,System::nanoTime,new VoiceAudioGate(System::nanoTime),()->options)) {
      voice.start(target,device,VoiceSettings.defaults());source.speech(2);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(submitted).hasSize(1));
      assertThat(submitted.getFirst().text()).isEqualTo("Ａ.txt を確認");
      assertThat(submitted.getFirst().source()).isEqualTo(InputSource.VOICE);
      assertThat(submitted.getFirst().target()).isEqualTo(target);
    }
  }
  @Test void playbackDiscardsRecognitionAlreadyInFlightAndSuppressesCapturedEcho() throws Exception {
    var source=new Source();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    var submitted=new CopyOnWriteArrayList<ConversationInput>();var recognized=new AtomicInteger();
    var tick=new AtomicLong();var gate=new VoiceAudioGate(tick::get);
    try(var voice=new VoiceInputCoordinator(d->source,s->backend(new SpeechRecognizer(){
      public String recognize(SpeechSegment segment) throws Exception {recognized.incrementAndGet();entered.countDown();release.await();return "echo";}
      public void close(){}
    },new AtomicInteger()),submitted::add,new VoiceEventPublisher(),clock,t->true,System::nanoTime,gate)) {
      voice.start(target,device,VoiceSettings.defaults());source.speech(1);
      assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
      try(var playback=gate.playback(Duration.ofMillis(800))){
        source.speech(2);release.countDown();
        await().atMost(Duration.ofSeconds(3)).until(()->source.reads.get()>=228);
      }
      source.speech(1);await().atMost(Duration.ofSeconds(3)).until(()->source.reads.get()>=304);
      assertThat(submitted).isEmpty();assertThat(recognized).hasValue(1);
      tick.set(800_000_000L);source.speech(1);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(submitted).hasSize(1));
    } finally {release.countDown();}
  }
  @Test void stopInterruptsSlowNativeStartupWithoutOpeningMicrophone() throws Exception {
    var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var captures=new AtomicInteger();
    try(var voice=new VoiceInputCoordinator(d->{captures.incrementAndGet();return new Source();},s->{
      entered.countDown();try{Thread.sleep(60000);}catch(InterruptedException e){interrupted.countDown();throw e;}
      throw new AssertionError("startup should have been interrupted");
    },input->{},new VoiceEventPublisher(),clock)) {
      voice.start(target,device,VoiceSettings.defaults());assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
      voice.off();assertThat(interrupted.await(2,TimeUnit.SECONDS)).isTrue();
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF));
      assertThat(captures).hasValue(0);
    }
  }
  @Test void defaultIsOffAndUnselectedMicDoesNotOpenBackend(){
    var calls=new AtomicInteger();var source=new Source();
    try(var voice=new VoiceInputCoordinator(d->source,s->{calls.incrementAndGet();throw new IllegalStateException();},
        input->{},new VoiceEventPublisher(),clock)){
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
      assertThatThrownBy(()->voice.start(target,null,VoiceSettings.defaults())).isInstanceOf(IllegalArgumentException.class);
      assertThat(calls.get()).isZero();
    }
  }
  @Test void silenceAutomaticallySubmitsOneVoiceInputToCapturedTarget(){
    var source=new Source();var submitted=new CopyOnWriteArrayList<ConversationInput>();var closed=new AtomicInteger();
    try(var voice=new VoiceInputCoordinator(d->source,s->backend(new SpeechRecognizer(){
      public String recognize(SpeechSegment segment){return "こんにちは";}
      public void close(){}
    },closed),submitted::add,new VoiceEventPublisher(),clock)){
      voice.start(target,device,VoiceSettings.defaults());source.speech(1);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(submitted).hasSize(1));
      assertThat(submitted.getFirst().source()).isEqualTo(InputSource.VOICE);
      assertThat(submitted.getFirst().target()).isEqualTo(target);
      assertThat(submitted.getFirst().text()).isEqualTo("こんにちは");
      voice.off();
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF));
      assertThat(closed.get()).isEqualTo(1);assertThat(source.closed).isTrue();
    }
  }
  @Test void slowAsrDoesNotBlockCaptureAndSegmentQueueStaysBounded() throws Exception {
    var source=new Source();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    var events=new CopyOnWriteArrayList<VoiceEventPublisher.Event>();var publisher=new VoiceEventPublisher();publisher.subscribe(events::add);
    try(var voice=new VoiceInputCoordinator(d->source,s->backend(new SpeechRecognizer(){
      public String recognize(SpeechSegment segment) throws Exception {entered.countDown();release.await();return "hello";}
      public void close(){}
    },new AtomicInteger()),input->{},publisher,clock)){
      voice.start(target,device,VoiceSettings.defaults());source.speech(1);
      assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();source.speech(5);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(source.reads.get()).isGreaterThanOrEqualTo(324));
      assertThat(voice.queuedSegments()).isEqualTo(2);
      assertThat(events).anyMatch(event->event.type()==VoiceEventPublisher.Type.SEGMENT_QUEUE_FULL);
      voice.off();release.countDown();
    } finally {release.countDown();}
  }
  @Test void offDoesNotReleaseNativeResourcesDuringDecodeOrSubmitLateResult() throws Exception {
    var source=new Source();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    var closed=new AtomicInteger();var submitted=new CopyOnWriteArrayList<ConversationInput>();
    try(var voice=new VoiceInputCoordinator(d->source,s->backend(new SpeechRecognizer(){
      public String recognize(SpeechSegment segment) throws Exception{
        entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}
        return "late";
      }
      public void close(){closed.incrementAndGet();}
    },new AtomicInteger()),submitted::add,new VoiceEventPublisher(),clock)){
      voice.start(target,device,VoiceSettings.defaults());source.speech(1);assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
      voice.off();assertThat(closed.get()).isZero();assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.STOPPING);
      release.countDown();
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF));
      assertThat(closed.get()).isEqualTo(1);assertThat(submitted).isEmpty();
    } finally {release.countDown();}
  }
  @Test void nativeInitializationFailureLeavesCliAliveAndMicUnopened(){
    var opens=new AtomicInteger();
    try(var voice=new VoiceInputCoordinator(d->{opens.incrementAndGet();return new Source();},
        s->{throw new UnsatisfiedLinkError("JNI unavailable");},input->{},new VoiceEventPublisher(),clock)){
      voice.start(target,device,VoiceSettings.defaults());
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(opens.get()).isZero();voice.off();assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
    }
  }
  @Test void captureDisconnectStopsWithoutSwitchingDevice(){
    var source=new Source();var opens=new AtomicInteger();var released=new AtomicInteger();
    try(var voice=new VoiceInputCoordinator(d->{assertThat(d).isEqualTo(device);opens.incrementAndGet();return source;},
        s->backend(new SpeechRecognizer(){public String recognize(SpeechSegment segment){return "unused";}public void close(){}},released),
        input->{},new VoiceEventPublisher(),clock)){
      voice.start(target,device,VoiceSettings.defaults());source.frames.add(new float[0]);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(opens.get()).isEqualTo(1);assertThat(released.get()).isEqualTo(1);
    }
  }
  @Test void rejectedRecognitionCannotExecuteSlashCommands(){
    var source=new Source();var submitted=new CopyOnWriteArrayList<ConversationInput>();
    var events=new CopyOnWriteArrayList<VoiceEventPublisher.Event>();var publisher=new VoiceEventPublisher();publisher.subscribe(events::add);
    try(var voice=new VoiceInputCoordinator(d->source,s->backend(new SpeechRecognizer(){
      public String recognize(SpeechSegment segment){return " /voice on";}public void close(){}
    },new AtomicInteger()),submitted::add,publisher,clock)){
      voice.start(target,device,VoiceSettings.defaults());source.speech(1);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(events).anyMatch(e->e.type()==VoiceEventPublisher.Type.RESULT_REJECTED));
      assertThat(submitted).isEmpty();
    }
  }
  @Test void vadFailureStopsVoiceAndReleasesBothResources(){
    var source=new Source();var vadClosed=new AtomicInteger();var asrClosed=new AtomicInteger();
    try(var voice=new VoiceInputCoordinator(d->source,s->new VoiceBackend(new VoiceActivityDetector(){
      public float probability(float[] frame){throw new IllegalStateException("VAD failed");}public void close(){vadClosed.incrementAndGet();}
    },new SpeechRecognizer(){public String recognize(SpeechSegment segment){return "unused";}public void close(){asrClosed.incrementAndGet();}}),
        input->{},new VoiceEventPublisher(),clock)){
      voice.start(target,device,VoiceSettings.defaults());source.speech(1);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(vadClosed.get()).isEqualTo(1);assertThat(asrClosed.get()).isEqualTo(1);
    }
  }

  @Test void diagnosticDisplaysRecognitionWithoutSendingToAgent() {
    var source=new Source();var submitted=new CopyOnWriteArrayList<ConversationInput>();
    var events=new CopyOnWriteArrayList<VoiceEventPublisher.Event>();var publisher=new VoiceEventPublisher();publisher.subscribe(events::add);
    try(var voice=new VoiceInputCoordinator(d->source,s->backend(new SpeechRecognizer(){
      public String recognize(SpeechSegment segment){return "診断の発話";}public void close(){}
    },new AtomicInteger()),submitted::add,publisher,clock)){
      voice.startDiagnostic(target,device,VoiceSettings.defaults());source.speech(1);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(events)
          .anyMatch(e->e.type()==VoiceEventPublisher.Type.DIAGNOSTIC_RESULT && e.detail().equals("診断の発話")));
      assertThat(submitted).isEmpty();
      voice.off();
    }
  }
  @Test void offDuringNativeInitializationClosesEventuallyWithoutOpeningMic() throws Exception {
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var opened=new AtomicInteger();var closed=new AtomicInteger();
    try(var voice=new VoiceInputCoordinator(d->{opened.incrementAndGet();return new Source();},s->{
      entered.countDown();boolean interrupted=false;
      // Model a native call that cannot react to Java interruption until it returns.
      while(release.getCount()!=0){try{release.await();}catch(InterruptedException e){interrupted=true;}}
      if(interrupted)Thread.currentThread().interrupt();return backend(new SpeechRecognizer(){
        public String recognize(SpeechSegment segment){return "unused";}public void close(){}
      },closed);
    },input->{},new VoiceEventPublisher(),clock)){
      voice.start(target,device,VoiceSettings.defaults());assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
      voice.off();assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.STOPPING);
      release.countDown();
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF));
      assertThat(opened.get()).isZero();assertThat(closed.get()).isEqualTo(1);
    } finally { release.countDown(); }
  }
  @Test void stalledCaptureIsStoppedByWatchdogWithoutFallback() {
    var source=new Source();var closed=new AtomicInteger();
    try(var voice=new VoiceInputCoordinator(d->source,s->backend(new SpeechRecognizer(){
      public String recognize(SpeechSegment segment){return "unused";}public void close(){}
    },closed),input->{},new VoiceEventPublisher(),clock)){
      voice.start(target,device,VoiceSettings.defaults());
      await().atMost(Duration.ofSeconds(8)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(source.closed).isTrue();assertThat(closed.get()).isEqualTo(1);
    }
  }

  @Test void captureCloseFailureIsReportedAsFailedInsteadOfOff() {
    var released=new CountDownLatch(1);var closed=new AtomicInteger();
    var source=new MicrophoneCaptureService.FrameSource(){
      public float[] readFrame() throws Exception {released.await();return null;}
      public void close(){released.countDown();throw new IllegalStateException("close failed");}
    };
    try(var voice=new VoiceInputCoordinator(d->source,s->backend(new SpeechRecognizer(){
      public String recognize(SpeechSegment segment){return "unused";}public void close(){}
    },closed),input->{},new VoiceEventPublisher(),clock)){
      voice.start(target,device,VoiceSettings.defaults());
      assertThat(voice.awaitStartup(Duration.ofSeconds(3))).isEqualTo(VoiceInputCoordinator.State.LISTENING);
      voice.off();
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(closed.get()).isEqualTo(1);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints={127,1024,-1})
  void invalidCaptureFrameNeverReachesNativeVad(int kind) {
    var source=new Source();var nativeCalls=new AtomicInteger();
    float[] frame=new float[kind<0?512:kind];if(kind<0)frame[0]=Float.NaN;
    source.frames.add(frame);
    try(var voice=new VoiceInputCoordinator(d->source,s->new VoiceBackend(new VoiceActivityDetector(){
      public float probability(float[] value){nativeCalls.incrementAndGet();return 0;}public void close(){}
    },new SpeechRecognizer(){public String recognize(SpeechSegment segment){return "unused";}public void close(){}}),
      input->{},new VoiceEventPublisher(),clock)){
      voice.start(target,device,VoiceSettings.defaults());
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(nativeCalls.get()).isZero();
    }
  }
}