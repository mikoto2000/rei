package dev.mikoto2000.rei;

import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.SpringApplication;
import dev.mikoto2000.rei.voice.*;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.application.session.ShellConversationService;

/** Explicit real-microphone echo trial: fresh markers, no Agent input and no audio files. */
public final class VoiceEchoAcceptance {
  public static void main(String[] args)throws Exception {
    if(args.length!=5)throw new IllegalArgumentException("BUNDLE DATA READY EXACT_MIC EXTERNAL_CONFIG");
    var ready=Path.of(args[2]).toAbsolutePath();var cue=ready.resolveSibling(ready.getFileName()+"-capture");
    if(Files.exists(ready)||Files.exists(cue))throw new IllegalArgumentException("Fresh markers required");
    System.setProperty("rei.data-dir",Path.of(args[1]).toAbsolutePath().toString());
    var app=new SpringApplication(ReiApplication.class);app.setDefaultProperties(ExternalConfigSupport.defaultProperties());
    dev.mikoto2000.rei.web.WebApplication.configure(app,null);
    app.addInitializers(context->context.addBeanFactoryPostProcessor(factory->{
      if(factory instanceof org.springframework.beans.factory.support.BeanDefinitionRegistry registry){
        String name=org.springframework.scheduling.config.TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
        if(registry.containsBeanDefinition(name))registry.removeBeanDefinition(name);
      }
    }));
    try(var context=app.run("--spring.config.additional-location=optional:file:"+Path.of(args[4]).toAbsolutePath().toString().replace('\\','/'),
        "--rei.voice.bundle-directory="+Path.of(args[0]).toAbsolutePath(),
        "--spring.shell.interactive.enabled=false","--spring.shell.noninteractive.enabled=false",
        "--rei.embedding.enabled=false","--rei.activity.enabled=false","--rei.interest.enabled=false",
        "--rei.topic-generator.enabled=false","--rei.sound-notification.enabled=false",
        "--rei.memory.auto-sleep.enabled=false","--rei.today.enabled=false","--rei.task-manager.enabled=false",
        "--spring.ai.mcp.client.enabled=false")) {
      var voice=context.getBean(VoiceInputCoordinator.class);var gate=context.getBean(VoiceAudioGate.class);
      var events=context.getBean(VoiceEventPublisher.class);var nativeBackend=context.getBean(IsolatedVoiceBackendFactory.class);
      var devices=context.getBean(AudioDeviceService.class);
      var matches=devices.devices().stream().filter(d->d.name().equals(args[3])).toList();
      if(matches.size()!=1)throw new IllegalStateException("Missing or ambiguous selected microphone");
      var device=matches.getFirst();devices.select(device.id());
      context.getBean(WindowsMicrophoneMonitor.class).reselect(device.id());
      var projects=context.getBean(ProjectService.class);var client=projects.newClient();
      var results=new AtomicInteger();var faults=new AtomicInteger();
      try(var scope=client.open();var observer=events.subscribe(event->{
        if(event.type()==VoiceEventPublisher.Type.DIAGNOSTIC_RESULT)results.incrementAndGet();
        if(event.type()==VoiceEventPublisher.Type.CAPTURE_FAILED||event.type()==VoiceEventPublisher.Type.BACKEND_FAILED
            ||event.type()==VoiceEventPublisher.Type.RECOGNITION_FAILED||event.type()==VoiceEventPublisher.Type.DEVICE_CHANGED
            ||event.type()==VoiceEventPublisher.Type.RELEASE_FAILED||event.type()==VoiceEventPublisher.Type.TARGET_CHANGED
            ||event.type()==VoiceEventPublisher.Type.CAPTURE_RESUMED)faults.incrementAndGet();
      })) {
        var shell=context.getBean(ShellConversationService.class);var target=shell.captureTarget();
        context.getBean(VoiceDeliveryService.class).bind(client,target);
        System.out.println("ECHO WAITING: microphone OFF; selected "+device.name()+"; fresh readiness required");System.out.flush();
        awaitMarker(ready,Duration.ofMinutes(10));
        var preload=gate.playback(Duration.ZERO);
        try {
          // Warm the verified backend without opening the microphone. The cue wait
          // must not consume the coordinator's fixed 20-second diagnostic deadline.
          try(var warmed=nativeBackend.open(context.getBean(VoiceProperties.class).settings())) {}
          if(nativeBackend.liveWorkers()!=0)throw new IllegalStateException("Warmup workers did not exit");
          System.out.println("ECHO ARMED: backend verified; microphone OFF; cue required within 10 minutes");System.out.flush();
          awaitMarker(cue,Duration.ofMinutes(10));
          voice.startDiagnostic(target,device,context.getBean(VoiceProperties.class).settings());
          if(voice.awaitStartup(Duration.ofMinutes(4))!=VoiceInputCoordinator.State.LISTENING)
            throw new IllegalStateException("Diagnostic startup failed");
          if(voice.state()!=VoiceInputCoordinator.State.LISTENING)throw new IllegalStateException("Diagnostic expired before playback");
          try(var playback=gate.playback(Duration.ofMillis(800))) {
            preload.close();
            context.getBean(SapiVoiceOutput.class).speak("Microsoft Haruka Desktop - Japanese",
                "こんにちは。音声入力のテストです。短く挨拶してください。",
                ()->voice.state()==VoiceInputCoordinator.State.LISTENING);
            if(voice.state()!=VoiceInputCoordinator.State.LISTENING)throw new IllegalStateException("Playback was interrupted");
            System.out.println("ECHO PLAYBACK COMPLETED");
          }
          long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
          while(voice.state()!=VoiceInputCoordinator.State.OFF&&voice.state()!=VoiceInputCoordinator.State.FAILED){
            if(System.nanoTime()-deadline>=0)throw new IllegalStateException("Diagnostic cleanup timeout");Thread.sleep(100);
          }
          System.out.println("ECHO RESULT: recognized="+results.get()+" faults="+faults.get()+" state="+voice.state()+" nativeWorkers="+nativeBackend.liveWorkers());
          if(results.get()!=0||faults.get()!=0||voice.state()!=VoiceInputCoordinator.State.OFF||nativeBackend.liveWorkers()!=0)
            throw new IllegalStateException("Echo acceptance did not pass; inspect numeric diagnostics");
          System.out.println("ECHO PASS: no Agent input; no audio files");
        } finally {preload.close();voice.off();}
      }
    }
  }
  private static void awaitMarker(Path marker,Duration timeout)throws Exception {
    long deadline=System.nanoTime()+timeout.toNanos();
    while(!Files.exists(marker)){
      if(System.nanoTime()-deadline>=0)throw new IllegalStateException("Fresh marker timeout; no successful acceptance claimed");Thread.sleep(50);
    }
  }
}
