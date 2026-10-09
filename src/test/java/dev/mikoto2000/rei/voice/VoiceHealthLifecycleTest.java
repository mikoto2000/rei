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
class VoiceHealthLifecycleTest {
  final ConversationTarget target=new ConversationTarget(new ProjectContext(UUID.randomUUID().toString(),"test",Path.of(".")),"session");
  final AudioDevice device=new AudioDevice("device","test","description","vendor","1");
  static final class MutableClock extends Clock {
    final AtomicReference<Instant> time=new AtomicReference<>(Instant.parse("2026-10-09T08:00:00Z"));
    public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return time.get();}
  }
  VoiceBackend backend(SpeechRecognizer recognizer){return new VoiceBackend(new VoiceActivityDetector(){public float probability(float[] frame){return frame[0]>.2f?1:0;}public void close(){}},recognizer);}
  @Test void changedTargetStopsWithoutSendingLateDecodedText() throws Exception {
    var selected=new AtomicBoolean(true);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var submitted=new CopyOnWriteArrayList<ConversationInput>();
    var source=new VoiceInputCoordinatorTest.Source();var events=new CopyOnWriteArrayList<VoiceEventPublisher.Event>();var publisher=new VoiceEventPublisher();publisher.subscribe(events::add);
    try(var voice=new VoiceInputCoordinator(d->source,s->backend(new SpeechRecognizer(){
      public String recognize(SpeechSegment segment){entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}return "late";}public void close(){}
    }),submitted::add,publisher,new MutableClock(),t->selected.get(),System::nanoTime)) {
      voice.start(target,device,VoiceSettings.defaults());source.speech(1);assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();selected.set(false);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.STOPPING));release.countDown();
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF));
      assertThat(submitted).isEmpty();assertThat(events).anyMatch(e->e.type()==VoiceEventPublisher.Type.TARGET_CHANGED);assertThat(source.closed).isTrue();
    } finally {release.countDown();}
  }
  @Test void resumeGapStopsEvenWhenAsrFinishesBeforeNextCaptureFrame() throws Exception {
    var clock=new MutableClock();var ticks=new AtomicLong();var source=new VoiceInputCoordinatorTest.Source();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var submitted=new CopyOnWriteArrayList<ConversationInput>();
    var events=new CopyOnWriteArrayList<VoiceEventPublisher.Event>();var publisher=new VoiceEventPublisher();publisher.subscribe(events::add);
    try(var voice=new VoiceInputCoordinator(d->source,s->backend(new SpeechRecognizer(){
      public String recognize(SpeechSegment segment) throws Exception {entered.countDown();release.await();return "stale";}public void close(){}
    }),submitted::add,publisher,clock,t->true,ticks::get)) {
      voice.start(target,device,VoiceSettings.defaults());source.speech(1);assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
      clock.time.updateAndGet(t->t.plusSeconds(10));release.countDown();
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(submitted).isEmpty();assertThat(events).anyMatch(e->e.type()==VoiceEventPublisher.Type.CAPTURE_RESUMED);assertThat(source.closed).isTrue();
    } finally {release.countDown();}
  }
  @Test void deviceHealthFailureStopsEvenIfDriverKeepsReturningSilentFrames() {
    var healthy=new AtomicBoolean(true);var closed=new AtomicBoolean();var reads=new AtomicInteger();var publisher=new VoiceEventPublisher();var events=new CopyOnWriteArrayList<VoiceEventPublisher.Event>();publisher.subscribe(events::add);
    var source=new MicrophoneCaptureService.FrameSource(){
      public float[] readFrame() throws Exception {Thread.sleep(5);reads.incrementAndGet();return new float[512];}
      public void checkHealth(){if(!healthy.get())throw new IllegalStateException("endpoint gone");}
      public void close(){closed.set(true);}
    };
    try(var voice=new VoiceInputCoordinator(d->source,s->backend(new SpeechRecognizer(){public String recognize(SpeechSegment segment){return "";}public void close(){}}),i->{},publisher,new MutableClock())) {
      voice.start(target,device,VoiceSettings.defaults());await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(reads.get()).isGreaterThan(2));healthy.set(false);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(closed).isTrue();assertThat(events).anyMatch(e->e.type()==VoiceEventPublisher.Type.DEVICE_CHANGED);
    }
  }
  @Test void repeatedStartStopReleasesEachBackendAndCaptureExactlyOnce() {
    var sources=new CopyOnWriteArrayList<VoiceInputCoordinatorTest.Source>();var nativeClosed=new AtomicInteger();
    try(var voice=new VoiceInputCoordinator(d->{var source=new VoiceInputCoordinatorTest.Source();sources.add(source);return source;},s->backend(new SpeechRecognizer(){public String recognize(SpeechSegment segment){return "";}public void close(){nativeClosed.incrementAndGet();}}),i->{},new VoiceEventPublisher(),new MutableClock())) {
      for(int cycle=0;cycle<12;cycle++) {
        voice.start(target,device,VoiceSettings.defaults());await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.LISTENING));
        voice.off();await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF));
      }
      assertThat(nativeClosed).hasValue(12);assertThat(sources).hasSize(12).allMatch(source->source.closed);
    }
  }
}