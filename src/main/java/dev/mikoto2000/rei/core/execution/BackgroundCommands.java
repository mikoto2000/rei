package dev.mikoto2000.rei.core.execution;

import java.net.URI;
import java.time.*;
import java.util.function.*;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.summarize.*;
import dev.mikoto2000.rei.image.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.conversation.HistoryFormatter;

/** Command orchestration over the existing runtime registry/executor and project state store. */
@Service
public class BackgroundCommands {
  private final ConversationInputRouter runs;
  private final ProjectService projects;
  private final ProjectRunStateStore states;
  private final WebPageSummarizerService summaries;
  private final ImageGenerationService images;
  private final AgentEventFactory events;
  private final AgentEventPublisher publisher;
  private final Clock clock;
  private dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator narrator;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setNarrator(dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator narrator) { this.narrator=narrator; }
  public BackgroundCommands(ConversationInputRouter runs,ProjectService projects,ProjectRunStateStore states,
      WebPageSummarizerService summaries,ImageGenerationService images,AgentEventFactory events,AgentEventPublisher publisher,Clock clock) {
    this.runs=runs; this.projects=projects; this.states=states; this.summaries=summaries; this.images=images;
    this.events=events; this.publisher=publisher; this.clock=clock;
  }
  public ActiveExecution summarize(URI source) {
    return start(ExecutionType.SUMMARIZE,source.toString(),()->summaries.summarize(source).summary());
  }
  public ActiveExecution image(ImageGenerationRequest request) {
    return start(ExecutionType.IMAGE,request.prompt(),()->{
      var result=images.generate(request);
      if(!result.success()) {
        if("cancelled".equals(result.message())) throw new java.util.concurrent.CancellationException();
        throw new IllegalStateException(result.message());
      }
      return "画像生成プロンプト: "+result.prompt()+"\n画像を保存しました: "+result.savedPath();
    });
  }
  private ActiveExecution start(ExecutionType type,String source,Supplier<String> operation) {
    var project=projects.currentContext();
    var registered=new java.util.concurrent.atomic.AtomicReference<ActiveExecution>();
    try {
      return runs.submitBackground(project,type,source,e->{registered.set(e); publish(e,"RUNNING","");},e->{
        try {
          String result=operation.get();
          if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
          states.saveCompleted(CompletedExecution.of(e,source,result,clock.instant()));
          publish(e,"COMPLETED",result);
          if(type==ExecutionType.SUMMARIZE && narrator!=null) {
            try { runs.executeAuxiliary(()->{try(var scope=ExecutionScope.open(e)){narrator.narrateCompletedRun(result);}}); }
            catch(java.util.concurrent.RejectedExecutionException ignored) {}
          }
        } catch(Exception error) {
          publish(e,status(error),new HistoryFormatter().label(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()));
          org.slf4j.LoggerFactory.getLogger(getClass()).debug("Background execution failed: {}",e.id(),error);
        }
      });
    } catch(RuntimeException error) {
      if(registered.get()!=null) publish(registered.get(),"FAILED","Execution could not be started");
      throw error;
    }
  }
  private String status(Throwable error) {
    for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
      if(cause instanceof java.util.concurrent.CancellationException || cause instanceof InterruptedException) return "CANCELLED";
      if(cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException
          || cause instanceof java.net.SocketTimeoutException) return "TIMED_OUT";
    }
    return Thread.currentThread().isInterrupted()?"CANCELLED":error.getMessage()!=null&&error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timeout")?"TIMED_OUT":"FAILED";
  }
  private void publish(ActiveExecution execution,String status,String output) {
    try { publisher.publish(events.backgroundExecution(execution,status,output)); }
    catch(RuntimeException error) { org.slf4j.LoggerFactory.getLogger(getClass()).debug("Execution notification failed: {}",execution.id(),error); }
  }
  public void showLastSummary(Consumer<String> out) {
    var project=projects.currentContext(); var format=new HistoryFormatter();
    var active=runs.activeExecutions().stream().filter(e->e.type()==ExecutionType.SUMMARIZE&&project.id().equals(e.projectId())).toList();
    if(!active.isEmpty()) {
      out.accept("Summarize is currently running:");
      for(var execution:active) {
        long elapsed=Math.max(0,Duration.between(execution.startedAt(),clock.instant()).toSeconds());
        out.accept("  "+execution.id()+"  Source: "+format.label(execution.summary())+"  Elapsed: "+String.format("%02d:%02d",elapsed/60,elapsed%60));
      }
    }
    var latest=states.latestCompleted(project.id(),ExecutionType.SUMMARIZE);
    if(latest.isEmpty()) out.accept("No completed summary for project: "+format.label(project.name()));
    else {
      var result=latest.get();
      out.accept("Last completed summary:");
      out.accept("  Source: "+format.label(result.source()));
      out.accept("  Completed: "+format.timestamp(result.completedAt().toString()));
      out.accept(CredentialRedactor.redact(result.result()).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", ""));
    }
  }
}
