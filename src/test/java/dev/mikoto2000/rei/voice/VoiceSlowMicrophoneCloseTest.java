package dev.mikoto2000.rei.voice;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.core.project.ProjectContext;
@Timeout(10)
class VoiceSlowMicrophoneCloseTest {
  @Test void offReturnsWhileDriverCloseIsBlockedButBackendIsHeldUntilCaptureRelease() throws Exception {
    var closeEntered=new CountDownLatch(1);var releaseClose=new CountDownLatch(1);var backendClosed=new AtomicBoolean();var closeCalls=new AtomicInteger();
    MicrophoneCaptureService capture=d->new MicrophoneCaptureService.FrameSource(){
      public float[] readFrame() throws Exception{Thread.sleep(10);return new float[512];}
      public void close(){closeCalls.incrementAndGet();closeEntered.countDown();boolean interrupted=false;
        while(releaseClose.getCount()>0)try{releaseClose.await();}catch(InterruptedException stop){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();}
    };
    VoiceBackendFactory backend=s->new VoiceBackend(new VoiceActivityDetector(){public float probability(float[] frame){return 0;}public void close(){backendClosed.set(true);}},new SpeechRecognizer(){public String recognize(SpeechSegment segment){return "unused";}public void close(){}});
    var voice=new VoiceInputCoordinator(capture,backend,i->{throw new AssertionError("Unexpected submission");},new VoiceEventPublisher(),Clock.systemUTC());
    var target=new ConversationTarget(new ProjectContext(UUID.randomUUID().toString(),"test",Path.of(".")),"session");
    var device=new AudioDevice("dry","DRY (VT-4)","input","vendor","1");
    var caller=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"off-fixture");thread.setDaemon(true);return thread;});
    try {
      voice.start(target,device,VoiceSettings.defaults());assertThat(voice.awaitStartup(Duration.ofSeconds(2))).isEqualTo(VoiceInputCoordinator.State.LISTENING);
      var stopped=caller.submit(voice::off);stopped.get(300,TimeUnit.MILLISECONDS);
      assertThat(closeEntered.await(2,TimeUnit.SECONDS)).isTrue();assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.STOPPING);
      assertThat(backendClosed.get()).isFalse();assertThatThrownBy(()->voice.start(target,device,VoiceSettings.defaults())).isInstanceOf(IllegalStateException.class);
    }finally {releaseClose.countDown();voice.close();caller.shutdownNow();}
    assertThat(closeCalls.get()).isEqualTo(1);assertThat(backendClosed.get()).isTrue();
  }
}
