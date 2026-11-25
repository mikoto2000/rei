package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.core.chat.AgentRunContext;

/** Server-wide selection and host-owned accepted connections; no inference or tools. */
final class ChatRunConnections {
  private final ChatEndpointCatalog catalog;
  private final RunLlmConnectionFactory factory;
  private final java.util.concurrent.atomic.AtomicReference<Selection> selection;
  private final java.util.concurrent.ConcurrentMap<String, Accepted> accepted =
      new java.util.concurrent.ConcurrentHashMap<>();

  private record Selection(String endpoint, java.util.Map<String, String> models) {
    private Selection {
      models = java.util.Map.copyOf(models);
    }
  }

  private record Accepted(AgentRunContext owner, RunLlmConnection connection) {}

  ChatRunConnections(ChatEndpointCatalog catalog, RunLlmConnectionFactory factory) {
    this.catalog = catalog;
    this.factory = factory;
    this.selection =
        new java.util.concurrent.atomic.AtomicReference<>(
            new Selection(catalog.initialEndpoint(), java.util.Map.of()));
  }

  void select(String endpoint) {
    catalog.connection(endpoint);
    selection.updateAndGet(current -> new Selection(endpoint, current.models()));
  }

  void selectModel(String model) {
    var before = selection.get();
    factory.capture(before.endpoint(), model);
    var models = new java.util.HashMap<>(before.models());
    models.put(before.endpoint(), model);
    if (!selection.compareAndSet(before, new Selection(before.endpoint(), models)))
      throw new IllegalStateException("Chat selection changed while choosing a model");
  }

  String selectedEndpoint() {
    return selection.get().endpoint();
  }

  RunLlmConnection accept(AgentRunContext owner) {
    java.util.Objects.requireNonNull(owner, "owner");
    return accepted
        .compute(
            owner.runId(),
            (run, prior) -> {
              if (prior != null) {
                if (!prior.owner().equals(owner))
                  throw new IllegalStateException("Run connection owner mismatch");
                return prior;
              }
              var chosen = selection.get();
              return new Accepted(
                  owner,
                  factory.capture(chosen.endpoint(), chosen.models().get(chosen.endpoint())));
            })
        .connection();
  }

  RunLlmConnection require(AgentRunContext owner) {
    var entry = accepted.get(owner.runId());
    if (entry == null) throw new IllegalStateException("Run connection snapshot is unavailable");
    if (!entry.owner().equals(owner)) throw new IllegalStateException("Run connection owner mismatch");
    return entry.connection();
  }

  void release(AgentRunContext owner) {
    accepted.computeIfPresent(owner.runId(), (run, entry) -> {
      if (!entry.owner().equals(owner)) throw new IllegalStateException("Run connection owner mismatch");
      return null;
    });
  }

  void restore(AgentRunContext owner, RunLlmConnection saved) {
    java.util.Objects.requireNonNull(saved, "saved connection");
    accepted.compute(owner.runId(), (run, prior) -> {
      if (prior != null) {
        if (!prior.owner().equals(owner)) throw new IllegalStateException("Run connection owner mismatch");
        if (!prior.connection().equals(saved)) throw new IllegalStateException("Run connection cannot be replaced");
      }
      factory.verifyBinding(saved);
      return prior == null ? new Accepted(owner, saved) : prior;
    });
  }
}
