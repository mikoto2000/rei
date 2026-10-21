package dev.mikoto2000.rei.http;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 外部情報の取得に必要なValidating対象の解決グループを扱う。
 *
 * <p>Validates all answers and passes resolved socket addresses to the connector.
 */
public class ValidatingResolverGroup extends AddressResolverGroup<InetSocketAddress> {
  /** 許可。 */
  private final Predicate<InetAddress> allowed;

  /** 承認済み。 */
  private final Set<InetAddress> approved;

  /** 委譲先。 */
  private final AddressResolverGroup<InetSocketAddress> delegate;

  /** 外部情報の取得を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @SuppressWarnings("unchecked")
  public ValidatingResolverGroup(Predicate<InetAddress> allowed, Set<InetAddress> approved) {
    this(
        allowed,
        approved,
        (AddressResolverGroup<InetSocketAddress>)
            reactor.netty.http.HttpResources.get().getOrCreateDefaultResolver());
  }

  /** 指定された値や利用先を使って、ValidatingResolverGroupを初期化する。 */
  public ValidatingResolverGroup(
      Predicate<InetAddress> allowed,
      Set<InetAddress> approved,
      AddressResolverGroup<InetSocketAddress> delegate) {
    this.allowed = allowed;
    this.approved = approved;
    this.delegate = delegate;
  }

  /** 新たな対象を利用できるよう、対象の解決を作成する。 */
  protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
    return new AbstractAddressResolver<InetSocketAddress>(executor, InetSocketAddress.class) {
      /** 待機中。 */
      private final Set<Future<?>> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();

      /** 終了済み。 */
      private final java.util.concurrent.atomic.AtomicBoolean closed =
          new java.util.concurrent.atomic.AtomicBoolean();

      /** 外部情報の取得で利用した資源や登録を解放し、利用を終了する。 */
      public void close() {
        closed.set(true);
        pending.forEach(lookup -> lookup.cancel(false));
        pending.clear();
      }

      /** 外部情報の取得において、do該当確定したに該当するか判定する。 */
      protected boolean doIsResolved(InetSocketAddress address) {
        return false;
      }

      /** 外部情報の取得に関するdo対象の解決を処理する。 */
      protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> result) {
        Promise<List<InetSocketAddress>> all = executor.newPromise();
        all.addListener(
            done -> {
              if (done.isSuccess()) result.trySuccess(all.getNow().getFirst());
              else if (done.isCancelled()) result.cancel(false);
              else result.tryFailure(done.cause());
            });
        result.addListener(
            done -> {
              if (done.isCancelled()) all.cancel(false);
            });
        doResolveAll(address, all);
      }

      /** 外部情報の取得に関するdo対象の解決全対象を処理する。 */
      protected void doResolveAll(
          InetSocketAddress address, Promise<List<InetSocketAddress>> result) {
        if (closed.get()) {
          result.cancel(false);
          return;
        }
        if (!address.isUnresolved()) {
          validate(List.of(address), result);
          return;
        }
        var lookup = delegate.getResolver(executor).resolveAll(address);
        pending.add(lookup);
        if (closed.get()) lookup.cancel(false);
        result.addListener(
            done -> {
              if (done.isCancelled()) lookup.cancel(false);
            });
        lookup.addListener(
            done -> {
              pending.remove(lookup);
              if (done.isSuccess()) validate(lookup.getNow(), result);
              else if (done.isCancelled()) result.cancel(false);
              else result.tryFailure(done.cause());
            });
      }

      /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
      private void validate(
          List<InetSocketAddress> answers, Promise<List<InetSocketAddress>> result) {
        if (answers == null
            || answers.isEmpty()
            || answers.stream().anyMatch(a -> a.isUnresolved() || !allowed.test(a.getAddress()))) {
          result.tryFailure(new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED));
          return;
        }
        if (closed.get()) {
          result.cancel(false);
          return;
        }
        if (result.isCancelled()) return;
        answers.forEach(a -> approved.add(a.getAddress()));
        result.trySuccess(List.copyOf(answers));
      }
    };
  }
}
