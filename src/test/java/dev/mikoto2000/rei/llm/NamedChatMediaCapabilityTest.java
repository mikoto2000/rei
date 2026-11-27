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
    "image/png,relative/PRIVATE",
    "image/png,file:///PRIVATE",
    "image/png,javascript:PRIVATE",
    "image/png,https://user:PRIVATE@example.invalid/image.png",
    "image/png,https://example.invalid/PRIVATE#fragment",
    "image/png,'data:text/plain;base64,AQID'",
    "image/png,'data:image/png;base64,%%%'",
    "application/pdf,http://example.invalid/PRIVATE",
    "text/plain,INLINE",
    "application/json,INLINE",
    "audio/mpeg,INLINE",
    "video/mp4,INLINE",
    "image/tiff,INLINE"
  })
  void unsafeMediaAndUnsupportedFileShapesNeverBecomeOrdinaryText(String mime, String data)
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
              Map.of(
                  LlmEndpointCapabilities.Capability.VISION,
                  LlmEndpointCapabilities.Support.SUPPORTED,
                  LlmEndpointCapabilities.Capability.FILE_INPUT,
                  LlmEndpointCapabilities.Support.SUPPORTED),
              base.generation());
      var mediaBuilder = Media.builder().mimeType(MimeType.valueOf(mime)).name("fixture.bin");
      if (data.equals("INLINE")) mediaBuilder.data(new byte[] {1, 2, 3});
      else mediaBuilder.data(data);
      var media = mediaBuilder.build();
      var input = UserMessage.builder().text("fixture").media(List.of(media)).build();
      assertThatThrownBy(() ->
              models
                  .model(connection)
                  .call(new Prompt(input, RunNativeOptions.options(connection))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining("PRIVATE");
      assertThat(fixture.bodies).isEmpty();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "image/png,NONE",
    "image/png,API_KEY",
    "application/pdf,NONE",
    "application/pdf,API_KEY"
  })
  void confirmedChatMediaUsesItsTypedWireShape(String mime, LlmAuthentication authentication)
      throws Exception {
    try (var fixture = new PinnedChatModelsTest.Fixture();
        var models =
            new PinnedChatModels(
                pin -> {
                  if (pin.authentication() == LlmAuthentication.NONE)
                    throw new AssertionError("No-auth media read a credential");
                  return "fixture-media-key";
                },
                pin -> {},
                null,
                2)) {
      var base = fixture.pin("media", "fixture-model", authentication);
      var required =
          mime.startsWith("image/")
              ? LlmEndpointCapabilities.Capability.VISION
              : LlmEndpointCapabilities.Capability.FILE_INPUT;
      var connection =
          new RunLlmConnection(
              base.profileId(),
              true,
              base.provider(),
              authentication,
              null,
              null,
              base.endpoint(),
              Map.of(required, LlmEndpointCapabilities.Support.SUPPORTED),
              base.generation());
      models.model(connection).call(new Prompt(input(mime), RunNativeOptions.options(connection)));
      assertThat(fixture.bodies).hasSize(1);
      assertThat(fixture.headers)
          .containsExactly(
              authentication == LlmAuthentication.NONE ? "<none>" : "Bearer fixture-media-key");
      assertThat(fixture.bodies.getFirst().path("messages").toString())
          .contains(
              mime.startsWith("image/") ? "image_url" : "file_data",
              "data:" + mime + ";base64,AQID")
          .doesNotContain("fixture-media-key");
      if (!mime.startsWith("image/"))
        assertThat(fixture.bodies.getFirst().path("messages").toString()).contains("fixture.pdf");
    }
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> responsesInputs() {
    return java.util.stream.Stream.of(false, true)
        .flatMap(plan ->
            java.util.stream.Stream.of("image/png", "application/pdf")
                .flatMap(mime ->
                    java.util.Arrays.stream(LlmEndpointCapabilities.Support.values())
                        .map(support ->
                            org.junit.jupiter.params.provider.Arguments.of(plan, mime, support))));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("responsesInputs")
  void responsesAndPlanUseTheSameIndependentInputCapabilities(
      boolean plan, String mime, LlmEndpointCapabilities.Support support) throws Exception {
    var required =
        mime.startsWith("image/")
            ? LlmEndpointCapabilities.Capability.VISION
            : LlmEndpointCapabilities.Capability.FILE_INPUT;
    try (var fixture = new ResponsesLoopFixture(plan, Map.of(required, support))) {
      fixture.replies = turn -> fixture.reply;
      var options = fixture.options();
      var model = ResponsesAdapter.createForRun(fixture.pin, options, fixture.client);
      var prompt = new Prompt(input(mime), options);
      if (support != LlmEndpointCapabilities.Support.SUPPORTED) {
        assertThatThrownBy(() -> model.stream(prompt).blockLast(java.time.Duration.ofSeconds(5)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(required.name());
        assertThat(fixture.requests).isEmpty();
        return;
      }
      model.stream(prompt).blockLast(java.time.Duration.ofSeconds(5));
      assertThat(fixture.requests).hasSize(1);
      assertThat(fixture.requests.getFirst().path("input").toString())
          .contains(
              mime.startsWith("image/") ? "input_image" : "input_file",
              "data:" + mime + ";base64,AQID");
      if (!mime.startsWith("image/"))
        assertThat(fixture.requests.getFirst().path("input").toString()).contains("fixture.pdf");
    }
  }

  private static UserMessage input(String mime) {
    return UserMessage.builder()
        .text("synthetic media input")
        .media(
            List.of(
                Media.builder()
                    .mimeType(MimeType.valueOf(mime))
                    .name(mime.equals("application/pdf") ? "fixture.pdf" : "fixture.png")
                    .data(new byte[] {1, 2, 3})
                    .build()))
        .build();
  }

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
