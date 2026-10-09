package dev.mikoto2000.rei;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.SpringApplication;
import dev.mikoto2000.rei.voice.*;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.ui.shell.*;

/** Explicit manual acceptance. No microphone opens until the new readiness marker appears. */
public final class VoiceAcceptance {
  /** Numeric counters; explicit comparison optionally retains bounded PCM in RAM only. */
  private static final class Diagnostics {
    long frames,samples,nonzero,speechFrames;
    double sumSquares,peak,peakProbability;
    int recognitions;
    final float[] pcm;
    int pcmCount,binFrames,binSamples,binSpeech;
    double frameEnergy,binEnergy,binVad;
    Diagnostics(boolean compareWholeClip){pcm=compareWholeClip?new float[28*16000]:null;}
    void compareWholeClip(IsolatedVoiceBackendFactory factory,VoiceSettings settings)throws Exception {
      if(pcm==null)return;
      if(pcmCount==0)throw new IllegalStateException("No PCM for comparison");
      try(var comparison=factory.open(settings)) {
        String result=comparison.recognizer().recognize(new SpeechSegment(UUID.randomUUID(),Arrays.copyOf(pcm,pcmCount),java.time.Instant.now()));
        System.out.println("ACCEPTANCE WHOLE-CLIP DIAGNOSTIC ONLY (not sent to Agent): seconds="+pcmCount/16000.0+" raw="+result);
      } finally {Arrays.fill(pcm,0);}
      if(factory.liveWorkers()!=0)throw new IllegalStateException("Comparison workers remain");
    }
    void frame(float[] frame) {
      if(pcm!=null){
        if(pcmCount+frame.length>pcm.length)throw new IllegalStateException("Diagnostic PCM bound exceeded");
        System.arraycopy(frame,0,pcm,pcmCount,frame.length);pcmCount+=frame.length;
      }
      frameEnergy=0;
      frames++;
      for(float sample:frame){samples++;if(sample!=0)nonzero++;sumSquares+=(double)sample*sample;frameEnergy+=(double)sample*sample;peak=Math.max(peak,Math.abs(sample));}
    }
    void probability(float probability,float threshold){
      peakProbability=Math.max(peakProbability,probability);if(probability>=threshold)speechFrames++;
      if(pcm==null)return;
      binFrames++;binSamples+=512;binEnergy+=frameEnergy;binVad=Math.max(binVad,probability);if(probability>=threshold)binSpeech++;
      if(binFrames==5){System.out.printf(java.util.Locale.ROOT,"ACCEPTANCE TRACE: endSeconds=%.3f rms=%.6f vadPeak=%.6f speechFrames=%d/5%n",samples/16000.0,Math.sqrt(binEnergy/binSamples),binVad,binSpeech);binFrames=binSamples=binSpeech=0;binEnergy=binVad=0;}
    }
    void print(){System.out.printf(java.util.Locale.ROOT,
      "ACCEPTANCE DIAGNOSTICS: frames=%d samples=%d nonzero=%d peak=%.6f rms=%.6f vadPeak=%.6f speechFrames=%d recognitions=%d%n",
      frames,samples,nonzero,peak,samples==0?0:Math.sqrt(sumSquares/samples),peakProbability,speechFrames,recognitions);}
  }
  public static void main(String[] args) throws Exception {
    if((args.length!=5&&args.length!=6)||(args.length==6&&!args[5].equals("--whole-clip-diagnostic")))throw new IllegalArgumentException("BUNDLE DATA_DIR NEW_READY_FILE EXACT_MIC_NAME EXTERNAL_CONFIG [--whole-clip-diagnostic]");
    Path data=Path.of(args[1]).toAbsolutePath();
    Path ready=Path.of(args[2]).toAbsolutePath();
    if(Files.exists(ready))throw new IllegalArgumentException("Use a fresh readiness marker");
    System.setProperty("rei.data-dir",data.toString());
    var app=new SpringApplication(ReiApplication.class);
    app.setDefaultProperties(ExternalConfigSupport.defaultProperties());
    dev.mikoto2000.rei.web.WebApplication.configure(app,null);
    // Prevent configured proactive services from making unrelated external actions in this trial.
    app.addInitializers(context -> context.addBeanFactoryPostProcessor(factory -> {
      if(factory instanceof org.springframework.beans.factory.support.BeanDefinitionRegistry registry) {
        String scheduled=org.springframework.scheduling.config.TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
        if(registry.containsBeanDefinition(scheduled))registry.removeBeanDefinition(scheduled);
      }
    }));
    String[] settings={
      "--spring.config.additional-location=optional:file:"+Path.of(args[4]).toAbsolutePath().toString().replace('\\','/'),
      "--rei.voice.bundle-directory="+Path.of(args[0]).toAbsolutePath(),
      "--rei.embedding.enabled=false","--rei.activity.enabled=false","--rei.interest.enabled=false",
      "--rei.topic-generator.enabled=false","--rei.sound-notification.enabled=false",
      "--rei.memory.auto-sleep.enabled=false","--rei.today.enabled=false","--rei.task-manager.enabled=false",
      "--spring.ai.mcp.client.enabled=false","--rei.voice.confirmation=false"};
    try(var context=app.run(settings)) {
      var diagnostics=new Diagnostics(args.length==6);
      var captureEnabled=new AtomicBoolean(args.length==5);
      var devices=context.getBean(AudioDeviceService.class);
      var candidates=devices.devices().stream().filter(d->d.name().equals(args[3])).toList();
      if(candidates.size()!=1)throw new IllegalStateException("Missing or ambiguous explicitly selected microphone");
      devices.select(candidates.getFirst().id());
      var projects=context.getBean(ProjectService.class);
      var client=projects.newClient();
      try(var scope=client.open();var notifications=projects.notificationsFollow(client)) {
        var conversations=context.getBean(ShellConversationService.class);
        var target=conversations.captureTarget();
        var backend=context.getBean(IsolatedVoiceBackendFactory.class);
        var delivery=context.getBean(VoiceDeliveryService.class);delivery.bind(client,target);
        var voice=new VoiceInputCoordinator(device -> {
          var source=new GuardedMicrophoneCapture(new JavaSoundMicrophoneCapture(),context.getBean(WindowsMicrophoneMonitor.class)).open(device);
          return new MicrophoneCaptureService.FrameSource() {
            public float[] readFrame() throws Exception {
              var frame=source.readFrame();
              if(frame!=null&&!captureEnabled.get())return new float[frame.length];
              if(frame!=null)diagnostics.frame(frame);
              return frame;
            }
            public void checkHealth() throws Exception{source.checkHealth();}
            public void close(){source.close();}
          };
        }, settingsValue -> {
          var nativeBackend=backend.open(settingsValue);
          return new VoiceBackend(new VoiceActivityDetector() {
            public float probability(float[] frame) throws Exception {
              float probability=nativeBackend.vad().probability(frame);
              if(captureEnabled.get())diagnostics.probability(probability,settingsValue.threshold());
              return probability;
            }
            public void close(){nativeBackend.close();}
          }, new SpeechRecognizer() {
            public String recognize(SpeechSegment segment) throws Exception {
              diagnostics.recognitions++;
              long started=System.nanoTime();
              String result=nativeBackend.recognizer().recognize(segment);
              System.out.println("ACCEPTANCE SEGMENT: seconds="+segment.samples().length/16000.0+" asrMs="+(System.nanoTime()-started)/1e6+" raw="+result);
              return result;
            }
            public void close(){}
          });
        }, delivery::accept,context.getBean(VoiceEventPublisher.class),context.getBean(java.time.Clock.class),delivery::targetIsCurrent,System::nanoTime);
        var completed=new CountDownLatch(1);
        var failed=new AtomicBoolean();
        var output=new ShellEventOutput(){
          public synchronized void print(String text){System.out.print(text);}
          public synchronized void println(String text){System.out.println(text);}
          public synchronized void flush(){System.out.flush();}
        };
        var renderer=new ShellAgentEventRenderer(output);
        var bus=context.getBean(AgentEventBus.class);
        var subscription=bus.subscribe(event->{
          if(!target.sessionId().equals(event.sessionId()))return;
          renderer.onEvent(event);
          if(event.payload() instanceof AgentRunCompletedPayload)completed.countDown();
          if(event.payload() instanceof AgentRunFailedPayload){failed.set(true);completed.countDown();}
        });
        try(var voiceSubscription=context.getBean(VoiceEventPublisher.class).subscribe(new VoiceShellEventRenderer(output))) {
          System.out.println("ACCEPTANCE MODEL: "+VoiceModelManifest.pinned().id()+" "+context.getBean(VoiceProperties.class).inference()+" "+context.getBean(VoiceProperties.class).settings());
          System.out.println("ACCEPTANCE COMPARISON: "+(args.length==6?"RAM-only whole clip enabled; comparison not sent":"disabled"));
          System.out.println("ACCEPTANCE WAITING: microphone OFF; selected "+candidates.getFirst().name());
          long deadline=System.nanoTime()+Duration.ofMinutes(10).toNanos();
          while(!Files.exists(ready)) {
            if(System.nanoTime()>deadline)throw new IllegalStateException("Readiness timeout; microphone was never opened");
            Thread.sleep(100);
          }
          voice.start(target,devices.selected(),context.getBean(VoiceProperties.class).settings());
          if(voice.awaitStartup(Duration.ofMinutes(4))!=VoiceInputCoordinator.State.LISTENING)
            throw new IllegalStateException("Microphone/backend startup failed");
          if(args.length==6){
          Path captureCue=ready.resolveSibling(ready.getFileName()+"-capture");
          if(Files.exists(captureCue))throw new IllegalArgumentException("Use a fresh capture cue");
          System.out.println("ACCEPTANCE ARMED: backend ready; real frames suppressed until fresh capture cue");
          long cueDeadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();
          while(!Files.exists(captureCue)) {
            if(System.nanoTime()>cueDeadline)throw new IllegalStateException("Capture cue timeout");
            Thread.sleep(50);
          }
          captureEnabled.set(true);
          }
          System.out.println("ACCEPTANCE LISTENING: 20 seconds; speak the greeting; no audio file is saved");
          Thread.sleep(20000);
          voice.off();
          long stopDeadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
          while(voice.state()==VoiceInputCoordinator.State.STOPPING && System.nanoTime()<stopDeadline)Thread.sleep(50);
          if(voice.state()!=VoiceInputCoordinator.State.OFF)throw new IllegalStateException("Voice did not stop cleanly: "+voice.state());
          diagnostics.print();if(backend.liveWorkers()!=0)throw new IllegalStateException("Voice inference workers remain after OFF");
          System.out.println("ACCEPTANCE MIC OFF: waiting for existing Agent response");
          diagnostics.compareWholeClip(backend,context.getBean(VoiceProperties.class).settings());
          var turns=context.getBean(ConversationTurnStore.class);
          if(turns.read(target.sessionId()).isEmpty())throw new IllegalStateException("No automatic voice input reached the existing Agent");
          if(!completed.await(180,TimeUnit.SECONDS)||failed.get())throw new IllegalStateException("Existing Agent response failed or timed out");
          // Completion events precede ChatExecutionService finally/turn persistence.
          // Wait for the terminal history as a separate observable acceptance condition.
          long historyDeadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
          var records=turns.read(target.sessionId());
          while(records.stream().noneMatch(t->t.status()==ConversationTurnStore.Status.COMPLETED
              &&t.assistantMessage()!=null&&!t.assistantMessage().isBlank())
              &&System.nanoTime()<historyDeadline) {
            Thread.sleep(50);
            records=turns.read(target.sessionId());
          }
          if(records.stream().noneMatch(t->t.status()==ConversationTurnStore.Status.COMPLETED
              &&t.assistantMessage()!=null&&!t.assistantMessage().isBlank()))
            throw new IllegalStateException("No completed nonblank Agent response");
          for(var turn:records)System.out.println("ACCEPTANCE TURN: "+turn.status()+" / recognized="+turn.request()+" / response="+turn.assistantMessage());
          System.out.println("ACCEPTANCE PIPELINE PASSED (accuracy/style require separate review): real microphone -> raw VAD -> turbo FP32 -> common gateway -> existing Agent; no Enter submission");
        } finally { voice.close();subscription.unsubscribe(); }
      }
    }
  }
}