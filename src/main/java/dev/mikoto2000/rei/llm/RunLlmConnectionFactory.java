package dev.mikoto2000.rei.llm;

import java.util.function.Function;

/** Resolves immutable settings only; credentials are obtained at the HTTP boundary. */
final class RunLlmConnectionFactory {
  RunLlmConnectionFactory(ChatEndpointCatalog catalog, LlmProperties properties,
      Function<String, ResolvedLlmEndpoint> legacy, Function<String, String> registration) {}

  RunLlmConnection capture(String profile, String modelOverride) {
    return null;
  }
}
