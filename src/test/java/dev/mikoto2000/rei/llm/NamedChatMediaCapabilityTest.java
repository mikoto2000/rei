package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;

/** Owned loopback SDK requests; input capabilities are independent from authentication. */
class NamedChatMediaCapabilityTest {
  @ParameterizedTest
  @CsvSource({
    "image/png,UNKNOWN,VISION",
    "image/png,UNSUPPORTED,VISION",
    "application/pdf,UNKNOWN,FILE_INPUT",
    "application/pdf,UNSUPPORTED,FILE_INPUT"
  })
  void unconfirmedOrUnsupportedMediaNeverReachesTheSelectedChatEndpoint(
      String mime,
      LlmEndpointCapabilities.Support support,
      LlmEndpointCapabilities.Capability required)
      throws Exception {
    try (var fixture = new PinnedChatModelsTest.Fixture();
        var models =
            new PinnedChatModels(
                pin -> {
                  throw new AssertionError("No-auth media read a credential");
                },
                pin -> {},
                null,
                2)) {
      var base = fixture.pin("media", "fixture-model", LlmAuthentication.NONE);
      var connection =
          new RunLlmConnection(
              base.profileId(),
              true,
              base.provider(),
              base.authentication(),
              null,
              null,
              base.endpoint(),
              Map.of(required, support),
              base.generation());
      var input =
          UserMessage.builder()
              .text("synthetic media input")
              .media(
                  List.of(
                      Media.builder()
                          .mimeType(MimeType.valueOf(mime))
                          .name(mime.equals("application/pdf") ? "fixture.pdf" : "fixture.png")
                          .data(new byte[] {1, 2, 3})
                          .build()))
              .build();
      var prompt = new Prompt(input, RunNativeOptions.options(connection));
      assertThatThrownBy(() -> models.model(connection).call(prompt))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(required.name());
      assertThat(fixture.bodies).isEmpty();
      assertThat(fixture.headers).isEmpty();
    }
  }
}
