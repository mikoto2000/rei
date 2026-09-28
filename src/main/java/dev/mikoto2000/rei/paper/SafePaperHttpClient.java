package dev.mikoto2000.rei.paper;

import io.netty.resolver.*;
import io.netty.util.concurrent.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.util.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class SafePaperHttpClient implements PaperHttpClient {
  private final PaperProperties config;
  private final reactor.netty.http.client.HttpClient client;

  public SafePaperHttpClient(PaperProperties config) {
    config.validate();
    this.config = config;
    client =
        reactor.netty.http.client.HttpClient.newConnection()
            .resolver(new PublicResolverGroup())
            .followRedirect(false)
            .option(
                io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                (int) Math.min(Integer.MAX_VALUE, config.getTimeout().toMillis()))
            .responseTimeout(config.getTimeout());
  }

  public static void validateUri(URI uri) {
    if (!Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null)
      throw new PaperException(PaperException.Code.PDF_DOWNLOAD_FAILED, "許可されない URL");
  }

  public static boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isSiteLocalAddress()
        || address.isLinkLocalAddress()
        || address.isMulticastAddress()) return false;
    byte[] bytes = address.getAddress();
    int first = bytes[0] & 255, second = bytes[1] & 255;
    if (bytes.length == 4)
      return first != 0
          && first != 127
          && first < 224
          && !(first == 100 && second >= 64 && second <= 127)
          && !(first == 192 && second == 0)
          && !(first == 198 && (second == 18 || second == 19));
    return (first & 0xe0) == 0x20
        && !(first == 0x20 && second == 0x02)
        && !(first == 0x20
            && second == 0x01
            && ((bytes[2] == 0 && bytes[3] == 0)
                || (bytes[2] == 0x0d && (bytes[3] & 255) == 0xb8)));
  }

  /** Validates actual socket destinations, avoiding DNS preflight/rebinding races. */
  static final class PublicResolverGroup extends AddressResolverGroup<InetSocketAddress> {
    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
      var delegate = DefaultAddressResolverGroup.INSTANCE.getResolver(executor);
      return new AbstractAddressResolver<InetSocketAddress>(executor, InetSocketAddress.class) {
        protected boolean doIsResolved(InetSocketAddress address) {
          return false;
        }

        protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
          Promise<List<InetSocketAddress>> all = executor.newPromise();
          doResolveAll(address, all);
          all.addListener(
              done -> {
                if (done.isSuccess()) promise.trySuccess(all.getNow().getFirst());
                else promise.tryFailure(done.cause());
              });
        }

        protected void doResolveAll(
            InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
          delegate
              .resolveAll(address)
              .addListener(
                  done -> {
                    if (!done.isSuccess()) {
                      promise.tryFailure(done.cause());
                      return;
                    }
                    @SuppressWarnings("unchecked")
                    var addresses = (List<InetSocketAddress>) done.getNow();
                    if (addresses.isEmpty()
                        || addresses.stream()
                            .anyMatch(a -> a.isUnresolved() || !isPublic(a.getAddress())))
                      promise.tryFailure(
                          new PaperException(
                              PaperException.Code.PDF_DOWNLOAD_FAILED, "非公開アドレスは取得できません"));
                    else promise.trySuccess(addresses);
                  });
        }
      };
    }
  }

  record Response(int status, String location, String retryAfter, byte[] body) {}

  @Override
  public byte[] get(URI initial, String type, int maxBytes, PaperOperation op) {
    long started = System.nanoTime();
    for (int attempt = 0; attempt <= config.getRetries(); attempt++) {
      URI uri = initial;
      try {
        for (int redirect = 0; redirect <= 5; redirect++) {
          op.check();
          validateUri(uri);
          // Literal IPs may bypass a resolver in the transport, so validate them here too.
          if (io.netty.util.NetUtil.isValidIpV4Address(uri.getHost())
              || io.netty.util.NetUtil.isValidIpV6Address(uri.getHost())) {
            if (!isPublic(InetAddress.getByName(uri.getHost()))) throw failure("非公開アドレスは取得できません");
          }
          Response response = exchange(uri, type, maxBytes, op);
          if (response.status() >= 300 && response.status() < 400) {
            if (response.location() == null || redirect == 5)
              throw failure("リダイレクト上限または Location がありません");
            uri = uri.resolve(response.location());
            continue;
          }
          if (response.status() == 429 || response.status() >= 500) {
            if (attempt < config.getRetries()) {
              pause(attempt, response.retryAfter(), op);
              break;
            }
            throw new PaperException(
                response.status() == 429
                    ? PaperException.Code.PROVIDER_RATE_LIMITED
                    : PaperException.Code.SEARCH_FAILED,
                "Provider HTTP " + response.status());
          }
          if (response.status() != 200) throw failure("HTTP " + response.status());
          op.check();
          org.slf4j.LoggerFactory.getLogger(getClass())
              .info(
                  "paper HTTP host={} bytes={} durationMs={}",
                  initial.getHost(),
                  response.body().length,
                  (System.nanoTime() - started) / 1_000_000);
          return response.body();
        }
      } catch (java.util.concurrent.CancellationException e) {
        throw e;
      } catch (PaperException e) {
        if (e.code() == PaperException.Code.SEARCH_FAILED
            && e.getCause() instanceof java.util.concurrent.ExecutionException
            && attempt < config.getRetries()) {
          pause(attempt, "", op);
          continue;
        }
        throw e;
      } catch (Exception e) {
        dev.mikoto2000.rei.core.chat.RunCancellation.propagate(e);
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
          if (cause instanceof PaperException paper) throw paper;
          if (cause instanceof java.util.concurrent.TimeoutException
              || cause instanceof io.netty.handler.timeout.ReadTimeoutException)
            throw new PaperException(PaperException.Code.PROVIDER_TIMEOUT, "Provider timeout");
        }
        if (attempt == config.getRetries()) throw failure("HTTP 通信失敗");
        pause(attempt, "", op);
      }
    }
    throw failure("Provider retry 上限");
  }

  protected Response exchange(URI uri, String type, int maxBytes, PaperOperation op) {
    var future =
        client
            .headers(h -> h.set("User-Agent", config.getUserAgent()).set("Accept", type))
            .get()
            .uri(uri.toString())
            .response(
                (response, content) -> {
                  int status = response.status().code();
                  String actual =
                      response
                          .responseHeaders()
                          .get("Content-Type", "")
                          .toLowerCase(Locale.ROOT)
                          .split(";")[0]
                          .strip();
                  if (status == 200 && !actual.equals(type))
                    return Mono.<Response>error(failure("Content-Type が一致しません"));
                  return content
                      .reduceWith(
                          ByteArrayOutputStream::new,
                          (bytes, buffer) -> {
                            op.check();
                            if ((long) bytes.size() + buffer.readableBytes() > maxBytes)
                              throw failure("レスポンスサイズ上限");
                            byte[] chunk = new byte[buffer.readableBytes()];
                            buffer.readBytes(chunk);
                            bytes.writeBytes(chunk);
                            return bytes;
                          })
                      .map(
                          bytes ->
                              new Response(
                                  status,
                                  response.responseHeaders().get("Location"),
                                  response.responseHeaders().get("Retry-After", ""),
                                  bytes.toByteArray()));
                })
            .single()
            .timeout(config.getTimeout())
            .toFuture();
    return op.await(future, config.getTimeout());
  }

  private PaperException failure(String message) {
    return new PaperException(PaperException.Code.PDF_DOWNLOAD_FAILED, message);
  }

  private void pause(int attempt, String retry, PaperOperation op) {
    long ms = Math.min(5000, config.getBackoff().toMillis() * (1L << attempt));
    try {
      ms = Math.min(5000, Math.max(ms, Math.multiplyExact(Long.parseLong(retry), 1000)));
    } catch (NumberFormatException | ArithmeticException ignored) {
    }
    long until = System.nanoTime() + ms * 1_000_000;
    while (System.nanoTime() < until) {
      op.check();
      try {
        Thread.sleep(Math.max(1, Math.min(100, ms)));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        op.check();
      }
    }
  }
}
