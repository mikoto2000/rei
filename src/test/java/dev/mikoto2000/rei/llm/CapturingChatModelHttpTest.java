package dev.mikoto2000.rei.llm;
import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import dev.mikoto2000.rei.llm.capture.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.contextbudget.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.*;
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@Tag("integration")
class CapturingChatModelHttpTest {
  @TempDir Path dir;
  static final String RESPONSE=RequestCapturePhaseZeroTest.RESPONSE;
  static String event(String delta,String reason){return "data: {\"id\":\"chat\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"probe\",\"choices\":[{\"index\":0,\"delta\":"+delta+",\"finish_reason\":"+reason+"}]}\n\n";}
  static final String STREAM=event("{\"role\":\"assistant\",\"content\":\"ok\"}","null")+event("{}","\"stop\"")+"data: [DONE]\n\n";
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void legacyAdvisorExecutesToolsExactlyOnceWithCaptureOnOrOff(boolean capture, boolean streaming) throws Exception {
    String toolResponse="{\"id\":\"chat\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"probe\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"tool-id\",\"type\":\"function\",\"function\":{\"name\":\"readFile\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
    String toolStream=event("{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"tool-id\",\"type\":\"function\",\"function\":{\"name\":\"readFile\",\"arguments\":\"{}\"}}]}","\"tool_calls\"")+"data: [DONE]\n\n";
    try(var fixture=new Fixture((n,e)->n==1?(streaming?toolStream:toolResponse):(streaming?STREAM:RESPONSE))){
      if(capture)fixture.activate();
      var calls=new AtomicInteger();
      var callback=new org.springframework.ai.tool.ToolCallback(){
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition(){return org.springframework.ai.tool.definition.ToolDefinition.builder().name("readFile").description("read").inputSchema("{\"type\":\"object\",\"properties\":{}}").build();}
        public String call(String input){calls.incrementAndGet();return "legacy-tool-result";}
      };
      var client=org.springframework.ai.chat.client.ChatClient.builder(fixture.model())
          .defaultAdvisors(new RunAwareToolCallingAdvisor()).defaultOptions(fixture.options("run-a").mutate())
          .defaultTools(callback).build();
      if(streaming)assertThat(client.prompt("work").stream().content().collectList().block(Duration.ofSeconds(10))).isNotEmpty();
      else assertThat(client.prompt("work").call().content()).isNotBlank();
      assertThat(calls).hasValue(1);assertThat(fixture.received).hasSize(2);
      assertThat(new String(fixture.received.get(1),java.nio.charset.StandardCharsets.UTF_8)).contains("legacy-tool-result");
      if(capture)fixture.assertMatches(2);else assertThat(fixture.store.sessions()).isEmpty();
    }
  }
  @Test void captureTransportCreationFailureFallsBackToDelegate() throws Exception {
    try(var fixture=new Fixture((n,e)->RESPONSE)){
      fixture.activate();
      var delegate=OpenAiChatModel.builder().options(fixture.options("run-a")).build();
      var model=new CapturingChatModel(delegate,fixture.store,List.of(builder->{throw new IllegalStateException("fixture failure");}),
          io.micrometer.observation.ObservationRegistry.NOOP,null);
      assertThat(model.call(new Prompt("fallback",fixture.options("run-a"))).getResult().getOutput().getText()).isNotBlank();
      assertThat(fixture.received).hasSize(1);assertThat(fixture.store.attempts("run-a")).isEmpty();
    }
  }
  @Test void successfulCaptureDoesNotLogRawSecret(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    try(var fixture=new Fixture((n,e)->RESPONSE)){
      fixture.activate();String secret="capture-private-sentinel-95378";
      fixture.model().call(new Prompt("ordinary",fixture.options("run-a").mutate().extraBody(Map.of("api_key",secret)).build()));
      fixture.assertMatches(1);assertThat(output.getAll()).doesNotContain(secret);
    }
  }
  @Test void firstStreamingResponseDoesNotWaitForTheRemainingResponse() throws Exception {
    for(boolean enabled:List.of(false,true))try(var fixture=new Fixture((n,e)->STREAM)){
      fixture.finishGate=new CountDownLatch(1);if(enabled)fixture.activate();
      try{
        assertThat(fixture.model().stream(new Prompt("stream",fixture.options("run-a"))).take(1).blockLast(Duration.ofSeconds(2))).isNotNull();
        assertThat(fixture.finishGate.getCount()).isEqualTo(1);assertThat(fixture.received).hasSize(1);
        if(enabled)fixture.assertMatches(1);
      }finally{fixture.finishGate.countDown();}
    }
  }
  @Test void initialHistoryAndCompressionCaptureProjectedWireBytes() throws Exception {
    try(var fixture=new Fixture((n,e)->RESPONSE)){
      fixture.activate();var model=fixture.model();var options=fixture.options("run-a");
      model.call(new Prompt(List.of(new UserMessage("first")),options));
      model.call(new Prompt(List.of(new UserMessage("past"),new AssistantMessage("reply"),new UserMessage("current")),options));
      var properties=new ContextCompressionProperties();properties.setThreshold(300);properties.setHardLimit(600);properties.setRecentTokens(80);properties.setSummaryTokens(100);
      var estimate=TokenEstimator.conservative();var compressed=new AtomicInteger();
      var assembler=new ContextAssembler(properties,estimate,new ConversationSummaryRepository(dir),new ToolResultCompressor(new RawToolResultStore(dir),estimate,100,70),
        (previous,messages,budget,request)->{compressed.incrementAndGet();return "decided API X";},null,null);
      var raw=new Prompt(List.of(new SystemMessage("system"),ContextHistoryAdvisor.historical(new UserMessage("old ".repeat(500)),1),
          ContextHistoryAdvisor.historical(new AssistantMessage("recent"),2),new UserMessage("current request")),options);
      var projected=assembler.assemble(raw,"conversation-a","run-a",()->{});assertThat(compressed.get()).isEqualTo(1);
      model.call(projected);fixture.assertMatches(3);
      assertThat(new String(fixture.received.get(2),java.nio.charset.StandardCharsets.UTF_8)).contains("decided API X","recent","current request").doesNotContain("old old old");
      assertThat(fixture.store.attempts("run-a")).extracting(CaptureStore.AttemptInfo::logicalCallId).doesNotHaveDuplicates();
    }
  }
  @Test void realStagnationToolLoopRecordsTwoLogicalCalls() throws Exception {
    try(var fixture=new Fixture((n,e)->n==1?event("{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"tool-id\",\"type\":\"function\",\"function\":{\"name\":\"readFile\",\"arguments\":\"{}\"}}]}","\"tool_calls\"")+"data: [DONE]\n\n":STREAM)){
      fixture.activate();var events=new ArrayList<AgentEvent>();
      var execution=new RunExecutionContext("run-a",new OutputLimitRunBudget(1,3),new ProgressEvaluator(dir,null),new AgentEventFactory(Clock.systemUTC()),events::add);
      execution.setRunContext(new AgentRunContext("run-a","conversation-a",dir));
      var calls=new AtomicInteger();var callback=new org.springframework.ai.tool.ToolCallback(){
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition(){return org.springframework.ai.tool.definition.ToolDefinition.builder().name("readFile").description("read").inputSchema("{\"type\":\"object\",\"properties\":{}}").build();}
        public String call(String input){calls.incrementAndGet();return "tool-result-marker";}
      };
      var options=fixture.options("run-a").mutate().toolCallbacks(callback).toolContext(Map.of(RunExecutionContext.KEY,execution)).build();
      try{assertThat(new StagnationChatModel(fixture.model()).stream(new Prompt("work",options)).collectList().block(Duration.ofSeconds(10))).isNotEmpty();}
      finally{execution.close();}
      fixture.assertMatches(2);assertThat(calls.get()).isEqualTo(1);
      assertThat(new String(fixture.received.get(0),java.nio.charset.StandardCharsets.UTF_8)).contains("readFile");
      assertThat(new String(fixture.received.get(1),java.nio.charset.StandardCharsets.UTF_8)).contains("tool-result-marker");
      assertThat(fixture.store.attempts("run-a")).extracting(CaptureStore.AttemptInfo::logicalCallId).doesNotHaveDuplicates();
    }
  }
  @Test void sdkRetryRetainsLogicalIdAndIncrementsAttempts() throws Exception {
    try(var fixture=new Fixture((n,e)->{if(n==1)e.status=503;return RESPONSE;})){
      fixture.activate();fixture.model(1).call(new Prompt("retry",fixture.options("run-a")));fixture.assertMatches(2);
      var attempts=fixture.store.attempts("run-a");assertThat(attempts).extracting(CaptureStore.AttemptInfo::logicalCallId).containsOnly(attempts.getFirst().logicalCallId());
      assertThat(attempts).extracting(CaptureStore.AttemptInfo::attemptNumber).containsExactly(1,2);
      assertThat(attempts).extracting(CaptureStore.AttemptInfo::httpStatus).containsExactly(503,200);
    }
  }
  @Test void concurrentOtherRootAndBackgroundDoNotEnterTheCapture() throws Exception {
    try(var fixture=new Fixture((n,e)->RESPONSE);var executor=Executors.newVirtualThreadPerTaskExecutor()){
      fixture.activate();var model=fixture.model();var futures=new ArrayList<Future<?>>();
      for(String run:List.of("run-a","run-b","background"))futures.add(executor.submit(()->model.call(new Prompt(run,fixture.options(run)))));
      for(var future:futures)future.get(10,TimeUnit.SECONDS);
      assertThat(fixture.received).hasSize(3);assertThat(fixture.store.attempts("run-a")).hasSize(1);
      assertThat(new String(fixture.store.body(fixture.store.attempts("run-a").getFirst().attemptId()),java.nio.charset.StandardCharsets.UTF_8)).contains("run-a").doesNotContain("run-b","background");
    }
  }
  @Test void streamingCompletionAndCancellationHaveSameResponsesAndRequestCountOnAndOff() throws Exception {
    for(boolean enabled:List.of(false,true))try(var fixture=new Fixture((n,e)->STREAM)){
      if(enabled)fixture.activate();var model=fixture.model();
      var all=model.stream(new Prompt("stream",fixture.options("run-a"))).collectList().block(Duration.ofSeconds(10));assertThat(all).isNotEmpty();
      var first=model.stream(new Prompt("stream",fixture.options("run-a"))).take(1).blockLast(Duration.ofSeconds(10));assertThat(first).isNotNull();
      assertThat(fixture.received).hasSize(2);if(enabled)fixture.assertMatches(2);else assertThat(fixture.store.sessions()).isEmpty();
    }
  }
  @Test void httpErrorsPropagateOnAndOff() throws Exception {
    for(boolean enabled:List.of(false,true))try(var fixture=new Fixture((n,e)->{e.status=500;return "{\"error\":{\"message\":\"failed\"}}";})){
      if(enabled)fixture.activate();assertThatThrownBy(()->fixture.model().stream(new Prompt("error",fixture.options("run-a"))).collectList().block(Duration.ofSeconds(10))).isInstanceOf(java.util.concurrent.CompletionException.class).hasRootCauseInstanceOf(com.openai.errors.InternalServerException.class);
      assertThat(fixture.received).hasSize(1);if(enabled){fixture.assertMatches(1);assertThat(fixture.store.attempts("run-a").getFirst().httpStatus()).isEqualTo(500);}
    }
  }
  static class Reply {int status=200;}
  final class Fixture implements AutoCloseable {
    volatile CountDownLatch finishGate;
    final CaptureStore store=new CaptureStore();final List<byte[]> received=new CopyOnWriteArrayList<>();final HttpServer server;
    final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    Fixture(java.util.function.BiFunction<Integer,Reply,String> reply) throws Exception {
      server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);
      server.createContext("/v1/chat/completions",exchange->{received.add(exchange.getRequestBody().readAllBytes());var r=new Reply();String text=reply.apply(received.size(),r);
        exchange.getResponseHeaders().set("Content-Type",text.startsWith("data:")?"text/event-stream":"application/json");
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(r.status,0);
        try(var out=exchange.getResponseBody()){int cut=finishGate==null?bytes.length/2:text.indexOf("\n\n")+2;out.write(bytes,0,cut);out.flush();if(finishGate!=null)try{finishGate.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}out.write(bytes,cut,bytes.length-cut);}catch(java.io.IOException cancelled){}
      });server.start();
    }
    String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";}
    OpenAiChatOptions options(String run){return OpenAiChatOptions.builder().baseUrl(url()).apiKey("test-key").model("probe").maxRetries(0)
      .toolContext(Map.of(AgentRunContext.class.getName(),new AgentRunContext(run,"conversation-a",dir))).build();}
    CapturingChatModel model(){return model(0);}
    CapturingChatModel model(int retries){return new CapturingChatModel(OpenAiChatModel.builder().options(options("run-a").mutate().maxRetries(retries).build()).build(),store,
      List.of(builder->builder.interceptor(new ChatStreamTimeoutInterceptor()).interceptor(new ShowUiSdkRequestInterceptor())),io.micrometer.observation.ObservationRegistry.NOOP,null);}
    void activate(){store.reserve("client","conversation-a");store.accept("client","conversation-a","submission-a","run-a");}
    void assertMatches(int count) throws Exception {assertThat(received).hasSize(count);var attempts=store.attempts("run-a");assertThat(attempts).hasSize(count);
      for(int i=0;i<count;i++){var a=attempts.get(i);var bytes=store.body(a.attemptId());assertThat(bytes).isEqualTo(received.get(i));assertThat(a.sha256()).isEqualTo(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(received.get(i))));}}
    public void close(){server.stop(0);workers.close();}
  }
}
