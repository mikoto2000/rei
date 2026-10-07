package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Tag("integration")
class SubAgentToolsTest {
  @Test void durableCallbacksUseActualRunIdentityRequestAndSharedBudget()throws Exception {
    var registry=new SubAgentRegistry(directory,new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()),m->true));
    var runner=mock(SubAgentRunner.class);var tools=new SubAgentTools(runner,registry);
    var owner=new dev.mikoto2000.rei.core.chat.AgentRunContext("parent","session",directory,"project");
    var factory=new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC());
    var run=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("parent",new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0,3),null,factory,event->{});
    run.setRunContext(owner);run.setUserRequest("subagent resume child-id 7");
    var context=new org.springframework.ai.chat.model.ToolContext(java.util.Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY,run));
    when(runner.resumeDurable(eq(owner),eq("child-id"),eq(7L),eq(run.userRequest()),any())).thenReturn(new SubAgentResult("reviewer","child-run",SubAgentResult.Status.COMPLETED,"done",Instant.now(),Instant.now()));
    String result=tools.callback("resumeSubAgent").call("{\"childId\":\"child-id\",\"revision\":7}",context);
    assertThat(result).contains("child-run");verify(runner).resumeDurable(eq(owner),eq("child-id"),eq(7L),eq(run.userRequest()),any());
    when(runner.durableChildren(owner,0,10)).thenReturn(java.util.List.of());
    assertThat(tools.callback("listDurableSubAgents").call("{\"offset\":0,\"limit\":10}",context)).isEqualTo("[]");
    verify(runner).durableChildren(owner,0,10);
  }
  @TempDir Path directory;
  @Test void parallelCallbackMapsRequestsAndSerializesOrderedIndividualResults() {
    var registry=new SubAgentRegistry(directory,new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()),m->true));
    var parallel=mock(ParallelSubAgentDelegator.class);
    var tools=new SubAgentTools(mock(SubAgentRunner.class),registry);tools.parallelDelegator(parallel);
    var request=new ParallelSubAgentDelegator.Request("first","reviewer","review",null);
    var child=new SubAgentResult("reviewer","run",SubAgentResult.Status.COMPLETED,"done",Instant.now(),Instant.now());
    when(parallel.delegate(java.util.List.of(request))).thenReturn(new ParallelSubAgentDelegator.Batch(ParallelSubAgentDelegator.Status.COMPLETED,
        java.util.List.of(new ParallelSubAgentDelegator.Item("first","reviewer",ParallelSubAgentDelegator.Status.COMPLETED,child))));
    var callback=tools.callback("delegateTasks");
    assertThat(callback.getToolDefinition().inputSchema()).contains("requests","task","agent");
    var json=new SubAgentResultParser().parse(callback.call("{\"requests\":[{\"id\":\"first\",\"agent\":\"reviewer\",\"task\":\"review\"}]}"));
    assertThat(json.at("/items/0/id").asString()).isEqualTo("first");
    assertThat(json.at("/items/0/result/subAgentRunId").asString()).isEqualTo("run");
    verify(parallel).delegate(java.util.List.of(request));
  }
  @Test void delegateReturnsTypedStatusesAndDynamicCatalogWithoutPrompts() throws Exception {
    var loader = new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of("readMultiFile")), m -> true);
    var registry = new SubAgentRegistry(directory, loader);
    var runner = mock(SubAgentRunner.class);
    var tools = new SubAgentTools(runner, registry);
    var callback = tools.callback();
    Files.writeString(directory.resolve("reviewer.yaml"), SubAgentConfigurationTest.yaml("reviewer"));
    registry.reload();
    assertThat(callback.getToolDefinition().description()).contains("reviewer", "Independent review").doesNotContain("Review independently.");
    for (var status : SubAgentResult.Status.values()) {
      when(runner.run("reviewer", "task", null)).thenReturn(new SubAgentResult("reviewer", "run", status, "output", Instant.now(), Instant.now()));
      assertThat(callback.call("{\"agent\":\"reviewer\",\"task\":\"task\"}")).contains(status.name());
    }
    verify(runner, times(SubAgentResult.Status.values().length)).run("reviewer", "task", null);
  }
  @Test void callbackSerializesValidatedEnvelopeAndStructuredErrors() {
    var registry = new SubAgentRegistry(directory,
        new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()), m -> true));
    var runner = mock(SubAgentRunner.class);
    var callback = new SubAgentTools(runner, registry).callback();
    var parser = new SubAgentResultParser();
    var envelope = SubAgentOutput.fromValidated(parser.parse(SubAgentResultParserTest.VALID));
    when(runner.run("reviewer", "task", null)).thenReturn(new SubAgentResult("reviewer", "run",
        SubAgentResult.Status.COMPLETED, SubAgentResultParserTest.VALID, Instant.now(), Instant.now(), envelope, java.util.List.of()));
    var json = parser.parse(callback.call("{\"agent\":\"reviewer\",\"task\":\"task\"}"));
    assertThat(json.at("/structuredOutput/status").asString()).isEqualTo("SUCCESS");
    assertThat(json.at("/structuredOutput/result").isObject()).isTrue();
    when(runner.run("reviewer", "task", null)).thenReturn(new SubAgentResult("reviewer", "run",
        SubAgentResult.Status.FAILED, "SubAgent structural validation failed", Instant.now(), Instant.now(), null,
        java.util.List.of(new ValidationError("/result/findings", "Schema constraint violated: required"))));
    json = parser.parse(callback.call("{\"agent\":\"reviewer\",\"task\":\"task\"}"));
    assertThat(json.at("/status").asString()).isEqualTo("FAILED");
    assertThat(json.at("/validationErrors/0/path").asString()).isEqualTo("/result/findings");
  }
}
