package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** The normal profile gate reaches the pinned SDK through an entirely owned transport. */
class ResponsesProductionTransportTest {
  private final ObjectMapper json = new ObjectMapper();
  private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

  private LlmModelProvider provider() {
    return provider(2048);
  }

  private LlmModelProvider provider(int tokens) {
    return LlmModelProvider.forResponsesProductionTransport(
        org.mockito.Mockito.mock(ChatModel.class),
        ResponsesProductionGateTest.properties(
            LlmFeature.CHAT,
            Map.of("model", "gpt-6.2-sol", "max-output-tokens", Integer.toString(tokens))),
        List.of(http ->
            http.interceptor(chain -> {
              assertThat(chain.request().url().toString())
                  .isEqualTo("https://api.openai.com/v1/responses");
              var bytes = new okio.Buffer();
              chain.request().body().writeTo(bytes);
              var request = json.readTree(bytes.readByteArray());
              requests.add(request);
              String response =
                  "{\"id\":\"owned_profile\",\"object\":\"response\",\"created_at\":0,\"status\":\"completed\",\"model\":\"gpt-6.2-sol\",\"output\":[{\"type\":\"message\",\"id\":\"owned_final\",\"role\":\"assistant\",\"status\":\"completed\",\"phase\":\"final_answer\",\"content\":[{\"type\":\"output_text\",\"text\":\"owned profile response\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5,\"input_tokens_details\":{\"cached_tokens\":0},\"output_tokens_details\":{\"reasoning_tokens\":0}}}";
              boolean stream = request.path("stream").asBoolean();
              String body =
                  stream
                      ? "event: response.completed\ndata: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":"
                          + response
                          + "}\n\n"
                      : response;
              // Never chain.proceed: neither DNS nor an external HTTP request is performed.
              return new okhttp3.Response.Builder()
                  .request(chain.request())
                  .protocol(okhttp3.Protocol.HTTP_1_1)
                  .code(200)
                  .message("owned fixture")
                  .body(
                      okhttp3.ResponseBody.create(
                          body,
                          okhttp3.MediaType.get(stream ? "text/event-stream" : "application/json")))
                  .build();
            })));
  }

  private OpenAiResponsesChatOptions options(LlmModelProvider provider) {
    var options =
        (OpenAiResponsesChatOptions) provider.requestOptions(LlmFeature.CHAT, null, false);
    var context = new java.util.HashMap<String, Object>(options.getToolContext());
    context.put(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, "owned-profile-run");
    context.put("owned-context-marker", "retained");
    return options.mutate().toolContext(null).toolContext(context).build();
  }

  private void invoke(ChatModel model, OpenAiResponsesChatOptions options, boolean stream) {
    if (stream) model.stream(new Prompt("owned synthetic request", options)).collectList().block();
    else model.call(new Prompt("owned synthetic request", options));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void configured8192TokenBudgetReachesTheWireWithoutBeingReduced(boolean stream) {
    var provider = provider(8192);
    invoke(provider.chatModel(LlmFeature.CHAT), options(provider), stream);
    assertThat(requests).hasSize(1);
    assertThat(requests.getFirst().path("max_output_tokens").asInt()).isEqualTo(8192);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptedProductionEnvelopeReachesSyncAndSseWithoutChangingItsInputOptions(boolean stream) {
    var provider = provider();
    var original =
        options(provider)
            .mutate()
            .maxOutputTokens(1536)
            .reasoningEffort(null)
            .serviceTier(null)
            .build();
    invoke(provider.chatModel(LlmFeature.CHAT), original, stream);
    assertThat(requests).hasSize(1);
    var sent = requests.getFirst();
    assertThat(sent.path("model").asText()).isEqualTo("gpt-6.2-sol");
    assertThat(sent.path("reasoning").path("effort").asText()).isEqualTo("low");
    assertThat(sent.path("service_tier").asText()).isEqualTo("default");
    assertThat(sent.path("max_output_tokens").asInt()).isEqualTo(1536);
    assertThat(sent.path("store").asBoolean(true)).isFalse();
    assertThat(sent.hasNonNull("previous_response_id") || sent.hasNonNull("conversation"))
        .isFalse();
    assertThat(sent.path("include").toString()).contains("reasoning.encrypted_content");
    assertThat(original.getReasoningEffort()).isNull();
    assertThat(original.getServiceTier()).isNull();
    assertThat(original.getToolContext()).containsEntry("owned-context-marker", "retained");
  }

  @ParameterizedTest
  @CsvSource({
    "effort,false",
    "effort,true",
    "tier,false",
    "tier,true",
    "metadata,false",
    "metadata,true",
    "cache,false",
    "cache,true",
    "safety,false",
    "safety,true",
    "include,false",
    "include,true"
  })
  void unverifiedPerRequestOverridesFailBeforeAnyHttp(String field, boolean stream) {
    var provider = provider();
    var options = options(provider).mutate();
    switch (field) {
      case "effort" -> options.reasoningEffort("medium");
      case "tier" -> options.serviceTier("flex");
      case "metadata" -> options.metadata(Map.of("owned", "unverified"));
      case "cache" -> options.promptCacheKey("owned-unverified-cache-key");
      case "safety" -> options.safetyIdentifier("owned-unverified-safety-id");
      default -> options.include(List.of("file_search_call.results"));
    }
    var request = options.build();
    assertThatThrownBy(() -> invoke(provider.chatModel(LlmFeature.CHAT), request, stream))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(requests).isEmpty();
  }

  @Test
  void anExplicitChildModelCannotBorrowTheAcceptedParentProfile() {
    var provider = provider();
    assertThat(provider.subAgentChatModel("gpt-6.2-sol"))
        .isSameAs(provider.chatModel(LlmFeature.CHAT));
    assertThatThrownBy(() -> provider.subAgentChatModel("unverified-child-model"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(requests).isEmpty();
  }

  @Test
  void inheritedVoiceCorrectionCannotBorrowChatAcceptance() {
    var provider = provider();
    assertThatThrownBy(provider::voiceCorrectionChatModel)
        .isInstanceOf(IllegalStateException.class);
    assertThat(requests).isEmpty();
  }
}
