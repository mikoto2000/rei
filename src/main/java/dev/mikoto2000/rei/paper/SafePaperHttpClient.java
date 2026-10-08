package dev.mikoto2000.rei.paper;

import java.net.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SafePaperHttpClient implements PaperHttpClient {
  private final PaperProperties config;
  private final dev.mikoto2000.rei.http.SafeHttpFetcher fetcher;
  public SafePaperHttpClient(PaperProperties config) {
    this(config, new dev.mikoto2000.rei.http.SafeHttpFetcher());
  }
  @org.springframework.beans.factory.annotation.Autowired
  public SafePaperHttpClient(PaperProperties config, dev.mikoto2000.rei.http.SafeHttpFetcher fetcher) {
    config.validate(); this.config = config; this.fetcher = fetcher;
  }
  public static void validateUri(URI uri) {
    try { dev.mikoto2000.rei.http.PublicNetworkPolicy.validate(uri); }
    catch (dev.mikoto2000.rei.http.HttpFetchException error) {
      throw new PaperException(PaperException.Code.PDF_DOWNLOAD_FAILED, "許可されない URL");
    }
  }
  public static boolean isPublic(InetAddress address) {
    return dev.mikoto2000.rei.http.PublicNetworkPolicy.isPublic(address);
  }
  static final class PublicResolverGroup extends dev.mikoto2000.rei.http.ValidatingResolverGroup {
    PublicResolverGroup() { super(SafePaperHttpClient::isPublic, java.util.concurrent.ConcurrentHashMap.newKeySet()); }
  }
  record Response(int status, String location, String retryAfter, byte[] body) {}

  @Override
  public byte[] get(URI initial, String type, int maxBytes, PaperOperation op) {
    op = op.withDeadline(config.getTimeout());
    long started = System.nanoTime();
    for (int attempt = 0; attempt <= config.getRetries(); attempt++) {
      URI uri = initial;
      try {
        for (int redirect = 0; redirect <= config.getMaxRedirects(); redirect++) {
          op.check();
          validateUri(uri);
          // Literal IPs may bypass a resolver in the transport, so validate them here too.
          if (io.netty.util.NetUtil.isValidIpV4Address(uri.getHost())
              || io.netty.util.NetUtil.isValidIpV6Address(uri.getHost())) {
            if (!isPublic(InetAddress.getByName(uri.getHost()))) throw failure("非公開アドレスは取得できません");
          }
          Response response = exchange(uri, type, maxBytes, op);
          if (response.status() >= 300 && response.status() < 400) {
            if (response.location() == null || redirect == config.getMaxRedirects())
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
        dev.mikoto2000.rei.http.FetchOperation.propagateControls(e);
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
    int decoded = "application/pdf".equals(type) ? config.getMaxDecodedPdfBytes() : config.getMaxDecodedResponseBytes();
    var policy = new dev.mikoto2000.rei.http.HttpFetchPolicy(maxBytes, decoded,
        config.getConnectTimeout(), config.getReadTimeout(), config.getTimeout(), config.getMaxRedirects(), type, null, false);
    var operation = new dev.mikoto2000.rei.http.FetchOperation(op::check, op.deadlineNanos());
    try {
      var response = fetcher.exchange(uri, Map.of("User-Agent", config.getUserAgent(), "Accept", type),
          policy, operation, dev.mikoto2000.rei.http.HttpFetchObserver.NONE, false);
      return new Response(response.status(), response.header("location"), response.header("retry-after"), response.body());
    } catch (dev.mikoto2000.rei.http.HttpFetchException failed) {
      if (failed.code() == dev.mikoto2000.rei.http.HttpFetchException.Code.NETWORK_ERROR)
        throw new PaperException(PaperException.Code.SEARCH_FAILED, failed.code().name(),
            new java.util.concurrent.ExecutionException(failed));
      boolean timeout = switch (failed.code()) {
        case CONNECT_TIMEOUT, READ_TIMEOUT, TOTAL_TIMEOUT -> true;
        default -> false;
      };
      throw new PaperException(timeout ? PaperException.Code.PROVIDER_TIMEOUT : PaperException.Code.PDF_DOWNLOAD_FAILED,
          failed.code().name());
    }
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
