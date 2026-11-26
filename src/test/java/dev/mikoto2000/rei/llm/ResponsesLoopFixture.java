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
                    plan ? ReasoningReplayMode.ENCRYPTED : ReasoningReplayMode.NONE, 36, Set.of()),
                plan ? null : 64,
                null,
                Duration.ofSeconds(5)),
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
            plan ? Map.of() : Map.of("max_output_tokens", 64));
    client =
        PinnedOpenAiClients.open(
            pin,
            () -> {
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

  OpenAiResponsesChatOptions options() {
    return ((OpenAiResponsesChatOptions) RunNativeOptions.options(pin))
        .mutate()
        .toolContext(Map.of(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, "fixture-owner"))
        .build();
  }

  public void close() {
    client.close();
    transport.connectionPool().evictAll();
    transport.dispatcher().executorService().shutdown();
  }
}
