package dev.mikoto2000.rei.llm;

import com.openai.client.OpenAIClient;
import java.util.List;
import java.util.function.Supplier;

/** Explicit SDK clients sharing a host-owned transport without ambient credential lookup. */
final class PinnedOpenAiClients {
  private PinnedOpenAiClients() {}

  static OpenAIClient open(ResolvedLlmEndpoint endpoint, LlmAuthentication authentication,
      Supplier<String> credential, okhttp3.OkHttpClient transport, List<okhttp3.Interceptor> interceptors) {
    return null;
  }
}
