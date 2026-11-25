package dev.mikoto2000.rei.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.modelmessage.ModelMessageCodec;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import dev.mikoto2000.rei.modelmessage.ModelTokenUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/**
 * Explicit standalone test JVM only; not a Spring bean, production flag or alternate agent loop.
 */
public final class ResponsesLiveAcceptance {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SYSTEM = "Follow the user request. Return only the requested answer.";
  private final Path root;
  private final String provider;
  private final ResponsesLiveBudget budget;
  private final ResponsesAdapter adapter;
  private final OpenAiResponsesChatOptions options;
  private final ModelMessagePayloadStore store;
  private final ModelMessagePayloadStore.Scope scope;
  private final ResolvedLlmEndpoint endpoint;

  private ResponsesLiveAcceptance(String provider, Path root) throws Exception {
    if (!"true".equals(System.getenv("REI_LIVE_APPROVED_INITIAL")))
      throw new IllegalStateException("Explicit initial trial approval required");
    if (!List.of("openai", "local-deepseek").contains(provider))
      throw new IllegalArgumentException("Explicit approved target required");
    this.provider = provider;
    this.root = root.toRealPath();
    boolean openai = provider.equals("openai");
    String base = openai ? "https://api.openai.com/v1" : "http://gx10-707e.local:8888/v1";
    String model = openai ? "gpt-5.6-sol" : "deepseek-v4.1-flash-1017";
    String key = System.getenv(openai ? "OPENAI_API_KEY" : "REI_OPENAI_API_KEY");
    if (!openai && (key == null || key.isBlank())) key = "dummy-key";
    if (key == null || key.isBlank() || openai && key.equals("dummy-key"))
      throw new IllegalStateException("Approved credential unavailable");
    endpoint =
        new ResolvedLlmEndpoint(
            "initial-" + provider,
            1,
            LlmApiType.RESPONSES,
            base,
            "initial-credential",
            model,
            new LlmCapabilities(
                openai ? ReasoningReplayMode.ENCRYPTED : ReasoningReplayMode.PLAINTEXT,
                128,
                java.util.Set.of(
                    LlmCapabilities.Setting.REASONING_EFFORT,
                    LlmCapabilities.Setting.STRUCTURED_OUTPUT)),
            2000,
            null,
            Duration.ofSeconds(120));
    budget =
        new ResponsesLiveBudget(
            this.root.resolve("calls.ledger"), endpoint.fingerprint(), Clock.systemUTC());
    var builder =
        OpenAiResponsesChatOptions.builder()
            .baseUrl(base)
            .apiKey(key)
            .model(model)
            .maxRetries(0)
            .maxOutputTokens(2000)
            .timeout(Duration.ofSeconds(120))
            .strict(false)
            .toolContext(Map.of(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, "initial-" + provider));
    if (openai) builder.reasoningEffort("low").serviceTier("default");
    options = builder.build();
    adapter =
        ResponsesAdapter.create(
            endpoint,
            options,
            List.of(http ->
                http.interceptor(chain -> {
                  var request = chain.request();
                  if (!request.url().toString().equals(base + "/responses")
                      || !request.method().equals("POST")
                      || request.body() == null)
                    throw new IllegalStateException("Unapproved live request target");
                  var buffer = new okio.Buffer();
                  request.body().writeTo(buffer);
                  byte[] body = buffer.readByteArray();
                  var node = JSON.readTree(body);
                  if (!model.equals(node.path("model").asText())
                      || node.path("store").asBoolean(true)
                      || node.hasNonNull("previous_response_id")
                      || node.hasNonNull("conversation"))
                    throw new IllegalStateException("Unapproved live request fields");
                  int reservation =
                      budget.reserve(body.length, node.path("max_output_tokens").asInt());
                  chain
                      .call()
                      .timeout()
                      .timeout(
                          Math.min(120000, budget.remainingMillis()),
                          java.util.concurrent.TimeUnit.MILLISECONDS);
                  var response = chain.proceed(request);
                  if (response.priorResponse() != null
                      || !response.request().url().equals(request.url())) {
                    response.close();
                    throw new IllegalStateException("Live redirect is not accepted");
                  }
                  if (response.code() >= 400) {
                    try {
                      var error = JSON.readTree(response.peekBody(65536).string()).path("error");
                      String message =
                          error.path("message").asText("").toLowerCase(java.util.Locale.ROOT);
                      var flags =
                          List.of(
                                  "json_schema",
                                  "schema",
                                  "reasoning",
                                  "not supported",
                                  "unsupported",
                                  "not implemented",
                                  "grammar",
                                  "invalid",
                                  "name")
                              .stream()
                              .filter(message::contains)
                              .toList();
                      report(
                          "HTTP_ERROR_SHAPE",
                          Map.of("http_status", response.code(), "message_flags", flags));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                      report(
                          "HTTP_ERROR_SHAPE",
                          Map.of("http_status", response.code(), "json", false));
                    }
                  }
                  report(
                      "WIRE",
                      Map.of(
                          "reservation",
                          reservation,
                          "http_status",
                          response.code(),
                          "request_bytes",
                          body.length,
                          "request_sha256",
                          hash(body),
                          "stream",
                          node.path("stream").asBoolean(),
                          "reasoning_items_sent",
                          count(node.path("input"), "reasoning")));
                  return response;
                })));
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + this.root.resolve("payload.db"));
    store = new ModelMessagePayloadStore(source);
    scope =
        new ModelMessagePayloadStore.Scope(
            "initial-project", this.root.toString(), "initial-session");
  }

  private static int count(com.fasterxml.jackson.databind.JsonNode items, String type) {
    int count = 0;
    if (items.isArray())
      for (var item : items) if (type.equals(item.path("type").asText())) count++;
    return count;
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private void report(String id, Map<String, ?> numbers) throws java.io.IOException {
    var record = new java.util.LinkedHashMap<String, Object>();
    record.put("case", id);
    record.put("provider", provider);
    record.put("timestamp", java.time.Instant.now().toString());
    record.putAll(numbers);
    Files.writeString(
        root.resolve("results.jsonl"),
        JSON.writeValueAsString(record) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  private ChatResponse call(
      String id, List<Message> messages, boolean stream, OpenAiResponsesChatOptions configured)
      throws Exception {
    long started = System.nanoTime();
    var firstText = new AtomicLong();
    ChatResponse result;
    if (stream) {
      var completed = new AtomicReference<ChatResponse>();
      new MessageAggregator()
          .aggregate(
              adapter.stream(new Prompt(messages, configured))
                  .doOnNext(chunk -> {
                    if (chunk.getResult() != null
                        && chunk.getResult().getOutput().getText() != null
                        && !chunk.getResult().getOutput().getText().isEmpty())
                      firstText.compareAndSet(0, System.nanoTime());
                  }),
              completed::set)
          .blockLast(Duration.ofSeconds(120));
      result = completed.get();
    } else result = adapter.call(new Prompt(messages, configured));
    var usage = ModelTokenUsage.from(result);
    if (usage.input() == null
        || usage.output() == null
        || usage.input() > 10000
        || usage.output() > 2000)
      throw new IllegalStateException("Live usage does not fit the reserved bounds");
    var values = new java.util.LinkedHashMap<String, Object>();
    values.put("status", "RECEIVED");
    values.put("usage", usage);
    values.put("duration_ms", (System.nanoTime() - started) / 1_000_000.0);
    if (firstText.get() != 0) values.put("ttft_ms", (firstText.get() - started) / 1_000_000.0);
    var assistant = result.getResult().getOutput();
    var codec = new ModelMessageCodec();
    byte[] encoded = codec.encode(assistant);
    values.put("message_sha256", hash(encoded));
    values.put("message_bytes", encoded.length);
    values.put(
        "reasoning_items",
        assistant.getParts().stream().filter(ReasoningPart.class::isInstance).count());
    report(id, values);
    return result;
  }

  private static void requireAnswer(ChatResponse result, String expected) {
    if (!expected.equals(result.getResult().getOutput().getText().trim()))
      throw new IllegalStateException("Synthetic answer mismatch");
  }

  private void basic() throws Exception {
    var first =
        call(
            "SYNC",
            List.of(
                new SystemMessage(SYSTEM),
                new UserMessage("What is 19+23? Return only the decimal number.")),
            false,
            options);
    requireAnswer(first, "42");
    var ref =
        store.save(scope, "initial-" + provider, "first-answer", first.getResult().getOutput());
    JSON.writeValue(root.resolve("first-ref.json").toFile(), ref);
    byte[] original = new ModelMessageCodec().encode(first.getResult().getOutput());
    var restored = new ModelMessagePayloadStore(dataSource()).load(scope, ref);
    if (!java.util.Arrays.equals(original, new ModelMessageCodec().encode(restored)))
      throw new IllegalStateException("Durable message mismatch");
    report("SQLITE", Map.of("status", "PASSED", "payload_sha256", ref.sha256()));
    var streamed =
        call(
            "STREAM",
            List.of(
                new SystemMessage(SYSTEM),
                new UserMessage("What is 13+30? Return only the decimal number.")),
            true,
            options);
    requireAnswer(streamed, "43");
    report("BASIC", Map.of("status", "PASSED"));
  }

  private org.sqlite.SQLiteDataSource dataSource() {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("payload.db"));
    return source;
  }

  private void resume() throws Exception {
    var ref =
        JSON.readValue(root.resolve("first-ref.json").toFile(), ModelMessagePayloadStore.Ref.class);
    var restored = (AssistantMessage) store.load(scope, ref);
    var result =
        call(
            "PROCESS_RESTART_REPLAY",
            List.of(
                new SystemMessage(SYSTEM),
                new UserMessage("What is 19+23? Return only the decimal number."),
                restored,
                new UserMessage("Add one to the previous answer. Return only the decimal number.")),
            false,
            options);
    requireAnswer(result, "43");
    report("PROCESS_RESTART", Map.of("status", "PASSED", "source_payload_sha256", ref.sha256()));
    structured();
  }

  private void structured() throws Exception {
    var format = new org.springframework.ai.openai.OpenAiChatModel.ResponseFormat();
    format.setType(org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA);
    format.setStrict(true);
    format.setJsonSchema(
        "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"integer\"}},\"required\":[\"answer\"],\"additionalProperties\":false}");
    var structured =
        call(
            "JSON_SCHEMA",
            List.of(new UserMessage("Return JSON with answer equal to 42.")),
            false,
            options.mutate().responseFormat(format).build());
    var answer = JSON.readTree(structured.getResult().getOutput().getText());
    if (!answer.isObject()
        || answer.size() != 1
        || !answer.path("answer").isIntegralNumber()
        || answer.path("answer").longValue() != 42)
      throw new IllegalStateException("Synthetic JSON schema answer mismatch");
    report("STRUCTURED", Map.of("status", "PASSED"));
  }

  private void tools() throws Exception {
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "initial-" + provider + "-tools", "initial-tools", root, "initial-project");
    var exchange = store.newExchange(owner);
    var guard =
        new dev.mikoto2000.rei.core.policy.ToolPermissionGuard(
            new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
                new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(
                    true, null, null, null)),
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            event -> {});
    var executions = new java.util.concurrent.atomic.AtomicInteger();
    org.springframework.ai.tool.ToolCallback callback =
        new org.springframework.ai.tool.ToolCallback() {
          public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("now")
                .description(
                    "Owned test fixture dataset, not the current time. Call exactly once to retrieve the moduli and remainders.")
                .inputSchema(
                    "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                .build();
          }

          public String call(String input) {
            guard.validateArguments("now", input, owner);
            guard.check("now", input, owner);
            if (executions.incrementAndGet() != 1)
              throw new IllegalStateException("Synthetic read was repeated");
            return "{\"moduli\":[17,19,23],\"remainders\":[5,7,11]}";
          }
        };
    var catalog = dev.mikoto2000.rei.llm.discovery.ToolCatalog.standard(List.of(callback));
    var context = new java.util.HashMap<String, Object>();
    context.put(ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY, exchange);
    context.put(
        ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
        new ModelMessagePayloadStore.Origin(exchange.ownerKey(), endpoint.fingerprint()));
    context.put(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, exchange.ownerKey());
    context.put(ResolvedLlmEndpoint.CONTEXT_KEY, endpoint);
    var toolOptions =
        options
            .mutate()
            .toolContext(null)
            .toolContext(context)
            .toolCallbacks(catalog.callback("now"))
            .build();
    if (provider.equals("openai"))
      toolOptions = toolOptions.mutate().reasoningEffort("medium").build();
    var runBudget = new OutputLimitRunBudget(1, 3, null, 20000);
    var reservation =
        new OutputLimitRunBudget.LlmCallReservation() {
          public boolean tryReserve() {
            return runBudget.tryConsumeLlmCall();
          }

          public int remaining() {
            return runBudget.remainingLlmCalls();
          }

          public void recordTotalTokens(Integer tokens) {
            runBudget.recordTotalTokens(tokens);
          }

          public boolean tokenLimitEnabled() {
            return runBudget.tokenLimitEnabled();
          }

          public boolean tokenExhausted() {
            return runBudget.tokenExhausted();
          }

          public boolean usageUnknown() {
            return runBudget.usageUnknown();
          }
        };
    var outcome =
        new dev.mikoto2000.rei.core.chat.BoundedToolLoop()
            .runWithHistory(
                adapter,
                new Prompt(
                    "Call now exactly once for the synthetic dataset. Find the smallest positive integer x whose residues modulo each given modulus match the corresponding remainders. Return only the decimal integer.",
                    toolOptions),
                new java.util.concurrent.atomic.AtomicInteger(3),
                owner,
                () -> {},
                reservation)
            .block(Duration.ofSeconds(240));
    if (outcome == null || !"7417".equals(outcome.output().trim()) || executions.get() != 1)
      throw new IllegalStateException("Synthetic tool task verification failed");
    var reasoning = new java.util.ArrayList<Map<String, Object>>();
    for (var message : outcome.history()) {
      if (!(message instanceof AssistantMessage assistant)) continue;
      for (var part : assistant.getParts())
        if (part instanceof ReasoningPart item) {
          String content = item.payload() != null ? item.payload().data() : item.text();
          if (content != null) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            reasoning.add(
                Map.of(
                    "bytes",
                    bytes.length,
                    "sha256",
                    hash(bytes),
                    "encrypted",
                    item.payload() != null));
          }
        }
    }
    report(
        "FUNCTION_TOOL_LOOP",
        Map.of(
            "status",
            "PASSED",
            "tool_executions",
            executions.get(),
            "llm_calls",
            runBudget.snapshot().llmCalls(),
            "total_tokens",
            runBudget.totalTokens(),
            "reasoning",
            reasoning));
  }

  private void replayToolAnswer() throws Exception {
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "initial-" + provider + "-tools", "initial-tools", root, "initial-project");
    var exchange = store.newExchange(owner);
    var savedScope = ModelMessagePayloadStore.Scope.forOwner(owner);
    var selected = storedAnswer(store, dataSource(), savedScope, owner.runId());
    var saved = selected.message();
    String sourceHash = selected.sha256();
    var context = new java.util.HashMap<String, Object>();
    context.put(ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY, exchange);
    context.put(
        ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
        new ModelMessagePayloadStore.Origin(exchange.ownerKey(), endpoint.fingerprint()));
    context.put(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, exchange.ownerKey());
    context.put(ResolvedLlmEndpoint.CONTEXT_KEY, endpoint);
    var replayOptions = options.mutate().toolContext(null).toolContext(context).build();
    var response =
        call(
            "STORED_REASONING_REPLAY",
            List.of(
                new UserMessage(
                    "Find the smallest positive integer whose remainders modulo 17,19,23 are 5,7,11, respectively."),
                saved,
                new UserMessage(
                    "Add one to the previous answer. Return only the decimal integer.")),
            false,
            replayOptions);
    requireAnswer(response, "7418");
    report(
        "REASONING_PROCESS_RESTART",
        Map.of("status", "PASSED", "source_payload_sha256", sourceHash));
  }

  record StoredAnswer(AssistantMessage message, String sha256) {}

  static StoredAnswer storedAnswer(
      ModelMessagePayloadStore store,
      org.sqlite.SQLiteDataSource source,
      ModelMessagePayloadStore.Scope savedScope,
      String run)
      throws Exception {
    var references = new java.util.ArrayList<ModelMessagePayloadStore.Ref>();
    try (var connection = source.getConnection();
        var query =
            connection.prepareStatement(
                "SELECT id,sha256 FROM model_message_payloads WHERE project=? AND root_path=? AND conversation=? AND run=? ORDER BY rowid DESC")) {
      query.setString(1, savedScope.project());
      query.setString(2, savedScope.root());
      query.setString(3, savedScope.conversation());
      query.setString(4, run);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          references.add(new ModelMessagePayloadStore.Ref(rows.getString(1), rows.getString(2)));
        }
      }
    }
    for (var ref : references) {
      var message = store.load(savedScope, ref);
      if (message instanceof AssistantMessage assistant
          && assistant.getToolCalls().isEmpty()
          && "7417".equals(assistant.getText().trim())
          && assistant.getParts().stream().anyMatch(ReasoningPart.class::isInstance))
        return new StoredAnswer(assistant, ref.sha256());
    }
    throw new IllegalStateException("Completed owned reasoning answer missing");
  }

  public static void main(String[] arguments) {
    ((ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
        .setLevel(ch.qos.logback.classic.Level.OFF);
    try {
      if (arguments.length != 3)
        throw new IllegalArgumentException("Target, isolated root and action required");
      var trial = new ResponsesLiveAcceptance(arguments[0], Path.of(arguments[1]));
      try {
        if (arguments[2].equals("basic")) trial.basic();
        else if (arguments[2].equals("resume")) trial.resume();
        else if (arguments[2].equals("tools")) trial.tools();
        else if (arguments[2].equals("structured")) trial.structured();
        else if (arguments[2].equals("reasoning-replay")) trial.replayToolAnswer();
        else throw new IllegalArgumentException("Explicit initial action required");
      } catch (Exception failure) {
        var category =
            failure instanceof ResponsesTransportException transport
                ? transport.category().name()
                : failure.getClass().getSimpleName();
        String diagnostic =
            java.util.Set.of(
                        "Completed owned reasoning answer missing",
                        "Model message persistence rejected",
                        "Responses replay owner mismatch",
                        "Responses replay endpoint mismatch",
                        "Approved initial live budget rejected")
                    .contains(java.util.Objects.toString(failure.getMessage(), ""))
                ? failure.getMessage()
                : "SUPPRESSED";
        trial.report(
            "FAILURE", Map.of("status", "FAILED", "category", category, "diagnostic", diagnostic));
        throw new IllegalStateException("Initial trial failed; inspect numeric evidence");
      }
      System.out.println("INITIAL_TRIAL_ACTION_PASSED");
      System.exit(0);
    } catch (Exception failure) {
      System.err.println("INITIAL_TRIAL_ACTION_FAILED (details suppressed; no automatic retry)");
      System.exit(1);
    }
  }
}
