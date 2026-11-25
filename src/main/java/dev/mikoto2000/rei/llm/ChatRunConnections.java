package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.core.chat.AgentRunContext;

/** Server-wide selection and host-owned accepted connections; no inference or tools. */
final class ChatRunConnections {
  private final ChatEndpointCatalog catalog;
  private final RunLlmConnectionFactory factory;

  ChatRunConnections(ChatEndpointCatalog catalog, RunLlmConnectionFactory factory) {
    this.catalog = catalog;
    this.factory = factory;
  }

  void select(String endpoint) {}

  void selectModel(String model) {}

  String selectedEndpoint() {
    return catalog.initialEndpoint();
  }

  RunLlmConnection accept(AgentRunContext owner) {
    return factory.capture(catalog.initialEndpoint(), null);
  }
}
