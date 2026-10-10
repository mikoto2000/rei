package dev.mikoto2000.rei.timing;
import java.util.*;
import java.util.function.Supplier;
import dev.mikoto2000.rei.core.chat.*;
import static dev.mikoto2000.rei.timing.TimingRecorder.*;
import org.springframework.stereotype.Service;

/** Optional observation; no additional execution, permission or budget engine. */
@Service
public class TimingExecution {
 private final TimingRecorder recorder;
 public TimingExecution(TimingRecorder recorder){this.recorder=recorder;}
 public boolean enabled(){try{return recorder!=null&&recorder.enabled();}catch(RuntimeException unavailable){return false;}}
 public void safely(Runnable observation){try{observation.run();}catch(RuntimeException unavailable){/* Observation never changes the operation. */}}
 public ChatExecutionResult observeRun(AgentRunContext owner,Supplier<ChatExecutionResult> operation) {
  if(!enabled()||owner==null)return operation.get();
  boolean own=false;
  try{own=recorder.beginRun(owner.runId(),owner.projectId(),owner.conversationId());}catch(RuntimeException unavailable){ }
  Status status=Status.INCOMPLETE;
  try{var result=operation.get();status=status(result);return result;}
  catch(RuntimeException failure){status=status(failure);throw failure;}
  finally{if(own){var terminal=status;safely(()->recorder.finishRun(owner.runId(),terminal));}}
 }
 public Status status(ChatExecutionResult result) {
  if(result==null)return Status.INCOMPLETE;
  if(result.stopCode().equals("run_timeout")||result.stopCode().equals("timeout"))return Status.TIMED_OUT;
  return switch(result.status()){case SUCCESS->Status.SUCCESS;case FAILED->Status.FAILED;case CANCELLED->Status.CANCELLED;};
 }
 public static Status status(Throwable failure) {
  for(int depth=0;failure!=null&&depth<16;depth++,failure=failure.getCause()) {
   if(failure instanceof java.util.concurrent.CancellationException||failure instanceof InterruptedException)return Status.CANCELLED;
   if(failure instanceof java.util.concurrent.TimeoutException||failure instanceof java.net.http.HttpTimeoutException)return Status.TIMED_OUT;
   if(failure instanceof java.io.IOException||failure instanceof com.openai.errors.OpenAIIoException)return Status.DISCONNECTED;
   if(failure.getCause()==failure)break;
  }
  return Status.FAILED;
 }
 public SpanLease startSpan(String runId,String parent,String request,String attempt,Category category) {
  return startSpanWithId(runId,UUID.randomUUID().toString(),parent,request,attempt,category);
 }
 public SpanLease startSpanWithId(String runId,String id,String parent,String request,String attempt,Category category) {
  boolean recorded=false;
  if(enabled())try{recorded=recorder.beginSpan(runId,id,parent,request,attempt,category);}catch(RuntimeException unavailable){ }
  return new SpanLease(runId,id,recorded);
 }
 public final class SpanLease {
  private final String run,id;private final boolean recorded;
  private boolean finished;
  SpanLease(String run,String id,boolean recorded){this.run=run;this.id=id;this.recorded=recorded;}
  public String id(){return id;}
  public synchronized void finish(Status status){if(recorded&&!finished){finished=true;safely(()->recorder.endSpan(run,id,status));}}
  public void mark(Metric metric){if(recorded)safely(()->recorder.markSpan(run,id,metric));}
  public void usage(Long input,Long output,Long generated,Long duration){if(recorded)safely(()->recorder.usage(run,id,input,output,generated,duration));}
 }
 public void markRun(String runId,Metric metric){if(enabled())safely(()->recorder.markRun(runId,metric));}
}
