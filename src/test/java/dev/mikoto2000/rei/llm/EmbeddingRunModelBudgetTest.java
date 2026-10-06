package dev.mikoto2000.rei.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.*;
import org.springframework.ai.chat.metadata.DefaultUsage;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;

class EmbeddingRunModelBudgetTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
  EmbeddingResponse response(Integer tokens) {
    var metadata=new EmbeddingResponseMetadata();if(tokens!=null)metadata.setUsage(new DefaultUsage(tokens,0));
    return new EmbeddingResponse(List.of(new Embedding(new float[]{1,0},0)),metadata);
  }
  @Test void embeddingUsageSharesParentBudgetAndStopsNextProviderCallAtExactLimit() {
    var provider=mock(EmbeddingModel.class);
    when(provider.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[]{1,0},0)),
        new EmbeddingResponseMetadata("test",new DefaultUsage(5,0))));
    var model=new BudgetedEmbeddingModel(provider);var budget=new OutputLimitRunBudget(0,10,null,5);
    var run=new RunExecutionContext("run",budget,null,null,null);
    try(var scope=ModelCallBudgetScope.open(run.modelCallBudget())) {
      assertArrayEquals(new float[]{1,0},model.embed("query"));assertEquals(5,budget.totalTokens());
      assertTrue(assertThrows(RuntimeException.class,()->model.embed("next")).getMessage().contains("TOKEN_BUDGET_EXCEEDED"));
    }
    verify(provider,times(1)).call(any(EmbeddingRequest.class));assertNull(ModelCallBudgetScope.current());
  }
  @Test void overshootUnknownAndProviderFailureCannotReturnUsableVectors() {
    for(Integer tokens:Arrays.asList(6,null,0)) {
      var provider=mock(EmbeddingModel.class);when(provider.call(any(EmbeddingRequest.class))).thenReturn(response(tokens));
      var budget=new OutputLimitRunBudget(0,10,null,5);var run=new RunExecutionContext("run",budget,null,null,null);
      try(var scope=ModelCallBudgetScope.open(run.modelCallBudget())) {
        assertTrue(assertThrows(RuntimeException.class,()->new BudgetedEmbeddingModel(provider).embed("query"))
            .getMessage().contains(tokens!=null&&tokens>0?"TOKEN_BUDGET_EXCEEDED":"TOKEN_USAGE_UNKNOWN"));
      }
    }
    var provider=mock(EmbeddingModel.class);when(provider.call(any(EmbeddingRequest.class))).thenThrow(new IllegalStateException("offline"));
    var budget=new OutputLimitRunBudget(0,10,null,5);
    try(var scope=ModelCallBudgetScope.open(new RunExecutionContext("run",budget,null,null,null).modelCallBudget())) {
      assertTrue(assertThrows(RuntimeException.class,()->new BudgetedEmbeddingModel(provider).embed("query"))
          .getMessage().contains("TOKEN_USAGE_UNKNOWN"));assertTrue(budget.usageUnknown());
    }
  }
  @Test void documentFormattingAndDimensionProbeAreAccountedWithoutDoubleCharge() {
    var provider=mock(EmbeddingModel.class);when(provider.call(any(EmbeddingRequest.class))).thenReturn(response(2));
    var document=new org.springframework.ai.document.Document("text");when(provider.getEmbeddingContent(document)).thenReturn("formatted text");
    var model=new BudgetedEmbeddingModel(provider);var budget=new OutputLimitRunBudget(0,10,null,10);
    try(var scope=ModelCallBudgetScope.open(new RunExecutionContext("run",budget,null,null,null).modelCallBudget())) {
      assertEquals(2,model.dimensions());assertEquals(2,model.dimensions());model.embed(document);
    }
    var requests=org.mockito.ArgumentCaptor.forClass(EmbeddingRequest.class);verify(provider,times(2)).call(requests.capture());
    assertEquals(List.of("formatted text"),requests.getAllValues().getLast().getInstructions());assertEquals(4,budget.totalTokens());
  }
  @Test void workerCapturesBudgetAndRestoresItBeforeUnownedTask() {
    var provider=mock(EmbeddingModel.class);when(provider.call(any(EmbeddingRequest.class))).thenReturn(response(3));
    when(provider.embed(anyList())).thenReturn(List.of(new float[]{1,0}));
    var model=new BudgetedEmbeddingModel(provider);var budget=new OutputLimitRunBudget(0,10,null,3);
    try(var client=new dev.mikoto2000.rei.skills.SkillEmbeddingClient(()->model::embed,java.time.Duration.ofSeconds(2))) {
      try(var scope=ModelCallBudgetScope.open(new RunExecutionContext("run",budget,null,null,null).modelCallBudget())) {
        assertEquals(1,client.embed(List.of("first")).size());
        assertTrue(assertThrows(RuntimeException.class,()->client.embed(List.of("second"))).getMessage().contains("TOKEN_BUDGET_EXCEEDED"));
      }
      assertEquals(1,client.embed(List.of("unowned")).size());
    }
    assertNull(ModelCallBudgetScope.current());verify(provider,times(1)).call(any(EmbeddingRequest.class));verify(provider,times(1)).embed(anyList());
  }
  @Test void toolContextProvidesParentBudgetAndRestoresOuterScope() {
    var provider=mock(EmbeddingModel.class);when(provider.call(any(EmbeddingRequest.class))).thenReturn(response(3));
    var model=new BudgetedEmbeddingModel(provider);var budget=new OutputLimitRunBudget(0,10,null,3);
    var run=new RunExecutionContext("run",budget,null,null,null);
    var callback=mock(org.springframework.ai.tool.ToolCallback.class);
    when(callback.getToolDefinition()).thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder().name("vectorDocumentSearch")
        .description("search").inputSchema("{}").build());
    when(callback.call(anyString(),any(org.springframework.ai.chat.model.ToolContext.class))).thenAnswer(invocation->{model.embed("query");return "found";});
    var decorated=new dev.mikoto2000.rei.event.ToolEventCallbackDecorator(callback,
        new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),event->{});
    var context=new org.springframework.ai.chat.model.ToolContext(Map.of(RunExecutionContext.KEY,run));
    assertEquals("found",decorated.call("{}",context));assertEquals(3,budget.totalTokens());assertNull(ModelCallBudgetScope.current());
    assertTrue(assertThrows(RuntimeException.class,()->decorated.call("{}",context)).getMessage().contains("TOKEN_BUDGET_EXCEEDED"));
    assertNull(ModelCallBudgetScope.current());verify(provider,times(1)).call(any(EmbeddingRequest.class));
  }
  @Test @org.junit.jupiter.api.Tag("integration")
  void embeddingUsageSurvivesGoalRepositoryRestart() {
    var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db"));
    var props=new LlmProperties();props.getOutputLimit().setMaxTotalTokensPerGoal(3);
    var goals=new dev.mikoto2000.rei.goal.GoalRepository(source,java.time.Clock.systemUTC(),props);
    var goal=goals.create(new dev.mikoto2000.rei.core.chat.AgentRunContext("source","chat",root,"p"),"Artifact","out.txt","a".repeat(64),3,10);
    var claim=goals.claim("p",goal.id());var id=goals.beginAttempt(claim);
    var budget=new OutputLimitRunBudget(0,10,goals.modelBudget(claim,id));
    var provider=mock(EmbeddingModel.class);when(provider.call(any(EmbeddingRequest.class))).thenReturn(response(4));
    try(var scope=ModelCallBudgetScope.open(new RunExecutionContext(id,budget,null,null,null).modelCallBudget())) {
      assertTrue(assertThrows(RuntimeException.class,()->new BudgetedEmbeddingModel(provider).embed("query"))
          .getMessage().contains("TOKEN_BUDGET_EXCEEDED"));
    }
    var reopened=new dev.mikoto2000.rei.goal.GoalRepository(source,java.time.Clock.systemUTC());
    assertEquals(4,reopened.get("p",goal.id()).totalTokens());assertEquals(1,reopened.get("p",goal.id()).llmCallsUsed());
  }
  @Test void nestedBudgetScopesAndDefaultBeanConfigurationPreserveLegacyModel() {
    var outer=mock(ModelCallBudget.class);var inner=mock(ModelCallBudget.class);
    try(var first=ModelCallBudgetScope.open(outer)) {
      try(var second=ModelCallBudgetScope.open(inner)){assertSame(inner,ModelCallBudgetScope.current());}
      assertSame(outer,ModelCallBudgetScope.current());
    }
    assertNull(ModelCallBudgetScope.current());
    var context=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(EmbeddingBudgetConfiguration.class).withBean(EmbeddingModel.class,()->mock(EmbeddingModel.class));
    context.run(app->assertFalse(app.getBean(EmbeddingModel.class) instanceof BudgetedEmbeddingModel));
    context.withPropertyValues("rei.embedding.inherit-run-model-budget=true").run(app->assertTrue(app.getBean(EmbeddingModel.class) instanceof BudgetedEmbeddingModel));
  }
}
