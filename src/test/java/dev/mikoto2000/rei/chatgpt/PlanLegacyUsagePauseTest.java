package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Direct request errors publish only confirmed app limits to the shared account admission. */
class PlanLegacyUsagePauseTest {
  @TempDir Path root;

  static Stream<Arguments> failures() {
    return Stream.of(
            "subscription_sharing_usage_limit_exceeded",
            "subscription_sharing_usage_unavailable",
            "rate_limit_exceeded")
        .flatMap(code ->
            Stream.of("http", "failed", "delta-error").map(shape -> Arguments.of(code, shape)));
  }

  @ParameterizedTest
  @MethodSource("failures")
  void directHttpAndSseShareOnlyConfirmedAppUsagePauses(String code, String shape) {
    var posts = new AtomicInteger();
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          if (method.equals("GET")) return PlanOAuthTest.reply(200, PlanClientTest.MODELS);
          posts.incrementAndGet();
          if (shape.equals("http"))
            return PlanOAuthTest.reply(
                code.endsWith("unavailable") ? 503 : 429,
                "{\"error\":{\"code\":\"" + code + "\",\"message\":\"PRIVATE body\"}}");
          var data =
              shape.equals("failed")
                  ? PlanSseAssemblerTest.event(
                      "response.failed", "{\"error\":{\"code\":\"" + code + "\"}}")
                  : "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"partial\"}\n\n"
                      + "event: error\ndata: {\"type\":\"error\",\"code\":\""
                      + code
                      + "\",\"message\":\"PRIVATE body\"}\n\n";
          return new PlanTransport.Response(
              200, PlanSseAssemblerTest.stream(data), "fixture", "text/event-stream");
        };
    var saved = ProtectedCredentialStoreTest.tokens("access-secret", "refresh-secret");
    var store = new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
    store.locked(tx -> {
      tx.save("personal", saved);
      return null;
    });
    var accounts =
        new PlanClient(
            new PlanOAuth(store, transport, (id, app, nonce) -> null, Clock.systemUTC()),
            transport,
            Clock.systemUTC());
    java.util.function.Supplier<PlanSseAssembler.Result> infer =
        () ->
            accounts.inferHistory(
                "personal",
                "allowed",
                "fixture instructions",
                java.util.List.of(new PlanClient.Input("user", "fixture")),
                Duration.ofSeconds(2),
                262144,
                1048576,
                () -> false,
                () -> {});
    if (shape.equals("http")) assertThrows(ChatGptException.class, infer::get);
    else assertThat(infer.get().state()).isEqualTo(PlanSseAssembler.State.FAILED);
    boolean confirmed = code.equals("subscription_sharing_usage_limit_exceeded");
    assertThat(accounts.requestsPaused("personal", saved.registrationId())).isEqualTo(confirmed);
    if (confirmed)
      assertEquals(
          ChatGptException.Code.USAGE_LIMIT,
          assertThrows(
                  ChatGptException.class,
                  () -> accounts.begin("personal", saved.registrationId(), "other-model"))
              .code());
    else
      try (var request = accounts.begin("personal", saved.registrationId(), "allowed")) {
        assertThat(request).isNotNull();
      }
    TokenSet current = store.locked(tx -> tx.load("personal"));
    assertThat(current).isEqualTo(saved);
    assertThat(posts).hasValue(1);
  }
}
