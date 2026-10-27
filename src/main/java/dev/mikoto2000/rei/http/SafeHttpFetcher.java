package dev.mikoto2000.rei.http;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 安全性HTTP通信に必要な情報を外部から取得する。
 *
 * <p>Bounded transfer, pinned DNS answers and a single operation deadline.
 */
@Component
public class SafeHttpFetcher {
  /**
   * 外部情報の取得で受け渡す応答を表す。
   *
   * @param status 状態
   * @param headers HTTPヘッダー
   * @param body 本文
   * @param finalUri 最終URI
   * @param retrievedAt retrieved日時
   * @param validatedAt 検証済み日時
   */
  public record Response(
      int status,
      Map<String, String> headers,
      byte[] body,
      URI finalUri,
      java.time.Instant retrievedAt,
      java.time.Instant validatedAt) {
    /** 外部情報の取得を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Response(int status, Map<String, String> headers, byte[] body, URI finalUri) {
      this(status, headers, body, finalUri, java.time.Instant.now(), java.time.Instant.now());
    }

    /** 外部情報の取得で用いるヘッダーを返す。 */
    public String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }
  }

  /** 後続の処理に必要な情報を、取得先から読み込む。 */
  public Response fetch(
      URI initial,
      Map<String, String> headers,
      HttpFetchPolicy policy,
      FetchOperation operation,
      HttpFetchObserver observer) {
    var bounded = operation.withTimeout(policy.totalTimeout());
    URI uri = initial;
    try {
      for (int hop = 0; ; hop++) {
        bounded.check();
        policy.validate(uri);
        var response = exchange(uri, headers, policy, bounded, observer, hop > 0);
        if (response.status() < 300 || response.status() >= 400 || response.status() == 304)
          return response;
        if (hop >= policy.maxRedirects() || response.header("location") == null)
          throw new HttpFetchException(HttpFetchException.Code.REDIRECT_LIMIT);
        try {
          uri = uri.resolve(response.header("location"));
        } catch (IllegalArgumentException invalid) {
          throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
        }
        if (!initial.getScheme().equalsIgnoreCase(uri.getScheme())
            || !initial.getHost().equalsIgnoreCase(uri.getHost())
            || effectivePort(initial) != effectivePort(uri)) {
          var forwarded = new LinkedHashMap<>(headers);
          forwarded
              .keySet()
              .removeIf(
                  name ->
                      Set.of("if-none-match", "if-modified-since")
                          .contains(name.toLowerCase(Locale.ROOT)));
          if (forwarded.keySet().stream()
              .anyMatch(
                  name ->
                      !Set.of("accept", "accept-encoding", "user-agent", "cache-control")
                          .contains(name.toLowerCase(Locale.ROOT))))
            throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
          headers = Map.copyOf(forwarded);
        }
      }
    } catch (CancellationException cancelled) {
      observer.cancellation();
      throw cancelled;
    } catch (RuntimeException failed) {
      FetchOperation.propagateControls(failed);
      var safe = FetchOperation.classify(failed);
      if (safe instanceof HttpFetchException http) observer.failure(http.code());
      throw safe;
    }
  }

  /** 外部情報の取得で用いる実際に使うポートを返す。 */
  private static int effectivePort(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  /** 利用条件に合わない指定を受け付けないよう、Peerを検証する。 */
  static void validatePeer(
      java.net.SocketAddress peer, Set<InetAddress> approved, HttpFetchPolicy policy) {
    if (!(peer instanceof InetSocketAddress socket)
        || socket.isUnresolved()
        || !approved.contains(socket.getAddress())
        || !policy.allowsAddress(socket.getAddress()))
      throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
  }

  /** 外部情報の取得で用いるexchangeを返す。 */
  public Response exchange(
      URI uri,
      Map<String, String> headers,
      HttpFetchPolicy policy,
      FetchOperation operation,
      HttpFetchObserver observer,
      boolean redirect) {
    return exchange(uri, "GET", null, headers, policy, operation, observer, redirect);
  }

  /** Execute one bounded exchange without redirect or automatic transport retries. */
  public Response exchange(
      URI uri,
      String method,
      byte[] body,
      Map<String, String> headers,
      HttpFetchPolicy policy,
      FetchOperation operation,
      HttpFetchObserver observer,
      boolean redirect) {
    operation.check();
    policy.validate(uri);
    var transferBudget = TransferScope.current();
    Set<InetAddress> approved = ConcurrentHashMap.newKeySet();
    var literal = PublicNetworkPolicy.literal(uri.getHost());
    if (literal != null) {
      if (!policy.allowsAddress(literal))
        throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
      approved.add(literal);
    }
    try (var lease = FetchScope.acquireConnection(uri, operation);
        var resolver = new ValidatingResolverGroup(policy::allowsAddress, approved)) {
      var client =
          reactor.netty.http.client.HttpClient.newConnection()
              .compress(false)
              .keepAlive(false)
              .followRedirect(false)
              .disableRetry(true)
              .resolver(resolver)
              .option(
                  io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                  (int) policy.connectTimeout().toMillis())
              .responseTimeout(policy.readTimeout())
              .doOnRequest((request, connection) -> operation.check())
              .doOnConnected(
                  connection -> {
                    try {
                      validatePeer(connection.channel().remoteAddress(), approved, policy);
                    } catch (HttpFetchException refused) {
                      connection.dispose();
                      throw refused;
                    }
                  });
      operation.check();
      observer.request(redirect);
      var future =
          client
              .headers(h -> headers.forEach(h::set))
              .request(io.netty.handler.codec.http.HttpMethod.valueOf(method))
              .uri(uri.toASCIIString())
              .send(
                  body == null
                      ? reactor.netty.ByteBufFlux.empty()
                      : reactor.netty.ByteBufFlux.fromInbound(Mono.just(body)))
              .response(
                  (response, content) -> {
                    int status = response.status().code();
                    observer.status(status);
                    Map<String, String> received = new HashMap<>();
                    response
                        .responseHeaders()
                        .forEach(
                            h -> {
                              String name = h.getKey().toLowerCase(Locale.ROOT);
                              if (Set.of("cache-control", "vary").contains(name))
                                received.merge(
                                    name,
                                    h.getValue(),
                                    (previous, value) -> previous + ", " + value);
                              else received.put(name, h.getValue());
                            });
                    String length = received.get("content-length");
                    if (length != null && !"HEAD".equals(method)) {
                      try {
                        if (Long.parseLong(length) > policy.maxWireBytes())
                          return Mono.<Response>error(
                              new HttpFetchException(HttpFetchException.Code.WIRE_LIMIT));
                      } catch (NumberFormatException invalid) {
                        return Mono.<Response>error(
                            new HttpFetchException(HttpFetchException.Code.NETWORK_ERROR));
                      }
                    }
                    if (status == 200
                        && policy.requiredContentType() != null
                        && !policy
                            .requiredContentType()
                            .equalsIgnoreCase(
                                received.getOrDefault("content-type", "").split(";", 2)[0].strip()))
                      return Mono.<Response>error(
                          new HttpFetchException(HttpFetchException.Code.INVALID_CONTENT_TYPE));
                    return content
                        .reduceWith(
                            ByteArrayOutputStream::new,
                            (bytes, buffer) -> {
                              operation.check();
                              int size = buffer.readableBytes();
                              observer.bytes(size);
                              BoundedBody.checkWireSize(bytes.size(), size, policy.maxWireBytes());
                              if (transferBudget != null) transferBudget.wire(size);
                              byte[] chunk = new byte[size];
                              buffer.readBytes(chunk);
                              bytes.writeBytes(chunk);
                              return bytes;
                            })
                        .map(
                            bytes ->
                                new Response(
                                    status, Map.copyOf(received), bytes.toByteArray(), uri));
                  })
              .single()
              .timeout(operation.remaining())
              .toFuture();
      var wire = operation.await(future);
      var decoded =
          BoundedBody.decode(
              wire.body(),
              wire.header("content-encoding"),
              policy.maxWireBytes(),
              policy.maxDecodedBytes(),
              operation::check,
              size -> {
                if (transferBudget != null) transferBudget.decoded(size);
              });
      operation.check();
      return new Response(wire.status(), wire.headers(), decoded, uri);
    } catch (RuntimeException failed) {
      throw FetchOperation.classify(failed);
    }
  }
}
