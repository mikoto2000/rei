package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import okhttp3.*;
import okio.Buffer;

/** Phase 0 probes: real SDK and local HTTP, with no production capture feature enabled. */
@Tag("integration")
class RequestCapturePhaseZeroTest {
  static final String RESPONSE = "{\"id\":\"chat\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"probe\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
  static final String CHUNK = "data: {\"id\":\"chat\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"probe\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":null}]}\n\n";

  @Test void sdkRetryIsObservedAsTwoTransportAttemptsWithIdenticalBytes() throws Exception {
    try (var probe = new Probe(false, true)) {
      var model = probe.model();
      assertThat(model.call(new Prompt("hello", model.getOptions())).getResult().getOutput().getText()).isEqualTo("ok");
      assertThat(probe.received).hasSize(2);
      assertThat(probe.captured).hasSize(2);
      for (int i=0; i<2; i++) {
        assertThat(probe.captured.get(i)).isEqualTo(probe.received.get(i));
        assertThat(MessageDigest.getInstance("SHA-256").digest(probe.captured.get(i)))
            .isEqualTo(MessageDigest.getInstance("SHA-256").digest(probe.received.get(i)));
      }
      assertThat(probe.captured.get(0)).isEqualTo(probe.captured.get(1));
    }
  }

  @Test void asynchronousStreamingTransportDoesNotInheritLexicalRunScope() throws Exception {
    try (var probe = new Probe(true, false)) {
      var model=probe.model();
      var run=new AgentRunContext("run-a", "conversation-a", Path.of("."));
      try (var ignored=AgentRunScope.open(run)) {
        var results=model.stream(new Prompt("hello", model.getOptions())).collectList().block(Duration.ofSeconds(10));
        assertThat(results).isNotEmpty();
      }
      assertThat(probe.received).hasSize(1);
      assertThat(probe.captured).hasSize(1);
      assertThat(probe.captured.getFirst()).isEqualTo(probe.received.getFirst());
      assertThat(probe.scopePresent.get()).isZero();
    }
  }

  @Test void capturesShowUiRewrittenWireBytesWithoutExtraRequests() throws Exception {
    try (var probe=new Probe(false,false)) {
      var json=new com.fasterxml.jackson.databind.ObjectMapper();
      byte[] original=json.writeValueAsBytes(Map.of("model","showui","messages",List.of(Map.of("role","user","content",List.of(
          Map.of("type","text","text",dev.mikoto2000.rei.computeruse.ShowUiRequestInterceptor.INSTRUCTION+"\nSave"),
          Map.of("type","image_url","image_url",Map.of("url","data:image/png;base64,aA==")))))));
      var client=new OkHttpClient.Builder().addInterceptor(new ShowUiSdkRequestInterceptor()).addInterceptor(probe::capture).build();
      try {
        try(var response=client.newCall(new Request.Builder().url(probe.url()+"/chat/completions")
            .post(RequestBody.create(original,MediaType.get("application/json"))).build()).execute()) {
          assertThat(response.code()).isEqualTo(200);
        }
        assertThat(probe.received).hasSize(1);
        assertThat(probe.captured.getFirst()).isEqualTo(probe.received.getFirst()).isNotEqualTo(original);
        assertThat(json.readTree(probe.captured.getFirst()).path("messages").get(0).path("content")).hasSize(3);
      } finally { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown(); }
    }
  }

  @Test void showUiSkipsOneShotTransformationAndTransportConsumesItOnce() throws Exception {
    try(var probe=new Probe(false,false)) {
      var writes=new AtomicInteger();
      var body=new RequestBody() {
        public MediaType contentType(){return MediaType.get("application/json");}
        public boolean isOneShot(){return true;}
        public void writeTo(okio.BufferedSink sink) throws java.io.IOException {
          if(writes.incrementAndGet()>1)throw new java.io.IOException("One-shot body already consumed");
          sink.writeUtf8("{\"messages\":[]}");
        }
      };
      var client=new OkHttpClient.Builder().addInterceptor(new ShowUiSdkRequestInterceptor()).build();
      try {
        {
          try(var response=client.newCall(new Request.Builder().url(probe.url()+"/chat/completions").post(body).build()).execute()) {assertThat(response.code()).isEqualTo(200);}
        }
        assertThat(writes.get()).isEqualTo(1);
        assertThat(probe.received).hasSize(1);
      } finally {client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
    }
  }

  @Test void reactorContextIsNotAutomaticallyAvailableInsideSdkInterceptor() throws Exception {
    try(var probe=new Probe(true,false)) {
      var model=probe.model();
      var results=reactor.core.publisher.Flux.deferContextual(context -> {
        assertThat(context.get("run-id").toString()).isEqualTo("run-a");
        return model.stream(new Prompt("hello",model.getOptions()));
      }).contextWrite(context -> context.put("run-id","run-a")).collectList().block(Duration.ofSeconds(10));
      assertThat(results).isNotEmpty();
      assertThat(probe.scopePresent.get()).isZero();
      assertThat(probe.received).hasSize(1);
    }
  }

  @Test void publicSdkRequestOptionsAndTransportRequestExposeNoLocalIdentitySlot() {
    assertThat(Arrays.stream(com.openai.core.RequestOptions.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
        .doesNotContain("getAttributes","getTags","getContext");
    assertThat(Arrays.stream(com.openai.core.http.HttpRequest.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
        .doesNotContain("attributes","tags","context");
  }

  @Test void immutableClientBoundIdentitySurvivesStreamingAndSdkRetriesWithoutWireIds() throws Exception {
    record Identity(String run, String logicalCall) {}
    var identity=new Identity("run-a","logical-a");
    var observed=new CopyOnWriteArrayList<Identity>();
    try(var probe=new Probe(true,true)) {
      var transport=org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient.builder()
          .interceptor(new ShowUiSdkRequestInterceptor()).interceptor(chain -> {
            observed.add(identity);
            assertThat(chain.request().header("run-id")).isNull();
            return probe.capture(chain);
          }).build();
      var options=OpenAiChatOptions.builder().baseUrl(probe.url()).apiKey("test-key").model("probe").maxRetries(1).build();
      var client=new com.openai.client.OpenAIClientImpl(com.openai.core.ClientOptions.builder().baseUrl(probe.url()).apiKey("test-key").maxRetries(1).httpClient(transport).build());
      try {
        var model=OpenAiChatModel.builder().options(options).openAiClient(client).openAiClientAsync(client.async()).build();
        assertThat(model.stream(new Prompt("hello",options)).collectList().block(Duration.ofSeconds(10))).isNotEmpty();
        assertThat(observed).containsExactly(identity,identity);
        assertThat(probe.received).hasSize(2);
        for(int i=0;i<2;i++)assertThat(probe.captured.get(i)).isEqualTo(probe.received.get(i));
      } finally { client.close(); }
    }
  }

  static final class Probe implements AutoCloseable {
    final HttpServer server;
    final List<byte[]> captured=new CopyOnWriteArrayList<>(), received=new CopyOnWriteArrayList<>();
    final AtomicInteger scopePresent=new AtomicInteger();
    final boolean streaming;
    Probe(boolean streaming,boolean retry) throws Exception {
      this.streaming=streaming;
      server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      server.createContext("/v1/chat/completions", exchange -> {
        received.add(exchange.getRequestBody().readAllBytes());
        boolean fail=retry && received.size()==1;
        exchange.getResponseHeaders().set("Content-Type", streaming?"text/event-stream":"application/json");
        byte[] bytes=(fail?"{\"error\":{\"message\":\"retry\"}}":streaming?CHUNK+"data: [DONE]\n\n":RESPONSE).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(fail?503:200,streaming?0:bytes.length);
        try(var out=exchange.getResponseBody()) {
          if(streaming) { out.write(bytes,0,bytes.length/2); out.flush(); out.write(bytes,bytes.length/2,bytes.length-bytes.length/2); }
          else out.write(bytes);
        }
      });
      server.start();
    }
    String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";}
    OpenAiChatModel model(){return OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl(url()).apiKey("test-key").model("probe").maxRetries(1).build())
        .httpClientBuilderCustomizer(builder -> builder.interceptor(new ShowUiSdkRequestInterceptor()).interceptor(this::capture)).build();}
    Response capture(Interceptor.Chain chain) throws java.io.IOException {
      if(AgentRunScope.current()!=null)scopePresent.incrementAndGet();
      var buffer=new Buffer(); chain.request().body().writeTo(buffer); captured.add(buffer.readByteArray());
      return chain.proceed(chain.request());
    }
    public void close(){server.stop(0);}
  }
}
