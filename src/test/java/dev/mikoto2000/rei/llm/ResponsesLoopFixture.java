package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** Production clients intercepted before DNS; the no-auth route must never read credentials. */
final class ResponsesLoopFixture implements AutoCloseable {
  final RunLlmConnection pin;
  final com.openai.client.OpenAIClient client;
  final okhttp3.OkHttpClient transport =
      new okhttp3.OkHttpClient.Builder()
          .followRedirects(false)
          .followSslRedirects(false)
          .retryOnConnectionFailure(false)
          .build();
  final List<JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
  final java.util.concurrent.atomic.AtomicInteger credentialReads =
      new java.util.concurrent.atomic.AtomicInteger();
  java.util.function.IntFunction<String> replies;
  final String reply =
      PlanResponsesRequestTest.Fixture.event(
          "response.completed",
          "\"response\":"
              + """
              {"id":"resp_done","object":"response","created_at":0,"status":"completed",
               "model":"fixture-model","output":[{"type":"message","id":"msg_done",
               "role":"assistant","status":"completed","content":[{"type":"output_text",
               "text":"done","annotations":[]}]}],"usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5}}
              """);

  ResponsesLoopFixture(boolean plan) {
    this(plan, Map.of());
  }

  ResponsesLoopFixture(
      boolean plan,
      Map<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support> inputCapabilities) {
    this(plan, inputCapabilities, Map.of());
  }

  ResponsesLoopFixture(
      boolean plan,
      Map<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support> inputCapabilities,
      Map<String, Object> generation) {
    assertThat(generation.keySet())
        .allMatch(key -> Set.of("reasoning_effort", "response_format").contains(key));
    String base = plan ? "https://api.openai.com/v1" : "http://127.0.0.1:1/v1";
    var support = LlmEndpointCapabilities.Support.SUPPORTED;
    var unsupported = LlmEndpointCapabilities.Support.UNSUPPORTED;
    pin =
        new RunLlmConnection(
            "fixture",
            true,
            plan ? LlmProvider.CHATGPT_PLAN : LlmProvider.OPENAI_COMPATIBLE,
            plan ? LlmAuthentication.CHATGPT_OAUTH : LlmAuthentication.NONE,
            plan ? "personal" : null,
            plan ? "fixture-registration" : null,
            new ResolvedLlmEndpoint(
                "fixture",
                1,
                LlmApiType.RESPONSES,
                base,
                "chat-endpoint:fixture",
                "fixture-model",
                new LlmCapabilities(
                    plan ? ReasoningReplayMode.ENCRYPTED : ReasoningReplayMode.NONE,
                    36,
                    settings(inputCapabilities, generation)),
                plan ? null : 64,
                null,
                Duration.ofSeconds(5)),
            withInputCapabilities(
                Map.of(
                    LlmEndpointCapabilities.Capability.RESPONSES,
                    support,
                    LlmEndpointCapabilities.Capability.STREAMING,
                    support,
                    LlmEndpointCapabilities.Capability.FUNCTION_CALLING,
                    support,
                    LlmEndpointCapabilities.Capability.TOOL_NAMESPACE,
                    plan ? support : unsupported,
                    LlmEndpointCapabilities.Capability.REASONING_REPLAY,
                    plan ? support : unsupported,
                    LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS,
                    plan ? unsupported : support),
                generationCapabilities(inputCapabilities, generation)),
            generationValues(plan, generation));
    client =
        PinnedOpenAiClients.open(
            pin,
            () -> {
              credentialReads.incrementAndGet();
              if (!plan) throw new AssertionError("No-auth route accessed credentials");
              return "fixture-plan-access";
            },
            transport,
            List.of(chain -> {
              assertThat(chain.request().url().toString()).isEqualTo(base + "/responses");
              if (plan)
                assertThat(chain.request().header("Authorization"))
                    .isEqualTo("Bearer fixture-plan-access");
              else
                assertThat(chain.request().headers().names())
                    .noneMatch(name ->
                        Set.of("authorization", "api-key", "x-api-key", "cookie")
                            .contains(name.toLowerCase(java.util.Locale.ROOT)));
              var buffer = new okio.Buffer();
              chain.request().body().writeTo(buffer);
              requests.add(
                  new com.fasterxml.jackson.databind.ObjectMapper()
                      .readTree(buffer.readByteArray()));
              return new okhttp3.Response.Builder()
                  .request(chain.request())
                  .protocol(okhttp3.Protocol.HTTP_1_1)
                  .code(200)
                  .message("fixture")
                  .body(
                      okhttp3.ResponseBody.create(
                          replies.apply(requests.size()),
                          okhttp3.MediaType.get("text/event-stream")))
                  .build();
            }),
            () -> {});
  }

  private static Map<String, Object> generationValues(
      boolean plan, Map<String, Object> generation) {
    var result = new java.util.HashMap<String, Object>(generation);
    if (!plan) result.put("max_output_tokens", 64);
    return Map.copyOf(result);
  }

  private static Set<LlmCapabilities.Setting> settings(
      Map<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support> capabilities,
      Map<String, Object> generation) {
    var settings = java.util.EnumSet.noneOf(LlmCapabilities.Setting.class);
    if (capabilities.get(LlmEndpointCapabilities.Capability.VISION)
        == LlmEndpointCapabilities.Support.SUPPORTED)
      settings.add(LlmCapabilities.Setting.IMAGE_INPUT);
    if (generation.containsKey("reasoning_effort"))
      settings.add(LlmCapabilities.Setting.REASONING_EFFORT);
    if (generation.containsKey("response_format"))
      settings.add(LlmCapabilities.Setting.STRUCTURED_OUTPUT);
    return Set.copyOf(settings);
  }

  private static Map<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support>
      generationCapabilities(
          Map<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support> inputs,
          Map<String, Object> generation) {
    var values =
        new java.util.EnumMap<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support>(
            LlmEndpointCapabilities.Capability.class);
    values.putAll(inputs);
    if (generation.containsKey("reasoning_effort"))
      values.put(
          LlmEndpointCapabilities.Capability.REASONING_EFFORT,
          LlmEndpointCapabilities.Support.SUPPORTED);
    if (generation.containsKey("response_format"))
      values.put(
          LlmEndpointCapabilities.Capability.STRUCTURED_OUTPUT,
          LlmEndpointCapabilities.Support.SUPPORTED);
    return Map.copyOf(values);
  }

  OpenAiResponsesChatOptions options() {
    return ((OpenAiResponsesChatOptions) RunNativeOptions.options(pin))
        .mutate()
        .toolContext(Map.of(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, "fixture-owner"))
        .build();
  }

  private static Map<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support>
      withInputCapabilities(
          Map<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support> defaults,
          Map<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support> inputs) {
    assertThat(inputs.keySet())
        .allMatch(capability ->
            capability == LlmEndpointCapabilities.Capability.VISION
                || capability == LlmEndpointCapabilities.Capability.FILE_INPUT
                || capability == LlmEndpointCapabilities.Capability.REASONING_EFFORT
                || capability == LlmEndpointCapabilities.Capability.STRUCTURED_OUTPUT);
    var values =
        new java.util.EnumMap<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support>(
            LlmEndpointCapabilities.Capability.class);
    values.putAll(defaults);
    values.putAll(inputs);
    return Map.copyOf(values);
  }

  public void close() {
    client.close();
    transport.connectionPool().evictAll();
    transport.dispatcher().executorService().shutdown();
  }
}
