package dev.mikoto2000.rei.vectordocument;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@org.junit.jupiter.api.Tag("integration")
class RerankServiceTest {
  @Test void evaluationRejectsDisabledOrFailedProviderWhileRuntimeRetainsFallback(){
    var absent=new RerankService(new RerankProperties(false,null,null,null,null),RestClient.builder());assertEquals(List.of("a"),absent.rerank("q",List.of("a"),s->s));assertThrows(IllegalStateException.class,()->absent.rerankForEvaluation("q",List.of("a"),s->s));
    var configured=new RerankService(new RerankProperties(true,baseUrl,"fixture-key","model","/custom/rerank"),RestClient.builder());status=500;assertEquals(List.of("a","b"),configured.rerank("q",List.of("a","b"),s->s));assertThrows(RuntimeException.class,()->configured.rerankForEvaluation("q",List.of("a","b"),s->s));
    status=200;response="{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]}";assertEquals(List.of("b","a"),configured.rerankForEvaluation("q",List.of("a","b"),s->s));
  }
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
  private RerankService budgetedService() {
    return new RerankService(new RerankProperties(true,baseUrl,"rerank-key","reranker","/custom/rerank",true),RestClient.builder());
  }
  @Test void unknownMalformedOversizedAndAmbiguousUsageCannotBecomeFreeFallback() {
    var variants=new java.util.ArrayList<String>();
    for(String usage:List.of("{}","{\"total_tokens\":0}","{\"total_tokens\":-1}","{\"total_tokens\":1.5}",
        "{\"total_tokens\":2147483648}","{\"total_tokens\":1,\"total_tokens\":2}"))variants.add("{\"usage\":"+usage+",\"results\":[]}");
    variants.add("{}");variants.add("{\"usage\":{\"total_tokens\":2}} {}");
    variants.add("{\"usage\":{\"total_tokens\":2},\"extra\":"+"[".repeat(33)+"0"+"]".repeat(33)+"}");
    variants.add("{\"usage\":{\"total_tokens\":2},\"extra\":\""+"x".repeat(65536)+"\"}");
    for(String variant:variants) {
      response=variant;var budget=new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0,10,null,100);
      var run=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("run",budget,null,null,null);
      try(var scope=dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(run.modelCallBudget())) {
        assertTrue(assertThrows(RuntimeException.class,()->budgetedService().rerank("q",List.of("a","b"),s->s))
            .getMessage().contains("TOKEN_USAGE_UNKNOWN"));assertTrue(budget.usageUnknown());
      }
    }
  }
  @Test void unavailableEndpointUsageStopsButKnownInvalidResultsStillChargeBeforeFallback() {
    status=500;var budget=new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0,10,null,100);
    try(var scope=dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("run",budget,null,null,null).modelCallBudget())) {
      assertTrue(assertThrows(RuntimeException.class,()->budgetedService().rerank("q",List.of("a","b"),s->s))
          .getMessage().contains("TOKEN_USAGE_UNKNOWN"));
    }
    status=200;response="{\"usage\":{\"total_tokens\":2},\"results\":[]}";
    var valid=new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0,10,null,100);
    try(var scope=dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("run",valid,null,null,null).modelCallBudget())) {
      assertEquals(List.of("a","b"),budgetedService().rerank("q",List.of("a","b"),s->s));
      assertEquals(2,valid.totalTokens());assertEquals(9,valid.remainingLlmCalls());
    }
  }
  @Test void exhaustedAndCanceledParentsDoNotSendRerankRequests() {
    var empty=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("run",new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0,0),null,null,null);
    try(var scope=dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(empty.modelCallBudget())) {
      assertTrue(assertThrows(RuntimeException.class,()->budgetedService().rerank("q",List.of("a","b"),s->s))
          .getMessage().contains("LLM_CALL_BUDGET_EXCEEDED"));
    }
    var cancelled=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("run",new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0,10),null,null,null);cancelled.cancel();
    try(var scope=dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(cancelled.modelCallBudget())) {
      assertThrows(java.util.concurrent.CancellationException.class,()->budgetedService().rerank("q",List.of("a","b"),s->s));
    }
    assertNull(body.get());
  }
  @Test void goalRerankUsageSurvivesRepositoryRestartAfterOvershoot() {
    response="{\"usage\":{\"total_tokens\":6},\"results\":[{\"index\":0,\"relevance_score\":1}]}";
    var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db"));
    var props=new dev.mikoto2000.rei.llm.LlmProperties();props.getOutputLimit().setMaxTotalTokensPerGoal(5);
    var goals=new dev.mikoto2000.rei.goal.GoalRepository(source,java.time.Clock.systemUTC(),props);
    var goal=goals.create(new dev.mikoto2000.rei.core.chat.AgentRunContext("source","chat",root,"p"),"Artifact","out.txt","a".repeat(64),3,10);
    var claim=goals.claim("p",goal.id());var id=goals.beginAttempt(claim);
    var budget=new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0,10,goals.modelBudget(claim,id));
    try(var scope=dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(id,budget,null,null,null).modelCallBudget())) {
      assertTrue(assertThrows(RuntimeException.class,()->budgetedService().rerank("q",List.of("a"),s->s))
          .getMessage().contains("TOKEN_BUDGET_EXCEEDED"));
    }
    var restarted=new dev.mikoto2000.rei.goal.GoalRepository(source,java.time.Clock.systemUTC());
    assertEquals(6,restarted.get("p",goal.id()).totalTokens());assertEquals(1,restarted.get("p",goal.id()).llmCallsUsed());
  }
  @Test void defaultDisabledAndEmptyRequestsPreserveLegacyAccounting() {
    var budget=new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0,10,null,100);
    try(var scope=dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("run",budget,null,null,null).modelCallBudget())) {
      assertEquals(List.of("b","a"),service().rerank("q",List.of("a","b"),s->s));
      assertTrue(budgetedService().rerank("q",List.<String>of(),s->s).isEmpty());
    }
    assertEquals(0,budget.totalTokens());assertEquals(10,budget.remainingLlmCalls());assertFalse(budget.usageUnknown());
  }
  @Test void parentBudgetSettingBindsWithoutLosingEnabledDefault() {
    var properties=bind(Map.of("rei.rerank.inherit-run-model-budget","true"));
    assertTrue(properties.inheritRunModelBudget());assertTrue(properties.enabled());
    assertFalse(new RerankProperties(true,baseUrl,"","model",null).inheritRunModelBudget());
  }
  @Test void semanticSkillRerankCannotAbsorbParentBudgetStopIntoFusionOrder() {
    response="{\"usage\":{\"total_tokens\":6},\"results\":[{\"index\":0,\"relevance_score\":1}]}";
    var ranker=new RerankService(new RerankProperties(true,baseUrl,"rerank-key","reranker","/custom/rerank",true),RestClient.builder());
    var skill=new dev.mikoto2000.rei.skills.AgentSkill("writer","compose",true,java.nio.file.Path.of("writer"),java.nio.file.Path.of("writer/SKILL.md"),"private instructions");
    var semantic=new dev.mikoto2000.rei.skills.SemanticSkillSearch(new dev.mikoto2000.rei.skills.SemanticSkillProperties(true,64,.55,30),
        ()->texts->texts.stream().map(text->new float[]{1,0}).toList(),()->ranker,System::nanoTime);
    var budget=new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0,10,null,5);
    var run=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("run",budget,null,null,null);
    try(var scope=dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(run.modelCallBudget())) {
      assertTrue(assertThrows(RuntimeException.class,()->semantic.select("writer",List.of(skill),
          List.of(new dev.mikoto2000.rei.skills.SkillCandidate(skill,10,List.of("name"),List.of())),1))
          .getMessage().contains("TOKEN_BUDGET_EXCEEDED"));
    }
    assertEquals(6,budget.totalTokens());
  }
  @Test void ownedRerankChargesUsageAndExactLimitPreventsNextRequest() {
    response="{\"usage\":{\"total_tokens\":5},\"results\":[{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":0.9}]}";
    var service=new RerankService(new RerankProperties(true,baseUrl,"rerank-key","reranker","/custom/rerank",true),RestClient.builder());
    var budget=new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0,10,null,5);
    var run=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("run",budget,null,null,null);
    try(var scope=dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(run.modelCallBudget())) {
      assertEquals(List.of("b","a"),service.rerank("q",List.of("a","b"),s->s));assertEquals(5,budget.totalTokens());
      body.set(null);
      assertTrue(assertThrows(RuntimeException.class,()->service.rerank("q",List.of("a","b"),s->s))
          .getMessage().contains("TOKEN_BUDGET_EXCEEDED"));assertNull(body.get());
    }
  }
  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<String> body = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private String response = """
      {"results":[{"index":0,"relevance_score":0.1},{"index":1,"relevance_score":0.9}]}
      """;
  private int status = 200;

  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/custom/rerank", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach void stop() { server.stop(0); }

  private RerankService service() {
    return new RerankService(new RerankProperties(true, baseUrl, "rerank-key", "reranker", "/custom/rerank"),
        RestClient.builder());
  }

  @Test void sendsIndependentCredentialsModelAndFullTextAndReranksBeforeLimit() {
    VectorStore store = mock(VectorStore.class);
    when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
        Document.builder().id("a#0").text("First full document")
            .metadata(Map.of("docId", "a", "source", "a.txt", "chunkIndex", 0)).score(0.9).build(),
        Document.builder().id("b#0").text("Second full document")
            .metadata(Map.of("docId", "b", "source", "b.txt", "chunkIndex", 0)).score(0.2).build()));
    var documents = new VectorDocumentService(store, mock(VectorDocumentRepository.class),
        new VectorDocumentProperties(512, 0));
    documents.setRerankService(service());
    var results = documents.search("question", 1, null, null);
    assertEquals(1, results.size());
    assertEquals("b", results.getFirst().docId());
    var request = JsonMapper.builder().build().readTree(body.get());
    assertEquals("question", request.get("query").asString());
    assertEquals("reranker", request.get("model").asString());
    assertEquals("First full document", request.get("documents").get(0).asString());
    assertEquals("Second full document", request.get("documents").get(1).asString());
    assertEquals("Bearer rerank-key", authorization.get());
  }

  @Test void disabledAndEmptySearchDoNotSendRequests() {
    for (String url : new String[] {null, "", "  "}) {
      var disabled = new RerankService(new RerankProperties(true, url, "", "", null), RestClient.builder());
      assertEquals(List.of("a"), disabled.rerank("q", List.of("a"), s -> s));
    }
    assertTrue(service().rerank("q", List.<String>of(), s -> s).isEmpty());
    assertNull(body.get());
  }

  @Test void failureKeepsOriginalOrder() {
    status = 500;
    assertEquals(List.of("a", "b"), service().rerank("q", List.of("a", "b"), s -> s));
  }

  @Test void malformedIncompleteAndDuplicateResultsKeepOriginalOrder() {
    for (String invalid : List.of("{}", "{\"results\":[]}",
        "{\"results\":[{\"index\":0,\"relevance_score\":1},{\"index\":0,\"relevance_score\":2}]}",
        "{\"results\":[{\"index\":2,\"relevance_score\":1},{\"index\":0,\"relevance_score\":2}]}")) {
      response = invalid;
      assertEquals(List.of("a", "b"), service().rerank("q", List.of("a", "b"), s -> s));
    }
  }

  @Test void configuredEndpointRequiresModel() {
    assertThrows(IllegalArgumentException.class, () -> new RerankService(
        new RerankProperties(true, baseUrl, "", "", null), RestClient.builder()));
  }

  @Test void explicitlyDisabledSkipsRequestsAndModelValidation() {
    for (String model : new String[] {null, "", "reranker"}) {
      var properties = bind(Map.of("rei.rerank.enabled", "false", "rei.rerank.base-url", baseUrl));
      assertFalse(properties.enabled());
      var disabled = new RerankService(new RerankProperties(properties.enabled(), properties.baseUrl(),
          "rerank-key", model, "/custom/rerank"), RestClient.builder());
      var candidates = List.of("a", "b");
      assertSame(candidates, disabled.rerank("q", candidates, s -> s));
    }
    assertNull(body.get());
  }

  @Test void omittedEnabledPreservesExistingConfigurationAndExplicitTrueEnablesReranking() {
    for (String enabled : new String[] {null, "true"}) {
      var values = new java.util.HashMap<String, Object>();
      values.put("rei.rerank.base-url", baseUrl);
      values.put("rei.rerank.model", "reranker");
      values.put("rei.rerank.path", "/custom/rerank");
      if (enabled != null) values.put("rei.rerank.enabled", enabled);
      var properties = bind(values);
      assertTrue(properties.enabled());
      var configured = new RerankService(properties, RestClient.builder());
      assertEquals(List.of("b", "a"), configured.rerank("q", List.of("a", "b"), s -> s));
    }
  }

  private RerankProperties bind(Map<String, ?> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bind("rei.rerank", Bindable.of(RerankProperties.class)).get();
  }
}
