package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionDetails;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ChatLegacyEndpointDescriptionTest {
  @TempDir Path root;

  @Test
  void legacyDescriptionShowsActualCapturedOptionsAndResolvedAuthenticationWithoutInference()
      throws Exception {
    var nativeModel = mock(ChatModel.class);
    when(nativeModel.getOptions())
        .thenReturn(
            OpenAiChatOptions.builder()
                .model("native-default")
                .topP(0.7)
                .maxCompletionTokens(223)
                .apiKey("PRIVATE_NATIVE_KEY")
                .maxRetries(0)
                .build());
    try (var service = service(nativeModel)) {
      var description = service.endpoints().getFirst();
      var owner =
          new AgentRunContext(
              "owned-metadata-run",
              "session",
              root,
              "project",
              AgentRunContext.RequestSource.SHELL,
              AgentRunContext.Mode.CONVERSATION,
              false);
      assertThat(description.generationSettings())
          .isEqualTo(service.accept(owner).generation())
          .containsEntry("top_p", 0.7);
      assertThat(description.profile().model()).isEqualTo("legacy-picked");
      assertThat(description.profile().authentication()).isEqualTo(LlmAuthentication.API_KEY);
      assertThat(description.authenticationState()).isEqualTo("configured");
      assertThat(new ObjectMapper().writeValueAsString(description))
          .doesNotContain("PRIVATE_NATIVE_KEY", "PRIVATE_GLOBAL_KEY", "global.invalid");
      verify(nativeModel, never()).call(any(Prompt.class));
      verify(nativeModel, never()).stream(any(Prompt.class));
    }
  }

  @Test
  void invalidLegacyOptionsRemainVisibleAsUnavailableWithoutLeakingNativeErrors() throws Exception {
    var nativeModel = mock(ChatModel.class);
    when(nativeModel.getOptions()).thenThrow(new IllegalStateException("PRIVATE_NATIVE_ERROR"));
    try (var service = service(nativeModel)) {
      var description = service.endpoints().getFirst();
      assertThat(description.unavailableReason()).isNotBlank();
      assertThat(new ObjectMapper().writeValueAsString(description))
          .doesNotContain("PRIVATE_NATIVE_ERROR");
    }
  }

  private ChatConnectionService service(ChatModel nativeModel) {
    var properties = new LlmProperties();
    var holder = new ModelHolderService("legacy-picked");
    var beans = new StaticListableBeanFactory();
    var common = new OpenAiCommonProperties();
    common.setBaseUrl("http://global.invalid");
    common.setApiKey("PRIVATE_GLOBAL_KEY");
    beans.addBean("common", common);
    beans.addBean("chat", new OpenAiChatProperties());
    return new ChatConnectionService(
        new ChatEndpointCatalog(properties),
        properties,
        new LlmModelProvider(nativeModel, properties),
        holder,
        beans.getBeanProvider(OpenAiConnectionDetails.class),
        beans.getBeanProvider(OpenAiCommonProperties.class),
        beans.getBeanProvider(OpenAiChatProperties.class),
        beans.getBeanProvider(dev.mikoto2000.rei.llm.capture.CaptureStore.class));
  }
}
