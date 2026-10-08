package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.resolver.*;
import io.netty.util.concurrent.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ValidatingResolverGroupTest {
  @Test void closingResolverCancelsAnOutstandingDnsLookup() throws Exception {
    var executor = new DefaultEventExecutor();
    Promise<List<InetSocketAddress>> pending = executor.newPromise();
    var delegate = new AddressResolverGroup<InetSocketAddress>() {
      protected AddressResolver<InetSocketAddress> newResolver(EventExecutor owner) {
        return new AbstractAddressResolver<InetSocketAddress>(owner, InetSocketAddress.class) {
          protected boolean doIsResolved(InetSocketAddress address) { return false; }
          protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> result) { throw new AssertionError(); }
          protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> result) {
            pending.addListener(done -> { if (done.isCancelled()) result.cancel(false); });
            result.addListener(done -> { if (done.isCancelled()) pending.cancel(false); });
          }
        };
      }
    };
    try (delegate; var group = new ValidatingResolverGroup(PublicNetworkPolicy::isPublic, ConcurrentHashMap.newKeySet(), delegate)) {
      var resolver = group.getResolver(executor);
      var result = resolver.resolve(InetSocketAddress.createUnresolved("fixture.example", 80));
      resolver.close();
      executor.submit(() -> {}).sync();
      assertTrue(pending.isCancelled()); assertTrue(result.isCancelled());
    } finally { executor.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); }
  }
  @Test void resolvedPrivateSocketsCannotBypassDnsPolicy() throws Exception {
    var executor = new DefaultEventExecutor();
    try (var group = new ValidatingResolverGroup(PublicNetworkPolicy::isPublic, ConcurrentHashMap.newKeySet())) {
      var resolution = group.getResolver(executor).resolve(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 80));
      assertThrows(ExecutionException.class, () -> resolution.get(2, TimeUnit.SECONDS));
    } finally { executor.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); }
  }
  @Test void dnsAnswerIsPinnedAndAChangedPrivateAnswerIsRejected() throws Exception {
    var executor = new DefaultEventExecutor(); var calls = new AtomicInteger();
    var approved = ConcurrentHashMap.<InetAddress>newKeySet();
    var delegate = new AddressResolverGroup<InetSocketAddress>() {
      protected AddressResolver<InetSocketAddress> newResolver(EventExecutor owner) {
        return new AbstractAddressResolver<InetSocketAddress>(owner, InetSocketAddress.class) {
          protected boolean doIsResolved(InetSocketAddress address) { return false; }
          protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> result) { throw new AssertionError("resolveAll required"); }
          protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> result) {
            try { result.setSuccess(List.of(new InetSocketAddress(InetAddress.getByName(
                calls.incrementAndGet() == 1 ? "1.1.1.1" : "127.0.0.1"), address.getPort()))); }
            catch (UnknownHostException error) { result.setFailure(error); }
          }
        };
      }
    };
    try (delegate; var group = new ValidatingResolverGroup(PublicNetworkPolicy::isPublic, approved, delegate)) {
      var pinned = group.getResolver(executor).resolve(InetSocketAddress.createUnresolved("fixture.example", 80)).get(2, TimeUnit.SECONDS);
      assertEquals("1.1.1.1", pinned.getAddress().getHostAddress());
      assertEquals(1, calls.get());
      assertTrue(approved.contains(pinned.getAddress()));
      assertThrows(ExecutionException.class, () -> group.getResolver(executor)
          .resolve(InetSocketAddress.createUnresolved("fixture.example", 80)).get(2, TimeUnit.SECONDS));
      assertEquals(2, calls.get());
      assertEquals(1, approved.size());
    } finally { executor.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); }
  }
}
