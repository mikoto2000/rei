package dev.mikoto2000.rei.core.chat;

import java.util.concurrent.*;
import org.springframework.context.annotation.*;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;

@Configuration
public class AgentRunConfiguration {
  private org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.voice.VoicePlaybackService> voicePlayback;
  @org.springframework.beans.factory.annotation.Autowired
  public void setVoicePlayback(org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.voice.VoicePlaybackService> playback){voicePlayback=playback;}

  @Bean
  public dev.mikoto2000.rei.application.session.ShellConversationService shellConversations(
      dev.mikoto2000.rei.core.project.ProjectService projects,
      dev.mikoto2000.rei.application.session.SessionLifecycle lifecycle, ConversationInputRouter router,
      @org.springframework.beans.factory.annotation.Value("${rei.conversation.concurrent-enabled:false}") boolean enabled,
      @org.springframework.beans.factory.annotation.Value("${rei.task-manager.enabled:false}") boolean tasksEnabled,
      @org.springframework.beans.factory.annotation.Value("${rei.today.enabled:false}") boolean todayEnabled,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.application.run.RunRegistry> registry,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.application.run.RunService> service) {
    var shell=new dev.mikoto2000.rei.application.session.ShellConversationService(projects, lifecycle,(context,prompt)->{
      var runs=registry.getIfAvailable();var runner=service.getIfAvailable();
      if(!(enabled||tasksEnabled||todayEnabled) || runs==null || runner==null){router.submit(context,prompt);return;}
      runs.register(context);
      try {router.submit(context,prompt,work->runner.execute(context,work));}
      catch(RuntimeException|Error error){runs.forget(context.runId());throw error;}
    },enabled,router);
    shell.onPendingCancellation(context->{
      var runner=service.getIfAvailable();var runs=registry.getIfAvailable();
      if((enabled||tasksEnabled||todayEnabled) && runner!=null && runs!=null)return runner.cancelQueuedOnly(context.runId()).accepted();
      return router.cancelQueued(context.runId());
    });
    return shell;
  }
  @Bean(destroyMethod = "shutdownNow")
  public ExecutorService agentRunExecutor() {
    return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rei-agent-", 0).factory());
  }

  @Bean
  public ConversationInputRouter conversationInputRouter(ExecutorService agentRunExecutor,
      ChatExecutionService execution, ChatResponseNarrator narrator,
      dev.mikoto2000.rei.event.AgentEventFactory events, dev.mikoto2000.rei.event.AgentEventPublisher publisher) {
    return new ConversationInputRouter(agentRunExecutor, (context, prompt, queue) -> {
      try {
        var result = execution.execute(context, prompt, queue);
        if (result.success() && context.requestSource() == AgentRunContext.RequestSource.SHELL) {
          var playback=voicePlayback==null?null:voicePlayback.getIfAvailable();
          if(playback!=null&&playback.enabledFor(context)) {
            playback.offer(context,result.text());return;
          }
          // Audio can block for minutes; it is not an active AgentRun and must not hold its mailbox open.
          try {
            agentRunExecutor.execute(() -> {
              try (var scope = AgentRunScope.open(context)) {
                narrator.narrateCompletedRun(result.text());
              }
            });
          }
          catch (RejectedExecutionException closing) {
            org.slf4j.LoggerFactory.getLogger(AgentRunConfiguration.class).debug("Narration skipped during shutdown");
          }
        }
      } catch (RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(AgentRunConfiguration.class).error("Agent run failed: {}", context.runId(), error);
        publisher.publish(events.runFailed(context.runId(), dev.mikoto2000.rei.event.ErrorInformation.from(error)).withOwnership(context));
      }
    }, (context, entry) -> publisher.publish(events.intervention(context.runId(), entry.id(), entry.text(), false).withOwnership(context)));
  }
}
