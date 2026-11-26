package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** SDK serialization fixtures only: no account, DNS, public HTTP or real inference. */
class PlanResponsesRequestTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void planAlwaysUsesStreamingStatelessRequestsWithExplicitHistory(boolean streaming)
      throws Exception {
    try (var fixture = new Fixture()) {
      var options = fixture.options();
      var model = ResponsesAdapter.createForRun(fixture.pin, options, fixture.client);
      var prompt =
          new Prompt(
              List.of(
                  new SystemMessage("fixture instructions"), new UserMessage("fixture question")),
              options);
      if (streaming) model.stream(prompt).collectList().block(Duration.ofSeconds(5));
      else assertThat(model.call(prompt).getResult().getOutput().getText()).isEqualTo("done");
      assertThat(fixture.requests).hasSize(1);
      var wire = fixture.requests.getFirst();
      assertThat(wire.path("stream").asBoolean()).isTrue();
      assertThat(wire.path("store").asBoolean(true)).isFalse();
      assertThat(wire.path("input").isArray()).isTrue();
      assertThat(wire.path("input").toString())
          .contains("fixture question")
          .doesNotContain("system");
      assertThat(wire.path("instructions").asText()).isEqualTo("fixture instructions");
      assertThat(wire.path("include").toString()).contains("reasoning.encrypted_content");
      for (var field :
          List.of(
              "max_output_tokens",
              "temperature",
              "top_p",
              "previous_response_id",
              "background",
              "conversation",
              "max_tool_calls",
              "metadata",
              "moderation",
              "multi_agent",
              "prompt",
              "prompt_cache_retention",
              "safety_identifier",
              "top_logprobs",
              "truncation",
              "user",
              "service_tier",
              "reasoning")) assertThat(wire.has(field)).as(field).isFalse();
    }
  }

  @Test
  void blockingPlanCallCannotSucceedFromDeltasWithoutACompletedEvent() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.reply =
          Fixture.event(
              "response.output_text.delta",
              "\"item_id\":\"msg_fixture\",\"output_index\":0,\"content_index\":0,\"delta\":\"partial\"");
      var options = fixture.options();
      var model = ResponsesAdapter.createForRun(fixture.pin, options, fixture.client);
      assertThatThrownBy(() -> model.call(new Prompt("fixture", options)))
          .isInstanceOf(ResponsesTransportException.class);
      assertThat(fixture.requests).hasSize(1);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "max_output_tokens",
        "temperature",
        "top_p",
        "max_tool_calls",
        "truncation",
        "service_tier",
        "prompt_cache_key",
        "safety_identifier",
        "metadata"
      })
  void unsupportedExplicitPlanSettingsNameTheFieldWithoutEchoingItsValue(String field)
      throws Exception {
    try (var fixture = new Fixture()) {
      var defaults = fixture.options();
      var builder = defaults.mutate();
      switch (field) {
        case "max_output_tokens" -> builder.maxOutputTokens(1);
        case "temperature" -> builder.temperature(0.1);
        case "top_p" -> builder.topP(0.9);
        case "max_tool_calls" -> builder.maxToolCalls(1);
        case "truncation" -> builder.truncation("disabled");
        case "service_tier" -> builder.serviceTier("default");
        case "prompt_cache_key" -> builder.promptCacheKey("PRIVATE_OPTION_VALUE");
        case "safety_identifier" -> builder.safetyIdentifier("PRIVATE_OPTION_VALUE");
        case "metadata" -> builder.metadata(Map.of("private", "PRIVATE_OPTION_VALUE"));
        default -> throw new AssertionError(field);
      }
      var model = ResponsesAdapter.createForRun(fixture.pin, defaults, fixture.client);
      assertThatThrownBy(() -> model.call(new Prompt("fixture", builder.build())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(field)
          .hasMessageNotContaining("PRIVATE_OPTION_VALUE");
      assertThat(fixture.requests).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"authentication", "resource", "registration", "account", "replay", "streaming"})
  void untrustedPlanBindingsAreRejectedBeforeAnyRequest(String change) throws Exception {
    try (var fixture = new Fixture()) {
      var source = fixture.pin;
      var endpoint = source.endpoint();
      var capabilities = new java.util.HashMap<>(source.capabilities());
      if (change.equals("streaming"))
        capabilities.put(
            LlmEndpointCapabilities.Capability.STREAMING, LlmEndpointCapabilities.Support.UNKNOWN);
      var pin =
          new RunLlmConnection(
              source.profileId(),
              true,
              source.provider(),
              change.equals("authentication") ? LlmAuthentication.API_KEY : source.authentication(),
              change.equals("account") ? null : source.accountRef(),
              change.equals("registration") ? null : source.accountRegistration(),
              new ResolvedLlmEndpoint(
                  endpoint.endpointId(),
                  endpoint.generation(),
                  endpoint.apiType(),
                  change.equals("resource") ? "http://foreign.invalid/v1" : endpoint.baseUrl(),
                  endpoint.credentialRef(),
                  endpoint.model(),
                  new LlmCapabilities(
                      change.equals("replay")
                          ? ReasoningReplayMode.NONE
                          : ReasoningReplayMode.ENCRYPTED,
                      36,
                      Set.of()),
                  null,
                  null,
                  endpoint.readTimeout()),
              capabilities,
              Map.of());
      assertThatThrownBy(() ->
              ResponsesAdapter.createForRun(
                  pin, (OpenAiResponsesChatOptions) RunNativeOptions.options(pin), fixture.client))
          .isInstanceOf(IllegalStateException.class);
      assertThat(fixture.requests).isEmpty();
    }
  }

  static final class Fixture implements AutoCloseable {
    final RunLlmConnection pin =
        new RunLlmConnection(
            "plan",
            true,
            LlmProvider.CHATGPT_PLAN,
            LlmAuthentication.CHATGPT_OAUTH,
            "personal",
            "fixture-registration",
            new ResolvedLlmEndpoint(
                "plan",
                1,
                LlmApiType.RESPONSES,
                "https://api.openai.com/v1",
                "chat-endpoint:plan",
                "fixture-model",
                new LlmCapabilities(ReasoningReplayMode.ENCRYPTED, 36, Set.of()),
                null,
                null,
                Duration.ofSeconds(5)),
            Map.of(
                LlmEndpointCapabilities.Capability.RESPONSES,
                LlmEndpointCapabilities.Support.SUPPORTED,
                LlmEndpointCapabilities.Capability.STREAMING,
                LlmEndpointCapabilities.Support.SUPPORTED,
                LlmEndpointCapabilities.Capability.REASONING_REPLAY,
                LlmEndpointCapabilities.Support.SUPPORTED),
            Map.of());
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final okhttp3.OkHttpClient transport =
        new okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();
    final com.openai.client.OpenAIClient client;
    int status = 200;
    java.util.function.IntFunction<String> replies;
    String mediaType = "text/event-stream";
    String reply =
        event(
            "response.completed",
            "\"response\":"
                + """
                {"id":"resp_fixture","object":"response","created_at":0,"status":"completed",
                 "model":"fixture-model","output":[{"type":"message","id":"msg_fixture",
                 "role":"assistant","status":"completed","phase":"final_answer",
                 "content":[{"type":"output_text","text":"done","annotations":[]}]}],
                 "usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5}}
                """);

    Fixture() {
      client =
          PinnedOpenAiClients.open(
              pin,
              () -> "fixture-plan-access",
              transport,
              List.of(chain -> {
                assertThat(chain.request().url().toString())
                    .isEqualTo("https://api.openai.com/v1/responses");
                assertThat(chain.request().header("Authorization"))
                    .isEqualTo("Bearer fixture-plan-access");
                var buffer = new okio.Buffer();
                chain.request().body().writeTo(buffer);
                requests.add(new ObjectMapper().readTree(buffer.readByteArray()));
                return new okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(status)
                    .message("fixture")
                    .body(
                        okhttp3.ResponseBody.create(
                            replies == null ? reply : replies.apply(requests.size()),
                            okhttp3.MediaType.get(mediaType)))
                    .build();
              }),
              () -> {});
    }

    OpenAiResponsesChatOptions options() {
      var options = (OpenAiResponsesChatOptions) RunNativeOptions.options(pin);
      var context = new java.util.HashMap<>(options.getToolContext());
      context.put(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, "fixture-owner");
      return options.mutate().toolContext(context).build();
    }

    static String event(String type, String fields) {
      return "event: "
          + type
          + "\ndata: {\"type\":\""
          + type
          + "\",\"sequence_number\":1,"
          + fields.replace("\n", "").replace("\r", "")
          + "}\n\n";
    }

    public void close() {
      client.close();
      transport.connectionPool().evictAll();
      transport.dispatcher().executorService().shutdown();
    }
  }
}
