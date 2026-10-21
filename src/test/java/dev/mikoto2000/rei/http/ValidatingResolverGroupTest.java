package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Validating対象の解決グループの結果と状態の契約を検証するテスト。 */
class ValidatingResolverGroupTest {
  /** 外部情報の取得の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void closingResolverCancelsAnOutstandingDnsLookup() throws Exception {
    var executor = new DefaultEventExecutor();
    Promise<List<InetSocketAddress>> pending = executor.newPromise();
    var delegate =
        new AddressResolverGroup<InetSocketAddress>() {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          protected AddressResolver<InetSocketAddress> newResolver(EventExecutor owner) {
            return new AbstractAddressResolver<InetSocketAddress>(owner, InetSocketAddress.class) {
              /** {@code doIsResolved} の条件を再現するための補助情報を返す。 */
              protected boolean doIsResolved(InetSocketAddress address) {
                return false;
              }

              /** {@code doResolve} の条件を再現するため、検証用の操作や状態を扱う。 */
              protected void doResolve(
                  InetSocketAddress address, Promise<InetSocketAddress> result) {
                throw new AssertionError();
              }

              /** {@code doResolveAll} の条件を再現するため、検証用の操作や状態を扱う。 */
              protected void doResolveAll(
                  InetSocketAddress address, Promise<List<InetSocketAddress>> result) {
                pending.addListener(
                    done -> {
                      if (done.isCancelled()) result.cancel(false);
                    });
                result.addListener(
                    done -> {
                      if (done.isCancelled()) pending.cancel(false);
                    });
              }
            };
          }
        };
    try (delegate;
        var group =
            new ValidatingResolverGroup(
                PublicNetworkPolicy::isPublic, ConcurrentHashMap.newKeySet(), delegate)) {
      var resolver = group.getResolver(executor);
      var result = resolver.resolve(InetSocketAddress.createUnresolved("fixture.example", 80));
      resolver.close();
      executor.submit(() -> {}).sync();
      assertTrue(pending.isCancelled());
      assertTrue(result.isCancelled());
    } finally {
      executor.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  /** 外部情報の取得で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void resolvedPrivateSocketsCannotBypassDnsPolicy() throws Exception {
    var executor = new DefaultEventExecutor();
    try (var group =
        new ValidatingResolverGroup(PublicNetworkPolicy::isPublic, ConcurrentHashMap.newKeySet())) {
      var resolution =
          group
              .getResolver(executor)
              .resolve(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 80));
      assertThrows(ExecutionException.class, () -> resolution.get(2, TimeUnit.SECONDS));
    } finally {
      executor.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  /** 外部情報の取得で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void dnsAnswerIsPinnedAndAChangedPrivateAnswerIsRejected() throws Exception {
    var executor = new DefaultEventExecutor();
    var calls = new AtomicInteger();
    var approved = ConcurrentHashMap.<InetAddress>newKeySet();
    var delegate =
        new AddressResolverGroup<InetSocketAddress>() {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          protected AddressResolver<InetSocketAddress> newResolver(EventExecutor owner) {
            return new AbstractAddressResolver<InetSocketAddress>(owner, InetSocketAddress.class) {
              /** {@code doIsResolved} の条件を再現するための補助情報を返す。 */
              protected boolean doIsResolved(InetSocketAddress address) {
                return false;
              }

              /** {@code doResolve} の条件を再現するため、検証用の操作や状態を扱う。 */
              protected void doResolve(
                  InetSocketAddress address, Promise<InetSocketAddress> result) {
                throw new AssertionError("resolveAll required");
              }

              /** {@code doResolveAll} の条件を再現するため、検証用の操作や状態を扱う。 */
              protected void doResolveAll(
                  InetSocketAddress address, Promise<List<InetSocketAddress>> result) {
                try {
                  result.setSuccess(
                      List.of(
                          new InetSocketAddress(
                              InetAddress.getByName(
                                  calls.incrementAndGet() == 1 ? "1.1.1.1" : "127.0.0.1"),
                              address.getPort())));
                } catch (UnknownHostException error) {
                  result.setFailure(error);
                }
              }
            };
          }
        };
    try (delegate;
        var group =
            new ValidatingResolverGroup(PublicNetworkPolicy::isPublic, approved, delegate)) {
      var pinned =
          group
              .getResolver(executor)
              .resolve(InetSocketAddress.createUnresolved("fixture.example", 80))
              .get(2, TimeUnit.SECONDS);
      assertEquals("1.1.1.1", pinned.getAddress().getHostAddress());
      assertEquals(1, calls.get());
      assertTrue(approved.contains(pinned.getAddress()));
      assertThrows(
          ExecutionException.class,
          () ->
              group
                  .getResolver(executor)
                  .resolve(InetSocketAddress.createUnresolved("fixture.example", 80))
                  .get(2, TimeUnit.SECONDS));
      assertEquals(2, calls.get());
      assertEquals(1, approved.size());
    } finally {
      executor.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }
}
