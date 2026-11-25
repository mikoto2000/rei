package dev.mikoto2000.rei.llm;

import com.openai.client.OpenAIClient;
import java.util.List;
import java.util.function.Supplier;

/** Explicit SDK clients sharing a host-owned transport without ambient credential lookup. */
final class PinnedOpenAiClients {
  private PinnedOpenAiClients() {}

  static OpenAIClient open(RunLlmConnection connection, Supplier<String> credential,
      okhttp3.OkHttpClient transport, List<okhttp3.Interceptor> interceptors, Runnable verify) {
    return open(connection.endpoint(), connection.authentication(), credential, transport, interceptors);
  }

  static OpenAIClient open(ResolvedLlmEndpoint endpoint, LlmAuthentication authentication,
      Supplier<String> credential, okhttp3.OkHttpClient transport, List<okhttp3.Interceptor> interceptors) {
    if (transport.followRedirects() || transport.followSslRedirects() || transport.retryOnConnectionFailure()
        || transport.cookieJar() != okhttp3.CookieJar.NO_COOKIES
        || transport.authenticator() != okhttp3.Authenticator.NONE
        || transport.proxyAuthenticator() != okhttp3.Authenticator.NONE
        || !transport.networkInterceptors().isEmpty() || !transport.interceptors().isEmpty())
      throw new IllegalArgumentException("Pinned transport must disable implicit forwarding and authentication");
    var builder = com.openai.client.okhttp.OpenAIOkHttpClient.builder()
        .okHttpClient(transport).baseUrl(endpoint.baseUrl()).timeout(endpoint.readTimeout()).maxRetries(0);
    switch (authentication) {
      case NONE -> builder.apiKey("").removeHeaders("Authorization")
          .removeHeaders("api-key").removeHeaders("x-api-key").removeHeaders("Cookie");
      case API_KEY, CHATGPT_OAUTH -> builder.credential(
          com.openai.credential.BearerTokenCredential.create(() -> currentCredential(credential)));
      default -> throw new IllegalArgumentException("Pinned authentication must be resolved explicitly");
    }
    for (var interceptor : interceptors) builder.addInterceptor(interceptor);
    var expected = endpoint.baseUrl().replaceAll("/+$", "")
        + (endpoint.apiType() == LlmApiType.RESPONSES ? "/responses" : "/chat/completions");
    builder.addInterceptor(chain -> {
      var request = chain.request();
      if (!request.method().equals("POST") || !request.url().toString().equals(expected)
          || request.header("Host") != null)
        throw new java.io.IOException("Pinned inference destination changed");
      if (request.header("api-key") != null || request.header("x-api-key") != null
          || request.header("Cookie") != null
          || authentication == LlmAuthentication.NONE && request.header("Authorization") != null)
        throw new java.io.IOException("Pinned inference credential contract changed");
      return chain.proceed(request);
    });
    return builder.build();
  }

  private static String currentCredential(Supplier<String> credential) {
    var value = credential.get();
    if (value == null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalStateException("Accepted inference credentials are unavailable");
    return value;
  }
}
