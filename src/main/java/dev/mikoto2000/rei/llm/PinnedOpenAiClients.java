package dev.mikoto2000.rei.llm;

import com.openai.client.OpenAIClient;
import java.util.List;
import java.util.function.Supplier;

/** Explicit SDK clients sharing a host-owned transport without ambient credential lookup. */
final class PinnedOpenAiClients {
  private PinnedOpenAiClients() {}

  static OpenAIClient open(
      RunLlmConnection connection,
      Supplier<String> credential,
      okhttp3.OkHttpClient transport,
      List<okhttp3.Interceptor> interceptors,
      Runnable verify) {
    return open(
        connection.endpoint(),
        connection.authentication(),
        credential,
        transport,
        interceptors,
        connection.requestTimeout(),
        connection.transport(),
        java.util.Objects.requireNonNull(verify));
  }

  static OpenAIClient open(
      ResolvedLlmEndpoint endpoint,
      LlmAuthentication authentication,
      Supplier<String> credential,
      okhttp3.OkHttpClient transport,
      List<okhttp3.Interceptor> interceptors) {
    return open(
        endpoint,
        authentication,
        credential,
        transport,
        interceptors,
        endpoint.readTimeout(),
        java.util.Map.of(),
        () -> {});
  }

  private static OpenAIClient open(
      ResolvedLlmEndpoint endpoint,
      LlmAuthentication authentication,
      Supplier<String> credential,
      okhttp3.OkHttpClient transport,
      List<okhttp3.Interceptor> interceptors,
      java.time.Duration deadline,
      java.util.Map<String, String> attributes,
      Runnable verify) {
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
    for (var interceptor : interceptors) builder.addInterceptor(interceptor);
    var expected =
        okhttp3.HttpUrl.get(
            endpoint.baseUrl().replaceAll("/+$", "")
                + (endpoint.apiType() == LlmApiType.RESPONSES
                    ? "/responses"
                    : "/chat/completions"));
    builder.addInterceptor(chain -> {
      var request = chain.request();
      if (!request.method().equals("POST")
          || !request.url().equals(expected)
          || request.header("Host") != null)
        throw new java.io.IOException("Pinned inference destination changed");
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
      if (body != null)
        request = request.newBuilder().method(request.method(), nonReplayable(body)).build();
      return chain.proceed(request);
    });
    return builder.build();
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
