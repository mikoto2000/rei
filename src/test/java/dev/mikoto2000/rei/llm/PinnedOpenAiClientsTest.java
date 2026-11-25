package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PinnedOpenAiClientsTest {
  private record Owned(OpenAIClient client) implements AutoCloseable {
    public void close() {
      if (client != null) client.close();
    }
  }

  @Test
  void unauthenticatedProfileNeverUsesAnotherProfilesCredentialOrHeader() throws Exception {
    try (var fixture = new Fixture()) {
      try (var keyed =
          new Owned(
              PinnedOpenAiClients.open(
                  fixture.endpoint(),
                  LlmAuthentication.API_KEY,
                  () -> "PRIVATE_OTHER_PROFILE",
                  fixture.transport,
                  List.of()))) {
        assertThat(keyed.client()).isNotNull();
        request(keyed.client());
      }
      var lookups = new AtomicInteger();
      try (var none =
          new Owned(
              PinnedOpenAiClients.open(
                  fixture.endpoint(),
                  LlmAuthentication.NONE,
                  () -> {
                    lookups.incrementAndGet();
                    return "PRIVATE_OTHER_PROFILE";
                  },
                  fixture.transport,
                  List.of()))) {
        assertThat(none.client()).isNotNull();
        assertThat(request(none.client())).isEqualTo("resp_fixture");
      }
      assertThat(lookups).hasValue(0);
      assertThat(fixture.authorizations).containsExactly("Bearer PRIVATE_OTHER_PROFILE", "<none>");
      assertThat(fixture.alternativeKeys).containsOnly("<none>");
    }
  }

  @Test
  void oneCachedClientReadsTheCurrentCredentialForEachRequest() throws Exception {
    try (var fixture = new Fixture()) {
      var current = new AtomicReference<>("PRIVATE_FIRST");
      try (var client =
          new Owned(
              PinnedOpenAiClients.open(
                  fixture.endpoint(),
                  LlmAuthentication.API_KEY,
                  current::get,
                  fixture.transport,
                  List.of()))) {
        assertThat(client.client()).isNotNull();
        request(client.client());
        current.set("PRIVATE_REFRESHED");
        request(client.client());
      }
      assertThat(fixture.authorizations)
          .containsExactly("Bearer PRIVATE_FIRST", "Bearer PRIVATE_REFRESHED");
    }
  }

  @Test
  void redirectCannotResendTheConversationToAnotherReceiver() throws Exception {
    try (var original = new Fixture();
        var foreign = new Fixture()) {
      original.redirect.set(foreign.baseUrl() + "/responses");
      try (var client =
          new Owned(
              PinnedOpenAiClients.open(
                  original.endpoint(),
                  LlmAuthentication.API_KEY,
                  () -> "PRIVATE_FIRST",
                  original.transport,
                  List.of()))) {
        assertThat(client.client()).isNotNull();
        assertThatThrownBy(() -> request(client.client())).isInstanceOf(RuntimeException.class);
      }
      assertThat(original.requests).hasValue(1);
      assertThat(foreign.requests).hasValue(0);
    }
  }

  @Test
  void aManagedInterceptorCannotRewriteThePinnedDestination() throws Exception {
    try (var original = new Fixture();
        var foreign = new Fixture()) {
      okhttp3.Interceptor changed =
          chain ->
              chain.proceed(
                  chain.request().newBuilder().url(foreign.baseUrl() + "/responses").build());
      try (var client =
          new Owned(
              PinnedOpenAiClients.open(
                  original.endpoint(),
                  LlmAuthentication.NONE,
                  () -> "PRIVATE_UNUSED",
                  original.transport,
                  List.of(changed)))) {
        assertThat(client.client()).isNotNull();
        assertThatThrownBy(() -> request(client.client())).isInstanceOf(RuntimeException.class);
      }
      assertThat(original.requests).hasValue(0);
      assertThat(foreign.requests).hasValue(0);
    }
  }

  @Test
  void unauthenticatedProfileRejectsAnInjectedCredentialBeforeHttp() throws Exception {
    try (var fixture = new Fixture()) {
      okhttp3.Interceptor changed =
          chain ->
              chain.proceed(
                  chain
                      .request()
                      .newBuilder()
                      .header("Authorization", "Bearer PRIVATE_INJECTED")
                      .build());
      try (var client =
          new Owned(
              PinnedOpenAiClients.open(
                  fixture.endpoint(),
                  LlmAuthentication.NONE,
                  () -> "PRIVATE_UNUSED",
                  fixture.transport,
                  List.of(changed)))) {
        assertThat(client.client()).isNotNull();
        assertThatThrownBy(() -> request(client.client())).isInstanceOf(RuntimeException.class);
      }
      assertThat(fixture.requests).hasValue(0);
    }
  }

  @Test
  void acceptedDeadlineReadTimeoutAndAccountAttributesReachTheSdkTransport() throws Exception {
    try (var fixture = new Fixture()) {
      var pin =
          pin(
              fixture.endpoint(),
              java.util.Map.of(
                  "request_timeout",
                  "PT37S",
                  "organization_id",
                  "org-fixture",
                  "project_id",
                  "proj-fixture",
                  "api_beta",
                  "fixture=v1"));
      var reads = new AtomicInteger();
      var deadline = new java.util.concurrent.atomic.AtomicLong();
      var organization = new AtomicReference<String>();
      var project = new AtomicReference<String>();
      var beta = new AtomicReference<String>();
      okhttp3.Interceptor inspect =
          chain -> {
            reads.set(chain.readTimeoutMillis());
            deadline.set(chain.call().timeout().timeoutNanos());
            organization.set(chain.request().header("OpenAI-Organization"));
            project.set(chain.request().header("OpenAI-Project"));
            beta.set(chain.request().header("OpenAI-Beta"));
            return chain.proceed(chain.request());
          };
      try (var client =
          new Owned(
              PinnedOpenAiClients.open(
                  pin, () -> null, fixture.transport, List.of(inspect), () -> {}))) {
        assertThat(request(client.client())).isEqualTo("resp_fixture");
      }
      assertThat(reads).hasValue(5000);
      assertThat(deadline).hasValue(Duration.ofSeconds(37).toNanos());
      assertThat(organization).hasValue("org-fixture");
      assertThat(project).hasValue("proj-fixture");
      assertThat(beta).hasValue("fixture=v1");
    }
  }

  @Test
  void anInterceptorCannotChangeThePinnedAccountAudience() throws Exception {
    try (var fixture = new Fixture()) {
      var pin = pin(fixture.endpoint(), java.util.Map.of("organization_id", "org-fixture"));
      okhttp3.Interceptor changed =
          chain ->
              chain.proceed(
                  chain
                      .request()
                      .newBuilder()
                      .header("OpenAI-Organization", "foreign-org")
                      .build());
      try (var client =
          new Owned(
              PinnedOpenAiClients.open(
                  pin, () -> null, fixture.transport, List.of(changed), () -> {}))) {
        assertThatThrownBy(() -> request(client.client())).isInstanceOf(RuntimeException.class);
      }
      assertThat(fixture.requests).hasValue(0);
    }
  }

  @Test
  void noAuthBindingIsCheckedAgainImmediatelyBeforeHttp() throws Exception {
    try (var fixture = new Fixture()) {
      var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
      okhttp3.Interceptor changed =
          chain -> {
            valid.set(false);
            return chain.proceed(chain.request());
          };
      try (var client =
          new Owned(
              PinnedOpenAiClients.open(
                  pin(fixture.endpoint(), java.util.Map.of()),
                  () -> {
                    throw new AssertionError("No-auth read credentials");
                  },
                  fixture.transport,
                  List.of(changed),
                  () -> {
                    if (!valid.get()) throw new IllegalStateException("Binding removed");
                  }))) {
        assertThatThrownBy(() -> request(client.client())).isInstanceOf(RuntimeException.class);
      }
      assertThat(fixture.requests).hasValue(0);
    }
  }

  @Test
  void equivalentUrlCanonicalizationDoesNotRejectTheAcceptedReceiver() throws Exception {
    try (var fixture = new Fixture()) {
      var old = fixture.endpoint();
      var canonical =
          new ResolvedLlmEndpoint(
              old.endpointId(),
              old.generation(),
              old.apiType(),
              old.baseUrl().replace("http:", "HTTP:"),
              old.credentialRef(),
              old.model(),
              old.capabilities(),
              old.maxOutputTokens(),
              old.temperature(),
              old.readTimeout());
      try (var client =
          new Owned(
              PinnedOpenAiClients.open(
                  pin(canonical, java.util.Map.of()),
                  () -> null,
                  fixture.transport,
                  List.of(),
                  () -> {}))) {
        assertThat(request(client.client())).isEqualTo("resp_fixture");
      }
    }
  }

  private RunLlmConnection pin(
      ResolvedLlmEndpoint endpoint, java.util.Map<String, String> attributes) {
    return new RunLlmConnection(
        "fixture",
        true,
        LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE,
        null,
        null,
        endpoint,
        java.util.Map.of(),
        java.util.Map.of(),
        attributes);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(LlmApiType.class)
  void aRetryAfterZeroServiceFailureCannotResendAnInference(LlmApiType api) throws Exception {
    try (var fixture = new Fixture()) {
      fixture.unavailableOnce.set(true);
      try (var client = new Owned(PinnedOpenAiClients.open(fixture.endpoint(api), LlmAuthentication.NONE,
          () -> { throw new AssertionError("No-auth read credentials"); }, fixture.transport, List.of()))) {
        assertThatThrownBy(() -> {
          if (api == LlmApiType.RESPONSES) request(client.client());
          else client.client().chat().completions().create(
              com.openai.models.chat.completions.ChatCompletionCreateParams.builder()
                  .model("fixture-model").addUserMessage("fixture").build()).id();
        }).isInstanceOf(RuntimeException.class);
      }
      assertThat(fixture.requests).hasValue(1);
    }
  }

  private String request(OpenAIClient client) {
    return client
        .responses()
        .create(
            ResponseCreateParams.builder()
                .model("fixture-model")
                .store(false)
                .inputOfResponse(
                    List.of(
                        ResponseInputItem.ofEasyInputMessage(
                            EasyInputMessage.builder()
                                .role(EasyInputMessage.Role.USER)
                                .content("fixture")
                                .build())))
                .build())
        .id();
  }

  private static final class Fixture implements AutoCloseable {
    final com.sun.net.httpserver.HttpServer server;
    final okhttp3.OkHttpClient transport =
        new okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();
    final AtomicInteger requests = new AtomicInteger();
    final AtomicReference<String> redirect = new AtomicReference<>();
    final java.util.concurrent.atomic.AtomicBoolean unavailableOnce = new java.util.concurrent.atomic.AtomicBoolean();
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    final List<String> alternativeKeys = new CopyOnWriteArrayList<>();

    Fixture() throws Exception {
      server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1",
          exchange -> {
            requests.incrementAndGet();
            authorizations.add(
                java.util.Objects.toString(
                    exchange.getRequestHeaders().getFirst("Authorization"), "<none>"));
            alternativeKeys.add(
                java.util.Objects.toString(
                    exchange.getRequestHeaders().getFirst("api-key"), "<none>"));
            alternativeKeys.add(
                java.util.Objects.toString(
                    exchange.getRequestHeaders().getFirst("x-api-key"), "<none>"));
            exchange.getRequestBody().readAllBytes();
            if (unavailableOnce.compareAndSet(true, false)) {
              exchange.getResponseHeaders().set("Retry-After", "0");
              exchange.sendResponseHeaders(503, -1);
              exchange.close();
              return;
            }
            if (redirect.get() != null) {
              exchange.getResponseHeaders().set("Location", redirect.get());
              exchange.sendResponseHeaders(307, -1);
              exchange.close();
              return;
            }
            byte[] body =
                ("{\"id\":\"resp_fixture\",\"object\":\"response\",\"created_at\":0,"
                        + "\"status\":\"completed\",\"model\":\"fixture-model\",\"output\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
              output.write(body);
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    ResolvedLlmEndpoint endpoint() {
      return endpoint(LlmApiType.RESPONSES);
    }

    ResolvedLlmEndpoint endpoint(LlmApiType api) {
      return new ResolvedLlmEndpoint(
          "fixture",
          1,
          api,
          baseUrl(),
          "fixture",
          "fixture-model",
          new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()),
          null,
          null,
          Duration.ofSeconds(5));
    }

    public void close() {
      server.stop(0);
      transport.connectionPool().evictAll();
      transport.dispatcher().executorService().shutdownNow();
    }
  }
}
