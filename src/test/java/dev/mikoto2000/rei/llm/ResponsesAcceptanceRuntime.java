package dev.mikoto2000.rei.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;

/** Owned, explicitly authorized composition; never loads Rei's production configuration. */
final class ResponsesAcceptanceRuntime {
  enum Candidate {
    BASE,
    STRUCTURED,
    IMAGE,
    SUMMARY
  }

  static final ObjectMapper JSON =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  final Path root;
  final String target;
  final String model;
  final String base;
  final LlmProperties properties;
  final LlmModelProvider models;
  final ModelMessagePayloadStore payloads;
  final ResponsesAcceptanceBudget budget;
  final org.springframework.beans.factory.support.StaticListableBeanFactory beans;

  ResponsesAcceptanceRuntime(String target, Path root) throws Exception {
    this(target, root, null, Candidate.BASE);
  }

  ResponsesAcceptanceRuntime(String target, Path root, Candidate candidate) throws Exception {
    this(target, root, null, candidate);
  }

  ResponsesAcceptanceRuntime(
      Path root,
      List<org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer>
          fixtureTransport)
      throws Exception {
    this("fixture", root, fixtureTransport, Candidate.BASE);
  }

  private ResponsesAcceptanceRuntime(
      String target,
      Path root,
      List<org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer>
          fixtureTransport,
      Candidate candidate)
      throws Exception {
    boolean fixture =
        fixtureTransport != null && !fixtureTransport.isEmpty() && target.equals("fixture");
    if (candidate == null || candidate != Candidate.BASE && !target.equals("local-deepseek"))
      throw new IllegalArgumentException(
          "Candidate capability probes require the approved local target");
    if (!fixture
        && (!"true".equals(System.getenv("REI_LIVE_APPROVED_EXTENDED"))
            || !List.of("openai", "local-deepseek").contains(target)))
      throw new IllegalStateException("Explicit extended trial approval required");
    this.target = target;
    this.root = root.toRealPath();
    boolean openai = target.equals("openai") || fixture;
    base =
        fixture
            ? "https://acceptance-fixture.invalid/v1"
            : openai ? "https://api.openai.com/v1" : "http://gx10-707e.local:8888/v1";
    model =
        fixture ? "acceptance-fixture-model" : openai ? "gpt-5.6-sol" : "deepseek-v4.1-flash-1017";
    String key =
        fixture
            ? "owned-fixture-key"
            : System.getenv(openai ? "OPENAI_API_KEY" : "REI_OPENAI_API_KEY");
    if (!openai && (key == null || key.isBlank())) key = "dummy-key";
    if (key == null || key.isBlank() || openai && key.equals("dummy-key"))
      throw new IllegalStateException("Approved credential unavailable");
    budget =
        new ResponsesAcceptanceBudget(
            this.root.resolve("budget.jsonl"),
            "extended-" + target,
            new ResponsesAcceptanceBudget.Limits(
                200,
                1000000,
                300000,
                Duration.ofHours(3),
                openai ? 20000000 : 0,
                openai ? 10 : 0,
                openai ? 30 : 0),
            Clock.systemUTC());
    properties = new LlmProperties();
    properties.setReadTimeout(Duration.ofSeconds(120));
    properties.setMaxOutputTokens(8192);
    properties.setMaxStreamTimeoutRetries(0);
    properties.getOutputLimit().setMaxLlmCallsPerRun(80);
    properties.getOutputLimit().setMaxTotalTokensPerRun(1000000);
    var server = new LlmProperties.Server();
    server.setApi(LlmApiType.RESPONSES);
    server.setBaseUrl(base);
    server.setApiKey(key);
    server.setModel(model);
    server.setReasoningReplay(
        openai ? ReasoningReplayMode.ENCRYPTED : ReasoningReplayMode.PLAINTEXT);
    server.setMaxOutputTokens(8192);
    server.setMaxTools(128);
    var capabilities = java.util.EnumSet.of(LlmCapabilities.Setting.REASONING_EFFORT);
    if (openai)
      capabilities.addAll(
          java.util.Set.of(
              LlmCapabilities.Setting.STRUCTURED_OUTPUT,
              LlmCapabilities.Setting.IMAGE_INPUT,
              LlmCapabilities.Setting.REASONING_SUMMARY));
    if (candidate == Candidate.STRUCTURED)
      capabilities.add(LlmCapabilities.Setting.STRUCTURED_OUTPUT);
    if (candidate == Candidate.IMAGE) capabilities.add(LlmCapabilities.Setting.IMAGE_INPUT);
    if (candidate == Candidate.SUMMARY) capabilities.add(LlmCapabilities.Setting.REASONING_SUMMARY);
    server.setSupportedSettings(capabilities);
    properties.setFeature(LlmFeature.CHAT, server);
    var defaults =
        new ChatModel() {
          public org.springframework.ai.chat.model.ChatResponse call(
              org.springframework.ai.chat.prompt.Prompt prompt) {
            throw new IllegalStateException(
                "No default or fallback model in acceptance composition");
          }

          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.openai.OpenAiChatOptions.builder()
                .model("disabled")
                .build();
          }
        };
    List<org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer> policies =
        List.of(http ->
            http.interceptor(chain -> {
              var request = chain.request();
              if (!request.method().equals("POST")
                  || !request.url().toString().equals(base + "/responses")
                  || request.body() == null)
                throw new IllegalStateException("Unapproved acceptance request target");
              var buffer = new okio.Buffer();
              request.body().writeTo(buffer);
              byte[] bytes = buffer.readByteArray();
              var body = JSON.readTree(bytes);
              if (!model.equals(body.path("model").asText())
                  || body.path("store").asBoolean(true)
                  || body.hasNonNull("previous_response_id")
                  || body.hasNonNull("conversation")
                  || body.path("max_output_tokens").asInt() < 1
                  || body.path("max_output_tokens").asInt() > 8192
                  || openai && !"default".equals(body.path("service_tier").asText()))
                throw new IllegalStateException("Unapproved acceptance request fields");
              int wire = reserveWire();
              chain
                  .call()
                  .timeout()
                  .timeout(
                      Math.min(120000, budget.remainingMillis()),
                      java.util.concurrent.TimeUnit.MILLISECONDS);
              long start = System.nanoTime();
              var response = chain.proceed(request);
              if (response.priorResponse() != null
                  || !response.request().url().equals(request.url())) {
                response.close();
                throw new IllegalStateException("Acceptance redirects are not supported");
              }
              int reasoning = 0;
              for (var item : body.path("input"))
                if ("reasoning".equals(item.path("type").asText())) reasoning++;
              var audit = new java.util.LinkedHashMap<String, Object>();
              audit.put("wire", wire);
              audit.put("http_status", response.code());
              audit.put("bytes", bytes.length);
              audit.put("header_ms", (System.nanoTime() - start) / 1000000.0);
              audit.put("sha256", hash(bytes));
              audit.put("tools", body.path("tools").isArray() ? body.path("tools").size() : 0);
              audit.put("reasoning_items_sent", reasoning);
              audit.put("max_output_tokens", body.path("max_output_tokens").asInt());
              audit.put(
                  "format_type",
                  body.path("text").path("format").path("type").asText("UNSPECIFIED"));
              audit.put(
                  "reasoning_effort", body.path("reasoning").path("effort").asText("UNSPECIFIED"));
              audit.put("service_tier", body.path("service_tier").asText("UNSPECIFIED"));
              if (!response.isSuccessful()) {
                String diagnostic =
                    response.peekBody(8192).string().toLowerCase(java.util.Locale.ROOT);
                var flags = new java.util.ArrayList<String>();
                for (String fixed :
                    List.of(
                        "not supported",
                        "unsupported",
                        "json_schema",
                        "json_object",
                        "image",
                        "reasoning")) if (diagnostic.contains(fixed)) flags.add(fixed);
                audit.put("error_flags", flags);
              }
              report("WIRE", audit);
              return response;
            }));
    if (fixture) {
      policies = new java.util.ArrayList<>(policies);
      policies.addAll(fixtureTransport);
    }
    var descriptor =
        LlmModelProvider.forResponsesFixture(defaults, properties, policies)
            .resolvedEndpoint(LlmFeature.CHAT);
    models =
        LlmModelProvider.forResponsesAcceptance(
            defaults, properties, policies, Map.of(LlmFeature.CHAT, descriptor));
    payloads = new ModelMessagePayloadStore(source("payload.db"));
    beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    beans.addBean("payloads", payloads);
    models.configureModelMessagePayloads(beans.getBeanProvider(ModelMessagePayloadStore.class));
  }

  org.sqlite.SQLiteDataSource source(String name) {
    if (!name.matches("[a-z-]+\\.db"))
      throw new IllegalArgumentException("Owned database name required");
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve(name));
    return source;
  }

  ChatModel metered() {
    return metered(LlmFeature.CHAT);
  }

  ChatModel metered(String feature) {
    return new ResponsesAcceptanceMeter(
        models.chatModel(feature), budget, !target.equals("local-deepseek"));
  }

  void trace(String stage, Throwable failure) {
    try {
      var causes = new java.util.ArrayList<Map<String, Object>>();
      Throwable cause = failure;
      for (int depth = 0; cause != null && depth < 4; depth++, cause = cause.getCause()) {
        var frames =
            java.util.Arrays.stream(cause.getStackTrace())
                .limit(20)
                .map(StackTraceElement::toString)
                .toList();
        causes.add(Map.of("type", cause.getClass().getSimpleName(), "frames", frames));
      }
      report("SAFE_EXCEPTION_STACK", Map.of("stage", stage, "causes", causes));
    } catch (java.io.IOException ignored) {
      throw new IllegalStateException("Safe diagnostic write failed");
    }
  }

  private static String hash(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  synchronized void report(String id, Map<String, ?> fields) throws java.io.IOException {
    var record = new java.util.LinkedHashMap<String, Object>();
    record.put("case", id);
    record.put("target", target);
    record.put("timestamp", java.time.Instant.now().toString());
    record.putAll(fields);
    java.nio.file.Files.writeString(
        root.resolve("results.jsonl"),
        JSON.writeValueAsString(record) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  private synchronized int reserveWire() throws java.io.IOException {
    try (var channel =
            FileChannel.open(
                root.resolve("wire.ledger"),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      if (!lock.isValid() || channel.size() > 2048)
        throw new IllegalStateException("Wire quota rejected");
      var buffer = ByteBuffer.allocate((int) channel.size());
      while (buffer.hasRemaining())
        if (channel.read(buffer) < 0) throw new IllegalStateException("Wire quota rejected");
      String history = new String(buffer.array(), StandardCharsets.UTF_8);
      if (!history.isEmpty() && !history.endsWith("\n"))
        throw new IllegalStateException("Wire quota rejected");
      int count = 0;
      for (String line : history.split("\n")) {
        if (line.isEmpty()) continue;
        if (!Integer.toString(count + 1).equals(line))
          throw new IllegalStateException("Wire quota rejected");
        count++;
      }
      if (count >= 200 || count + 1 > budget.snapshot().calls())
        throw new IllegalStateException("Unreserved API request rejected");
      channel.position(channel.size());
      var next =
          ByteBuffer.wrap((Integer.toString(count + 1) + "\n").getBytes(StandardCharsets.UTF_8));
      while (next.hasRemaining()) channel.write(next);
      channel.force(true);
      return count + 1;
    } catch (NumberFormatException invalid) {
      throw new IllegalStateException("Wire quota rejected");
    }
  }
}
