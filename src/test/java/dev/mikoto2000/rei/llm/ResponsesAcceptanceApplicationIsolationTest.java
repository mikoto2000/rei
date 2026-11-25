package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual Spring boot isolation is proved with no network and no live approval flag. */
@Tag("integration")
class ResponsesAcceptanceApplicationIsolationTest {
  @TempDir Path root;

  @Test
  void actualApplicationBootsWithoutSchedulersBusinessCallbacksOrAnyModelRequest()
      throws Exception {
    String previous = System.getProperty("rei.data-dir");
    String restart = System.getProperty("spring.devtools.restart.enabled");
    try {
      org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer fixture =
          http ->
              http.interceptor(chain -> {
                String response =
                    "{\"id\":\"owned_app_smoke\",\"object\":\"response\",\"created_at\":0,\"status\":\"completed\",\"model\":\"acceptance-fixture-model\",\"output\":[{\"type\":\"message\",\"id\":\"owned_final\",\"role\":\"assistant\",\"status\":\"completed\",\"phase\":\"final_answer\",\"content\":[{\"type\":\"output_text\",\"text\":\"REI_OPERATIONAL_OK\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5,\"input_tokens_details\":{\"cached_tokens\":0},\"output_tokens_details\":{\"reasoning_tokens\":0}}}";
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
                        okhttp3.ResponseBody.create(
                            sse, okhttp3.MediaType.get("text/event-stream")))
                    .build();
              });
      var runtime =
          new ResponsesAcceptanceRuntime(
              root, List.of(fixture), ResponsesAcceptanceRuntime.Candidate.OPERATIONAL);
      try (var application = new ResponsesOperationalLiveAcceptance(runtime)) {
        assertThat(application.app.getBean(dev.mikoto2000.rei.core.chat.ChatExecutionService.class))
            .isNotNull();
        assertThat(application.tools.callbacks).isEmpty();
        assertThat(application.owner("preflight").projectRoot()).isEqualTo(root.toRealPath());
        assertThatCode(() -> application.owner("preflight")).doesNotThrowAnyException();
        assertThat(application.app.getBean(dev.mikoto2000.rei.application.run.RunRegistry.class))
            .isNotNull();
        assertThat(application.app.getBean(dev.mikoto2000.rei.application.run.RunService.class))
            .isNotNull();
        assertThat(runtime.budget.snapshot().calls()).isZero();
        assertThat(runtime.models.resolvedEndpoint(LlmFeature.CHAT).maxOutputTokens())
            .isEqualTo(2048);
        assertThat(runtime.models.resolvedEndpoint(LlmFeature.CHAT).capabilities().maxTools())
            .isEqualTo(36);
        assertThat(
                runtime.models.resolvedEndpoint(LlmFeature.CHAT).capabilities().supportedSettings())
            .containsExactly(LlmCapabilities.Setting.REASONING_EFFORT);
        application.smoke();
        assertThat(runtime.budget.snapshot().calls()).isEqualTo(1);
      }
    } finally {
      if (previous == null) System.clearProperty("rei.data-dir");
      else System.setProperty("rei.data-dir", previous);
      if (restart == null) System.clearProperty("spring.devtools.restart.enabled");
      else System.setProperty("spring.devtools.restart.enabled", restart);
    }
  }
}
