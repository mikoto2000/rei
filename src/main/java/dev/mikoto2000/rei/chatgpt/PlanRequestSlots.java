package dev.mikoto2000.rei.chatgpt;

import dev.mikoto2000.rei.modeloptions.ChatPlanAccounts;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Host-owned request capacity; idle registrations retain no admission state. */
final class PlanRequestSlots {
  private record Key(String account, String registration) {}

  private static final class Scope {
    private final Set<Permit> requests = new HashSet<>();
  }

  private final int maximum;
  private final ConcurrentMap<Key, Scope> active = new ConcurrentHashMap<>();

  PlanRequestSlots(int maximum) {
    if (maximum < 1 || maximum > 8)
      throw new IllegalArgumentException("Plan request concurrency must be between 1 and 8");
    this.maximum = maximum;
  }

  ChatPlanAccounts.Request begin(String account, String registration) {
    var key = new Key(account, registration);
    var admitted = new AtomicReference<Permit>();
    active.compute(
        key,
        (ignored, existing) -> {
          var scope = existing == null ? new Scope() : existing;
          if (scope.requests.size() >= maximum)
            throw ChatGptException.of(ChatGptException.Code.BUSY);
          var request = new Permit(key, scope);
          scope.requests.add(request);
          admitted.set(request);
          return scope;
        });
    return java.util.Objects.requireNonNull(admitted.get());
  }

  void invalidate(String account) {
    var cancelled = new java.util.ArrayList<Permit>();
    active.forEach((key, scope) -> {
      if (!key.account().equals(account)) return;
      active.computeIfPresent(
          key,
          (ignored, current) -> {
            if (current == scope) cancelled.addAll(scope.requests);
            return current;
          });
    });
    // Completion can synchronously close a permit and reenter this map.
    for (var request : cancelled) request.invalidation.complete(null);
  }

  private final class Permit implements ChatPlanAccounts.Request {
    private final Key key;
    private final Scope scope;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> invalidation = new CompletableFuture<>();
    private final CompletionStage<Void> signal = invalidation.minimalCompletionStage();

    private Permit(Key key, Scope scope) {
      this.key = key;
      this.scope = scope;
    }

    @Override
    public CompletionStage<Void> invalidated() {
      return signal;
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) return;
      active.computeIfPresent(
          key,
          (ignored, current) -> {
            if (current != scope) return current;
            scope.requests.remove(this);
            return scope.requests.isEmpty() ? null : scope;
          });
    }
  }
}
