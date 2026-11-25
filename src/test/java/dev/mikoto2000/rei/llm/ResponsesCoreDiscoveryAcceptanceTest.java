package dev.mikoto2000.rei.llm;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class ResponsesCoreDiscoveryAcceptanceTest {
  @TempDir Path root;

  @Test
  void realCoreCompletesAfterDiscoveryAndOneOwnedSpecialistResult() throws Exception {
    var calls = new AtomicInteger();
    org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer fixture =
        http ->
            http.interceptor(chain -> {
              int call = calls.incrementAndGet();
              String output =
                  switch (call) {
                    case 1 ->
                        "{\"type\":\"function_call\",\"id\":\"fc_search\",\"call_id\":\"call_search\",\"name\":\"searchTools\",\"arguments\":\"{\\\"query\\\":\\\"getGoalCompletionDefinition\\\",\\\"maxResults\\\":1}\",\"status\":\"completed\"}";
                    case 2 ->
                        "{\"type\":\"function_call\",\"id\":\"fc_specialist\",\"call_id\":\"call_specialist\",\"name\":\"getGoalCompletionDefinition\",\"arguments\":\"{}\",\"status\":\"completed\"}";
                    case 3 ->
                        "{\"type\":\"message\",\"id\":\"message_final\",\"role\":\"assistant\",\"status\":\"completed\",\"phase\":\"final_answer\",\"content\":[{\"type\":\"output_text\",\"text\":\"fixture-owned-value\",\"annotations\":[]}]}";
                    default -> throw new IllegalStateException("Unexpected fixture request count");
                  };
              String response =
                  "{\"id\":\"response_"
                      + call
                      + "\",\"object\":\"response\",\"created_at\":0,\"status\":\"completed\",\"model\":\"acceptance-fixture-model\",\"output\":[{\"type\":\"reasoning\",\"id\":\"reasoning_"
                      + call
                      + "\",\"status\":\"completed\",\"summary\":[],\"encrypted_content\":\"owned-opaque-fixture\"},"
                      + output
                      + "],\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5,\"input_tokens_details\":{\"cached_tokens\":0},\"output_tokens_details\":{\"reasoning_tokens\":1}}}";
              String sse =
                  "event: response.completed\ndata: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":"
                      + response
                      + "}\n\n";
              return new okhttp3.Response.Builder()
                  .request(chain.request())
                  .protocol(okhttp3.Protocol.HTTP_1_1)
                  .code(200)
                  .message("owned fixture")
                  .body(
                      okhttp3.ResponseBody.create(sse, okhttp3.MediaType.get("text/event-stream")))
                  .build();
            });
    var runtime = new ResponsesAcceptanceRuntime(root, List.of(fixture));
    try (var diagnostics =
        org.mockito.Mockito.mockStatic(
            dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.class,
            invocation -> {
              for (var argument : invocation.getArguments())
                if (argument instanceof Throwable error) runtime.trace("CORE_DIAGNOSTIC", error);
              return invocation.callRealMethod();
            })) {
      var failure =
          org.assertj.core.api.Assertions.catchThrowable(
              () -> ResponsesCoreLiveAcceptance.discovery(runtime, "fixture"));
      org.assertj.core.api.Assertions.assertThat(failure)
          .as(
              "owned numeric fixture evidence: %s",
              java.nio.file.Files.readString(root.resolve("results.jsonl")))
          .isNull();
    }
  }
}
