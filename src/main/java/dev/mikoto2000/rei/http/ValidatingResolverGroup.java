package dev.mikoto2000.rei.http;

import io.netty.resolver.*;
import io.netty.util.concurrent.*;
import java.net.*;
import java.util.*;
import java.util.function.Predicate;

/** Validates all answers and passes resolved socket addresses to the connector. */
public class ValidatingResolverGroup extends AddressResolverGroup<InetSocketAddress> {
  private final Predicate<InetAddress> allowed;
  private final Set<InetAddress> approved;
  private final AddressResolverGroup<InetSocketAddress> delegate;
  @SuppressWarnings("unchecked")
  public ValidatingResolverGroup(Predicate<InetAddress> allowed, Set<InetAddress> approved) {
    this(allowed, approved, (AddressResolverGroup<InetSocketAddress>) reactor.netty.http.HttpResources.get().getOrCreateDefaultResolver());
  }
  public ValidatingResolverGroup(Predicate<InetAddress> allowed, Set<InetAddress> approved,
      AddressResolverGroup<InetSocketAddress> delegate) {
    this.allowed = allowed; this.approved = approved; this.delegate = delegate;
  }
  protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
    return new AbstractAddressResolver<InetSocketAddress>(executor, InetSocketAddress.class) {
      private final Set<Future<?>> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();
      private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
      public void close() {
        closed.set(true); pending.forEach(lookup -> lookup.cancel(false)); pending.clear();
      }
      protected boolean doIsResolved(InetSocketAddress address) { return false; }
      protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> result) {
        Promise<List<InetSocketAddress>> all = executor.newPromise();
        all.addListener(done -> {
          if (done.isSuccess()) result.trySuccess(all.getNow().getFirst());
          else if (done.isCancelled()) result.cancel(false);
          else result.tryFailure(done.cause());
        });
        result.addListener(done -> { if (done.isCancelled()) all.cancel(false); });
        doResolveAll(address, all);
      }
      protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> result) {
        if (closed.get()) { result.cancel(false); return; }
        if (!address.isUnresolved()) { validate(List.of(address), result); return; }
        var lookup = delegate.getResolver(executor).resolveAll(address);
        pending.add(lookup);
        if (closed.get()) lookup.cancel(false);
        result.addListener(done -> { if (done.isCancelled()) lookup.cancel(false); });
        lookup.addListener(done -> {
          pending.remove(lookup);
          if (done.isSuccess()) validate(lookup.getNow(), result);
          else if (done.isCancelled()) result.cancel(false);
          else result.tryFailure(done.cause());
        });
      }
      private void validate(List<InetSocketAddress> answers, Promise<List<InetSocketAddress>> result) {
        if (answers == null || answers.isEmpty() || answers.stream().anyMatch(a -> a.isUnresolved() || !allowed.test(a.getAddress()))) {
          result.tryFailure(new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED)); return;
        }
        if (closed.get()) { result.cancel(false); return; }
        if (result.isCancelled()) return;
        answers.forEach(a -> approved.add(a.getAddress()));
        result.trySuccess(List.copyOf(answers));
      }
    };
  }
}
