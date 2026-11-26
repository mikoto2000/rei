package dev.mikoto2000.rei.llm;

import com.openai.client.OpenAIClient;
import java.util.List;
import java.util.function.Supplier;

/** Explicit SDK clients sharing a host-owned transport without ambient credential lookup. */
final class PinnedOpenAiClients {
  private PinnedOpenAiClients() {}

  static OpenAIClient openModels(
      RunLlmConnection connection,
      Supplier<String> credential,
      okhttp3.OkHttpClient transport,
      List<okhttp3.Interceptor> interceptors,
      Runnable verify) {
    requireOAuthBinding(connection);
    return open(
        connection.endpoint(),
        connection.authentication(),
        credential,
        transport,
        interceptors,
        connection.requestTimeout(),
        connection.transport(),
        java.util.Objects.requireNonNull(verify),
        true);
  }

  static OpenAIClient open(
      RunLlmConnection connection,
      Supplier<String> credential,
      okhttp3.OkHttpClient transport,
      List<okhttp3.Interceptor> interceptors,
      Runnable verify) {
    requireOAuthBinding(connection);
    return open(
        connection.endpoint(),
        connection.authentication(),
        credential,
        transport,
        interceptors,
        connection.requestTimeout(),
        connection.transport(),
        java.util.Objects.requireNonNull(verify),
        false);
  }

  static OpenAIClient open(
      ResolvedLlmEndpoint endpoint,
      LlmAuthentication authentication,
      Supplier<String> credential,
      okhttp3.OkHttpClient transport,
      List<okhttp3.Interceptor> interceptors) {
    if (authentication == LlmAuthentication.CHATGPT_OAUTH)
      throw new IllegalArgumentException("OAuth requires an accepted ChatGPT account binding");
    return open(
        endpoint,
        authentication,
        credential,
        transport,
        interceptors,
        endpoint.readTimeout(),
        java.util.Map.of(),
        () -> {},
        false);
  }

  private static void requireOAuthBinding(RunLlmConnection connection) {
    if (connection.authentication() != LlmAuthentication.CHATGPT_OAUTH) return;
    if (!connection.named()
        || connection.provider() != LlmProvider.CHATGPT_PLAN
        || connection.accountRef() == null
        || !connection.accountRef().matches("[A-Za-z0-9_-]{1,64}")
        || connection.accountRegistration() == null
        || connection.accountRegistration().isBlank()
        || connection.accountRegistration().length() > 128
        || connection.accountRegistration().codePoints().anyMatch(Character::isISOControl)
        || connection.endpoint().apiType() != LlmApiType.RESPONSES
        || !"https://api.openai.com/v1".equals(connection.endpoint().baseUrl())
        || !connection.profileId().equals(connection.endpoint().endpointId())
        || !("chat-endpoint:" + connection.profileId())
            .equals(connection.endpoint().credentialRef()))
      throw new IllegalArgumentException("OAuth requires an accepted ChatGPT account binding");
  }

  private static OpenAIClient open(
      ResolvedLlmEndpoint endpoint,
      LlmAuthentication authentication,
      Supplier<String> credential,
      okhttp3.OkHttpClient transport,
      List<okhttp3.Interceptor> interceptors,
      java.time.Duration deadline,
      java.util.Map<String, String> attributes,
      Runnable verify,
      boolean modelDiscovery) {
    if (transport.followRedirects()
        || transport.followSslRedirects()
        || transport.retryOnConnectionFailure()
        || transport.cookieJar() != okhttp3.CookieJar.NO_COOKIES
        || transport.authenticator() != okhttp3.Authenticator.NONE
        || transport.proxyAuthenticator() != okhttp3.Authenticator.NONE
        || !transport.networkInterceptors().isEmpty()
        || !transport.interceptors().isEmpty())
      throw new IllegalArgumentException(
          "Pinned transport must disable implicit forwarding and authentication");
    var builder =
        com.openai.client.okhttp.OpenAIOkHttpClient.builder()
            .okHttpClient(transport)
            .baseUrl(endpoint.baseUrl())
            .timeout(
                com.openai.core.Timeout.builder()
                    .connect(connectTimeout(attributes))
                    .request(deadline)
                    .read(endpoint.readTimeout())
                    .build())
            .maxRetries(0);
    if (attributes.containsKey("organization_id"))
      builder.organization(attributes.get("organization_id"));
    if (attributes.containsKey("project_id")) builder.project(attributes.get("project_id"));
    if (attributes.containsKey("api_beta"))
      builder.putHeader("OpenAI-Beta", attributes.get("api_beta"));
    switch (authentication) {
      case NONE ->
          builder
              .apiKey("")
              .removeHeaders("Authorization")
              .removeHeaders("api-key")
              .removeHeaders("x-api-key")
              .removeHeaders("Cookie");
      case API_KEY, CHATGPT_OAUTH ->
          builder.credential(
              com.openai.credential.BearerTokenCredential.create(
                  () -> currentCredential(credential)));
      default ->
          throw new IllegalArgumentException("Pinned authentication must be resolved explicitly");
    }
    builder.addInterceptor(
        chain -> boundedTimeouts(chain, endpoint, attributes).proceed(chain.request()));
    for (var interceptor : interceptors) builder.addInterceptor(interceptor);
    var expected =
        okhttp3.HttpUrl.get(
            endpoint.baseUrl().replaceAll("/+$", "")
                + (modelDiscovery
                    ? "/models"
                    : endpoint.apiType() == LlmApiType.RESPONSES
                        ? "/responses"
                        : "/chat/completions"));
    builder.addInterceptor(chain -> {
      var request = chain.request();
      if (!request.method().equals(modelDiscovery ? "GET" : "POST")
          || modelDiscovery && request.body() != null
          || !request.url().equals(expected)
          || request.header("Host") != null)
        throw new java.io.IOException(
            modelDiscovery
                ? "Pinned model discovery destination changed"
                : "Pinned inference destination changed");
      if (request.header("api-key") != null
          || request.header("x-api-key") != null
          || request.header("Cookie") != null
          || authentication == LlmAuthentication.NONE && request.header("Authorization") != null)
        throw new java.io.IOException("Pinned inference credential contract changed");
      requireAttribute(request, "OpenAI-Organization", attributes.get("organization_id"));
      requireAttribute(request, "OpenAI-Project", attributes.get("project_id"));
      requireAttribute(request, "OpenAI-Beta", attributes.get("api_beta"));
      verify.run();
      var body = request.body();
      if (body != null) {
        body =
            PinnedWireLimits.request(
                body,
                RunTransportSettings.byteLimit(
                    attributes,
                    "request_limit_bytes",
                    RunTransportSettings.DEFAULT_REQUEST_LIMIT_BYTES));
        request = request.newBuilder().method(request.method(), nonReplayable(body)).build();
      }
      return PinnedWireLimits.response(
          boundedTimeouts(chain, endpoint, attributes).proceed(request),
          RunTransportSettings.byteLimit(
              attributes,
              "response_limit_bytes",
              RunTransportSettings.DEFAULT_RESPONSE_LIMIT_BYTES));
    });
    return builder.build();
  }

  private static java.time.Duration connectTimeout(java.util.Map<String, String> attributes) {
    var configured = attributes.get("connect_timeout");
    return configured == null
        ? java.time.Duration.ofMinutes(1)
        : RunTransportSettings.timeout(configured);
  }

  /** SDK per-request timeout options must not expand the accepted connection/read bounds. */
  private static okhttp3.Interceptor.Chain boundedTimeouts(
      okhttp3.Interceptor.Chain chain,
      ResolvedLlmEndpoint endpoint,
      java.util.Map<String, String> attributes) {
    int connect =
        ChatStreamTimeoutInterceptor.validatedReadTimeoutMillis(connectTimeout(attributes));
    int read = ChatStreamTimeoutInterceptor.validatedReadTimeoutMillis(endpoint.readTimeout());
    if (chain.connectTimeoutMillis() > 0) connect = Math.min(connect, chain.connectTimeoutMillis());
    if (chain.readTimeoutMillis() > 0) read = Math.min(read, chain.readTimeoutMillis());
    return chain
        .withConnectTimeout(connect, java.util.concurrent.TimeUnit.MILLISECONDS)
        .withReadTimeout(read, java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  private static void requireAttribute(okhttp3.Request request, String header, String expected)
      throws java.io.IOException {
    if (!request.headers(header).equals(expected == null ? List.of() : List.of(expected)))
      throw new java.io.IOException("Pinned inference account attributes changed");
  }

  private static String currentCredential(Supplier<String> credential) {
    var value = credential.get();
    if (value == null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalStateException("Accepted inference credentials are unavailable");
    return value;
  }

  /** Keep OkHttp's HTTP follow-up handling from repeating an accepted inference. */
  static okhttp3.RequestBody nonReplayable(okhttp3.RequestBody body) {
    return new okhttp3.RequestBody() {
      @Override
      public okhttp3.MediaType contentType() {
        return body.contentType();
      }

      @Override
      public long contentLength() throws java.io.IOException {
        return body.contentLength();
      }

      @Override
      public boolean isOneShot() {
        return true;
      }

      @Override
      public boolean isDuplex() {
        return body.isDuplex();
      }

      @Override
      public void writeTo(okio.BufferedSink sink) throws java.io.IOException {
        body.writeTo(sink);
      }
    };
  }
}
