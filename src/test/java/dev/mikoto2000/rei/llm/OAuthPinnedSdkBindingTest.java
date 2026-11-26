package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.client.OpenAIClient;
import java.net.Proxy;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** OAuth can enter the SDK only through an accepted account and its fixed public resource. */
class OAuthPinnedSdkBindingTest {
  private record Owned(OpenAIClient client) implements AutoCloseable {
    @Override
    public void close() {
      client.close();
    }
  }

  private record Transport(okhttp3.OkHttpClient client) implements AutoCloseable {
    Transport() {
      this(
          new okhttp3.OkHttpClient.Builder()
              .proxy(Proxy.NO_PROXY)
              .followRedirects(false)
              .followSslRedirects(false)
              .retryOnConnectionFailure(false)
              .build());
    }

    @Override
    public void close() {
      client.connectionPool().evictAll();
      client.dispatcher().executorService().shutdown();
    }
  }

  static Stream<Arguments> invalidBindings() {
    return Stream.of("local", "provider", "account", "registration", "source", "api", "credential")
        .flatMap(
            invalid -> Stream.of(false, true).map(discovery -> Arguments.of(invalid, discovery)));
  }

  private RunLlmConnection pin(String invalid) {
    var endpoint =
        new ResolvedLlmEndpoint(
            "subscription",
            1,
            invalid.equals("api") ? LlmApiType.CHAT_COMPLETIONS : LlmApiType.RESPONSES,
            invalid.equals("local") ? "http://127.0.0.1:1/v1" : "https://api.openai.com/v1",
            invalid.equals("credential") ? "chat-endpoint:other" : "chat-endpoint:subscription",
            "fixture-model",
            new LlmCapabilities(ReasoningReplayMode.ENCRYPTED, null, Set.of()),
            null,
            null,
            Duration.ofSeconds(5));
    var capabilities =
        new EnumMap<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support>(
            LlmEndpointCapabilities.Capability.class);
    for (var capability : LlmEndpointCapabilities.Capability.values())
      capabilities.put(capability, LlmEndpointCapabilities.Support.SUPPORTED);
    return new RunLlmConnection(
        "subscription",
        !invalid.equals("source"),
        invalid.equals("provider") ? LlmProvider.OPENAI_COMPATIBLE : LlmProvider.CHATGPT_PLAN,
        LlmAuthentication.CHATGPT_OAUTH,
        invalid.equals("account") ? null : "personal",
        invalid.equals("registration") ? null : "registration-one",
        endpoint,
        capabilities,
        Map.of());
  }

  private OpenAIClient open(
      RunLlmConnection pin,
      boolean discovery,
      AtomicInteger credentials,
      okhttp3.OkHttpClient transport) {
    java.util.function.Supplier<String> credential =
        () -> {
          credentials.incrementAndGet();
          return "fixture-oauth-token";
        };
    return discovery
        ? PinnedOpenAiClients.openModels(pin, credential, transport, List.of(), () -> {})
        : PinnedOpenAiClients.open(pin, credential, transport, List.of(), () -> {});
  }

  @ParameterizedTest
  @MethodSource("invalidBindings")
  void rejectsAnInvalidOAuthBindingBeforeConstructingACredentialBearingClient(
      String invalid, boolean discovery) {
    var credentials = new AtomicInteger();
    try (var transport = new Transport()) {
      assertThatThrownBy(
              () -> {
                try (var client =
                    new Owned(open(pin(invalid), discovery, credentials, transport.client()))) {
                  assertThat(client.client()).isNotNull();
                }
              })
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(credentials).hasValue(0);
    }
  }

  @Test
  void legacyEndpointOnlyEntryCannotObtainOAuthWithoutAnAcceptedAccountBinding() {
    var credentials = new AtomicInteger();
    try (var transport = new Transport()) {
      assertThatThrownBy(
              () -> {
                try (var client =
                    new Owned(
                        PinnedOpenAiClients.open(
                            pin("").endpoint(),
                            LlmAuthentication.CHATGPT_OAUTH,
                            () -> {
                              credentials.incrementAndGet();
                              return "fixture-oauth-token";
                            },
                            transport.client(),
                            List.of()))) {
                  assertThat(client.client()).isNotNull();
                }
              })
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(credentials).hasValue(0);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptedOAuthClientKeepsCredentialAcquisitionLazy(boolean discovery) {
    var credentials = new AtomicInteger();
    try (var transport = new Transport();
        var client = new Owned(open(pin(""), discovery, credentials, transport.client()))) {
      assertThat(client.client()).isNotNull();
      assertThat(credentials).hasValue(0);
    }
  }
}
