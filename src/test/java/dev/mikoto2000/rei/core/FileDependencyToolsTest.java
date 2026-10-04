package dev.mikoto2000.rei.core;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.dependency.*;
import dev.mikoto2000.rei.goal.FileGoalVerifier;

class FileDependencyToolsTest {
  @TempDir Path dir;
  FileDependencyTools tools(){var tools=new FileDependencyTools(new FileGoalVerifier());var time=new AtomicLong();
    tools.setDependencyAwaiter(new DependencyAwaiter(time::get,d->time.addAndGet(d.toNanos())));return tools;}
  @Test void existenceDigestAndMissingAreIndependentFacts() throws Exception {
    Files.writeString(dir.resolve("result.txt"),"hello");
    try(var scope=AgentRunScope.open(new AgentRunContext("r","s",dir,"p"))) {
      var tools=tools();assertEquals(DependencyState.COMPLETED,tools.waitForFile("result.txt",null,0,null).state());
      assertEquals(DependencyState.COMPLETED,tools.waitForFile("result.txt","2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",0,null).state());
      assertEquals(DependencyState.WAITING,tools.waitForFile("result.txt","0".repeat(64),1,null).state());
      assertEquals(DependencyState.WAITING,tools.waitForFile("absent",null,1,null).state());
    }
  }
  @Test void boundaryAndMalformedInputsCannotClaimCompletion() {
    var tools=tools();assertThrows(IllegalArgumentException.class,()->tools.waitForFile("x",null,0,null));
    try(var scope=AgentRunScope.open(new AgentRunContext("r","s",dir,"p"))) {
      assertEquals(DependencyState.BLOCKED,tools.waitForFile("../outside",null,0,null).state());
      assertEquals(DependencyState.BLOCKED,tools.waitForFile(dir.resolve("absolute").toString(),null,0,null).state());
      assertThrows(IllegalArgumentException.class,()->tools.waitForFile("x","bad",0,null));
      assertThrows(IllegalArgumentException.class,()->tools.waitForFile("x",null,61,null));
    }
  }
  @Test void callbackHidesContextAndVerifiedWaitingDoesNotCountAsStagnation() throws Exception {
    var tools=tools();var execution=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("r",new dev.mikoto2000.rei.llm.OutputLimitRunBudget(1,10),
        new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(dir),new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),event->{});
    var callback=org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks()[0];
    assertFalse(callback.getToolDefinition().inputSchema().contains("toolContext"));
    var required=new com.fasterxml.jackson.databind.ObjectMapper().readTree(callback.getToolDefinition().inputSchema()).path("required").toString();
    assertFalse(required.contains("expectedSha256"));assertFalse(required.contains("timeoutSeconds"));execution.beginIteration();
    try(var scope=AgentRunScope.open(new AgentRunContext("r","s",dir,"p"))) {
      var before=execution.evaluator().beforeTool("waitForFile","{}");
      var result=callback.call("{\"relativeFile\":\"missing\",\"timeoutSeconds\":0}",new org.springframework.ai.chat.model.ToolContext(Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY,execution)));
      assertTrue(result.contains("WAITING"));execution.recordTool("waitForFile","{}",result,before);execution.endIteration();
      assertEquals(0,execution.detector().stagnationCount());
    }
  }
  @Test void existenceWatcherCannotWeakenGoalDigestCriteria() throws Exception {
    Files.writeString(dir.resolve("file"),"value");
    var goal=new dev.mikoto2000.rei.goal.GoalRepository.Goal("id","p",dir.toString(),"s","goal","file",null,1,1,0,0,"READY",null,"");
    assertFalse(new FileGoalVerifier().verify(goal).satisfied());
  }
  @Test void actualFileCreationDuringPollingCompletesAndOversizedFileIsBlocked() throws Exception {
    var tools=new FileDependencyTools(new FileGoalVerifier());var time=new AtomicLong();
    tools.setDependencyAwaiter(new DependencyAwaiter(time::get,d->{
      time.addAndGet(d.toNanos());try {Files.writeString(dir.resolve("created"),"ready");}catch(java.io.IOException error){throw new IllegalStateException(error);}
    }));
    try(var scope=AgentRunScope.open(new AgentRunContext("r","s",dir,"p"))) {
      assertEquals(DependencyState.COMPLETED,tools.waitForFile("created",null,1,null).state());
      Files.write(dir.resolve("large"),new byte[1_048_577]);assertEquals(DependencyState.BLOCKED,tools.waitForFile("large",null,0,null).state());
      Thread.currentThread().interrupt();
      try {assertThrows(java.util.concurrent.CancellationException.class,()->tools.waitForFile("missing",null,1,null));}
      finally {Thread.interrupted();}
    }
  }
}
