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
  public static void main(String[] args) throws Exception {
    if(args.length!=5)throw new IllegalArgumentException("BUNDLE DATA_DIR NEW_READY_FILE EXACT_MIC_NAME EXTERNAL_CONFIG");
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
      "--spring.ai.mcp.client.enabled=false"};
    try(var context=app.run(settings)) {
      var voice=context.getBean(VoiceInputCoordinator.class);
      var devices=context.getBean(AudioDeviceService.class);
      var candidates=devices.devices().stream().filter(d->d.name().equals(args[3])).toList();
      if(candidates.size()!=1)throw new IllegalStateException("Missing or ambiguous explicitly selected microphone");
      devices.select(candidates.getFirst().id());
      var projects=context.getBean(ProjectService.class);
      var client=projects.newClient();
      try(var scope=client.open();var notifications=projects.notificationsFollow(client)) {
        var conversations=context.getBean(ShellConversationService.class);
        var target=conversations.captureTarget();
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
          System.out.println("ACCEPTANCE WAITING: microphone OFF; selected "+candidates.getFirst().name());
          long deadline=System.nanoTime()+Duration.ofMinutes(10).toNanos();
          while(!Files.exists(ready)) {
            if(System.nanoTime()>deadline)throw new IllegalStateException("Readiness timeout; microphone was never opened");
            Thread.sleep(100);
          }
          voice.start(target,devices.selected(),context.getBean(VoiceProperties.class).settings());
          if(voice.awaitStartup(Duration.ofSeconds(30))!=VoiceInputCoordinator.State.LISTENING)
            throw new IllegalStateException("Microphone/backend startup failed");
          System.out.println("ACCEPTANCE LISTENING: 20 seconds; speak the greeting; no audio file is saved");
          Thread.sleep(20000);
          voice.off();
          long stopDeadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
          while(voice.state()==VoiceInputCoordinator.State.STOPPING && System.nanoTime()<stopDeadline)Thread.sleep(50);
          if(voice.state()!=VoiceInputCoordinator.State.OFF)throw new IllegalStateException("Voice did not stop cleanly: "+voice.state());
          System.out.println("ACCEPTANCE MIC OFF: waiting for existing Agent response");
          var turns=context.getBean(ConversationTurnStore.class);
          if(turns.read(target.sessionId()).isEmpty())throw new IllegalStateException("No automatic voice input reached the existing Agent");
          if(!completed.await(180,TimeUnit.SECONDS)||failed.get())throw new IllegalStateException("Existing Agent response failed or timed out");
          var records=turns.read(target.sessionId());
          if(records.stream().noneMatch(t->t.status()==ConversationTurnStore.Status.COMPLETED
              &&t.assistantMessage()!=null&&!t.assistantMessage().isBlank()))
            throw new IllegalStateException("No completed nonblank Agent response");
          for(var turn:records)System.out.println("ACCEPTANCE TURN: "+turn.status()+" / recognized="+turn.request()+" / response="+turn.assistantMessage());
          System.out.println("ACCEPTANCE PASSED: real microphone -> raw VAD -> base INT8 -> common gateway -> existing Agent; no Enter submission");
        } finally { voice.close();subscription.unsubscribe(); }
      }
    }
  }
}