package dev.mikoto2000.rei.llm.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.llm.ChatConnectionService;
import dev.mikoto2000.rei.llm.ChatEndpointCatalog;
import dev.mikoto2000.rei.llm.LlmApiType;
import dev.mikoto2000.rei.llm.LlmAuthentication;
import dev.mikoto2000.rei.llm.LlmEndpointCapabilities;
import dev.mikoto2000.rei.llm.LlmProvider;
import dev.mikoto2000.rei.llm.LlmUsagePath;
import dev.mikoto2000.rei.llm.ReasoningReplayMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ChatEndpointCandidatesTest {
  @Test
  void shellCompletionUsesPublicMetadataWithoutReadingModelsOrCredentials() {
    var service = mock(ChatConnectionService.class);
    when(service.endpoints()).thenReturn(List.of(endpoint()));
    var candidates = new LlmCaptureCommand.EndpointCandidates(service);
    assertThat(candidates.types(null)).containsExactly("choices");
    var choices = candidates.choices(null);
    assertThat(choices).hasSize(1);
    assertThat(choices.getFirst().value()).isEqualTo("local");
    assertThat(choices.getFirst().description()).contains("[active]", "local-llm", "not-required");
    verify(service).endpoints();
    verifyNoMoreInteractions(service);
  }

  @Test
  void plainPicocliCompletionAlsoUsesRegisteredNames() {
    var service = mock(ChatConnectionService.class);
    when(service.endpoints()).thenReturn(List.of(endpoint()));
    assertThat(new LlmCaptureCommand.EndpointCandidates(service)).containsExactly("local");
    verify(service).endpoints();
    verifyNoMoreInteractions(service);
  }

  @Test
  void standaloneHelpAndMetadataConstructionDoNotRequireAnApplicationOrAuthentication() {
    assertThat(new LlmCaptureCommand.EndpointCandidates().choices(null)).isEmpty();
    var cli = new CommandLine(new LlmCaptureCommand());
    var use = cli.getSubcommands().get("chat").getSubcommands().get("use");
    assertThat(use.getCommandSpec().positionalParameters().getFirst().completionCandidates())
        .isInstanceOf(LlmCaptureCommand.EndpointCandidates.class);
  }

  private ChatConnectionService.Endpoint endpoint() {
    var profile = new ChatEndpointCatalog.Profile("local", "fixture-model", LlmProvider.OPENAI_COMPATIBLE,
        LlmApiType.CHAT_COMPLETIONS, LlmAuthentication.NONE, null, 1, LlmUsagePath.LOCAL_LLM,
        new LlmEndpointCapabilities(Map.of(), Map.of(), Map.of(), 10));
    return new ChatConnectionService.Endpoint(profile, true, ReasoningReplayMode.NONE,
        "not-required", null, Map.of(), Map.of(), List.of());
  }
}
