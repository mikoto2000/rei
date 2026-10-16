package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Explicit live test sends only synthetic speech/context, never microphone recordings or user
 * history.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "REI_VOICE_CORRECTION_LIVE", matches = "true")
class VoiceCorrectionLiveTest {
  @Test
  void realConfiguredLlmCorrectsSyntheticContextualHomophone() throws Exception {
    var options =
        OpenAiChatOptions.builder()
            .baseUrl(System.getenv("REI_VOICE_CORRECTION_LIVE_URL"))
            .apiKey(Objects.toString(System.getenv("REI_VOICE_CORRECTION_LIVE_KEY"), "dummy-key"))
            .model(System.getenv("REI_VOICE_CORRECTION_LIVE_MODEL"))
            .timeout(java.time.Duration.ofSeconds(5))
            .maxRetries(0)
            .build();
    var model = OpenAiChatModel.builder().options(options).build();
    var models = new LlmModelProvider(model, new LlmProperties());
    var clients = mock(LlmChatClientProvider.class);
    when(clients.chatClient(LlmFeature.VOICE_CORRECTION))
        .thenReturn(ChatClient.builder(models.voiceCorrectionChatModel()).build());
    var p = new VoiceCorrectionProperties();
    p.setMaxTotalTokens(0);
    var output =
        new VoiceCorrectionLlm(clients, models, p)
            .call(
                "交配の話",
                new VoiceCorrectionContext.Snapshot(
                    UUID.randomUUID().toString(),
                    "synthetic",
                    List.of("微分と勾配降下法について話しています。"),
                    List.of()));
    assertThat(new VoiceCorrectionValidator().validate("交配の話", output, 500).text())
        .isEqualTo("勾配の話");
  }
}
