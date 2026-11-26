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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ChatEndpointCommandTest {
  @Test
  void listAndShowExplainSelectionScopeAndPreserveCaptureHelp() {
    var fixture = new Fixture();
    assertThat(fixture.cli.execute("chat", "list")).isZero();
    assertThat(fixture.output.toString())
        .contains("local", "[active]", "local-model", "local-llm", "none", "not-required");
    fixture.output.getBuffer().setLength(0);
    assertThat(fixture.cli.execute("chat", "show")).isZero();
    assertThat(fixture.output.toString())
        .contains("capabilities", "generation", "unapplied", "process", "next Run", "restart");
    assertThat(fixture.cli.getSubcommands()).containsKeys("capture", "chat");
    verify(fixture.service, org.mockito.Mockito.times(2)).endpoints();
    verifyNoMoreInteractions(fixture.service);
  }

  @Test
  void directSelectionReportsConfigurationChangeWithoutClaimingConnectionSuccess() {
    var fixture = new Fixture();
    assertThat(fixture.cli.execute("chat", "use", "local")).isZero();
    verify(fixture.service).select("local");
    assertThat(fixture.output.toString())
        .contains("local", "local-model", "local-llm", "next Run", "not verified");
    verify(fixture.service).endpoints();
    verifyNoMoreInteractions(fixture.service);
  }

  @Test
  void interactiveCancellationNeverChangesSelection() {
    var fixture = new Fixture();
    fixture.command.setEndpointMenu(entries -> java.util.Optional.empty());
    assertThat(fixture.cli.execute("chat", "use")).isZero();
    assertThat(fixture.output.toString()).contains("cancel");
    verify(fixture.service).endpoints();
    verifyNoMoreInteractions(fixture.service);
  }

  @Test
  void interactiveConfirmationSelectsOnlyReturnedRegisteredId() {
    var fixture = new Fixture();
    fixture.command.setEndpointMenu(
        entries -> java.util.Optional.of(entries.getFirst().profile().id()));
    assertThat(fixture.cli.execute("chat", "use")).isZero();
    verify(fixture.service).select("local");
  }

  @Test
  void unknownNamesAndInvalidSyntaxDoNotChangeSelection() {
    var fixture = new Fixture();
    assertThat(fixture.cli.execute("chat", "use", "missing")).isEqualTo(2);
    assertThat(fixture.cli.execute("chat", "show", "extra")).isEqualTo(2);
    verify(fixture.service).endpoints();
    verifyNoMoreInteractions(fixture.service);
  }

  private static final class Fixture {
    final ChatConnectionService service = mock(ChatConnectionService.class);
    final LlmCaptureCommand command = new LlmCaptureCommand();
    final CommandLine cli = new CommandLine(command);
    final StringWriter output = new StringWriter();

    Fixture() {
      var profile =
          new ChatEndpointCatalog.Profile(
              "local",
              "local-model",
              LlmProvider.OPENAI_COMPATIBLE,
              LlmApiType.CHAT_COMPLETIONS,
              LlmAuthentication.NONE,
              null,
              1,
              LlmUsagePath.LOCAL_LLM,
              new LlmEndpointCapabilities(Map.of(), Map.of(), Map.of(), 10));
      when(service.endpoints())
          .thenReturn(
              List.of(
                  new ChatConnectionService.Endpoint(
                      profile,
                      true,
                      ReasoningReplayMode.NONE,
                      "not-required",
                      null,
                      Map.of(),
                      Map.of(),
                      List.of())));
      command.setChatConnections(service);
      cli.setOut(new PrintWriter(output, true));
      cli.setErr(new PrintWriter(output, true));
    }
  }
}
