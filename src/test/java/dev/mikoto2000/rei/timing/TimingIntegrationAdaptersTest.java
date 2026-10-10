package dev.mikoto2000.rei.timing;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.event.*;
import static dev.mikoto2000.rei.timing.TimingRecorder.*;
import static org.junit.jupiter.api.Assertions.*;
class TimingIntegrationAdaptersTest {
 final AtomicLong tick=new AtomicLong();
 final TimingStore store=new TimingStore(true,10,10,100,Duration.ofHours(1),Clock.systemUTC(),tick::get);
 final AgentRunContext owner=new AgentRunContext("run","session",java.nio.file.Path.of("."),"project",AgentRunContext.RequestSource.SHELL);
 @Test void observedRunPreservesResultAndDoesNotStoreResponseOrErrors() {
  var execution=new TimingExecution(store);var response=ChatExecutionResult.success("RESPONSE-SECRET",false);
  assertSame(response,execution.observeRun(owner,()->{tick.set(10);return response;}));
  var run=store.snapshot("project","session","run").orElseThrow();assertEquals(10,run.elapsedNanos());assertEquals(Status.SUCCESS,run.status());
  assertFalse(run.toString().contains("RESPONSE-SECRET"));
 }
 @Test void exceptionsCancellationAndTimeoutAreObservedWithoutChangingControlFlow() {
  var execution=new TimingExecution(store);
  assertThrows(java.util.concurrent.CancellationException.class,()->execution.observeRun(owner,()->{throw new java.util.concurrent.CancellationException("PRIVATE");}));
  assertEquals(Status.CANCELLED,store.latest("project","session").orElseThrow().status());
  var timeoutOwner=new AgentRunContext("timeout","session",owner.projectRoot(),"project",owner.requestSource());
  execution.observeRun(timeoutOwner,()->ChatExecutionResult.failed("PRIVATE","run_timeout"));
  assertEquals(Status.TIMED_OUT,store.latest("project","session").orElseThrow().status());
 }
 @Test void anOuterRunKeepsNestedChatAndCompletionValidationInOneTimeline() {
  var execution=new TimingExecution(store);store.beginRun("run","project","session");
  execution.observeRun(owner,()->{tick.set(3);return ChatExecutionResult.success("OK",false);});
  assertEquals(Status.INCOMPLETE,store.latest("project","session").orElseThrow().status());
  var span=execution.startSpan("run","run",null,null,Category.COMPLETION_VALIDATION);
  tick.set(5);span.finish(Status.SUCCESS);store.finishRun("run",Status.SUCCESS);
  assertEquals(5,store.latest("project","session").orElseThrow().elapsedNanos());
  assertEquals(2,store.latest("project","session").orElseThrow().summary().occupancyNanos().get(Category.COMPLETION_VALIDATION));
 }
 @Test void toolAndNestedSearchUseExistingIdsWithoutCopyingPayloadContent() {
  var bus=new InMemoryAgentEventBus();var events=new AgentEventFactory(Clock.systemUTC());store.beginRun("run","project","session");
  try(var observer=new TimingEventObserver(store,bus)) {
   bus.publish(events.toolStarted("call","webSearch","ARGUMENT-SECRET").withOwnership(owner));
   tick.set(8);bus.publish(events.toolCompleted("call","webSearch",99999,"RESULT-SECRET").withOwnership(owner));
  }
  store.finishRun("run",Status.SUCCESS);var run=store.latest("project","session").orElseThrow();
  assertEquals(2,run.spans().size());assertEquals(8,run.summary().occupancyNanos().get(Category.TOOL));assertEquals(8,run.summary().occupancyNanos().get(Category.SEARCH));
  assertEquals(8,run.summary().occupiedNanos());assertFalse(run.toString().contains("SECRET"));
 }
 @Test void cliReadsCurrentSessionWithoutCreatingAnotherRunAndShowsUnknownMetricsExplicitly() {
  store.beginRun("run","project","session");tick.set(10);store.finishRun("run",Status.SUCCESS);
  var text=new StringWriter();var command=new CommandLine(new TimingCommand(store,()->"project",()->"session"));command.setOut(new PrintWriter(text));
  assertEquals(0,command.execute("last","--details"));assertTrue(text.toString().contains("未取得"));assertTrue(text.toString().contains("run"));
  assertEquals(1,store.statistics().runs());assertFalse(text.toString().contains("TPS: 0"));
  var other=new StringWriter();var isolated=new CommandLine(new TimingCommand(store,()->"other",()->"session"));isolated.setOut(new PrintWriter(other));
  assertEquals(0,isolated.execute("run"));assertFalse(other.toString().contains("SUCCESS"));
 }
 @Test void brokenOrDisabledRecorderCannotChangeTheBusinessResult() {
  var recorder=org.mockito.Mockito.mock(TimingRecorder.class);org.mockito.Mockito.when(recorder.enabled()).thenReturn(true);
  org.mockito.Mockito.when(recorder.beginRun(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("PRIVATE"));
  var result=ChatExecutionResult.success("OK",false);assertSame(result,new TimingExecution(recorder).observeRun(owner,()->result));
  var disabled=new TimingStore(false,2,2,2,Duration.ofHours(1),Clock.systemUTC(),()->{throw new AssertionError();});
  assertSame(result,new TimingExecution(disabled).observeRun(owner,()->result));assertEquals(0,disabled.statistics().runs());
 }
 @Test void cliWithoutSessionAndDisabledFlagDoesNotCreateSessionOrCallModel() {
  var text=new StringWriter();var command=new CommandLine(new TimingCommand(store,()->{throw new AssertionError("no project lookup");},()->null));command.setOut(new PrintWriter(text));assertEquals(0,command.execute());assertTrue(text.toString().contains("履歴はありません"));assertEquals(0,store.statistics().runs());
  var disabled=new TimingStore(false,2,2,2,Duration.ofHours(1),Clock.systemUTC(),()->{throw new AssertionError("no clock");});var off=new CommandLine(new TimingCommand(disabled,()->{throw new AssertionError();},()->{throw new AssertionError();}));off.setOut(new PrintWriter(new StringWriter()));assertEquals(0,off.execute());
 }
 @Test void rootFlagRemovesTimingCommandAndMalformedArgumentsAreRejected() throws Exception {
  var root=new dev.mikoto2000.rei.ui.shell.RootCommand();var flag=root.getClass().getDeclaredField("timingEnabled");flag.setAccessible(true);flag.set(root,false);
  var rootSpec=picocli.CommandLine.Model.CommandSpec.create();var command=new CommandLine(rootSpec);command.addSubcommand("timing",new TimingCommand(store,()->"project",()->"session"));root.configureCommands(command);assertFalse(command.getSubcommands().containsKey("timing"));
  var bad=new CommandLine(new TimingCommand(store,()->"project",()->"session"));var errors=new StringWriter();bad.setErr(new PrintWriter(errors));assertEquals(2,bad.execute("../PRIVATE"));assertFalse(errors.toString().contains("PRIVATE"));assertEquals(0,store.statistics().runs());
 }}
