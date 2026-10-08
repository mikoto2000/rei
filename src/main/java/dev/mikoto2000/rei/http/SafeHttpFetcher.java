package dev.mikoto2000.rei.http;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Bounded transfer, pinned DNS answers and a single operation deadline. */
@Component
public class SafeHttpFetcher {
  public record Response(int status, Map<String, String> headers, byte[] body, URI finalUri) {
    public String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
  }
  public Response fetch(URI initial, Map<String, String> headers, HttpFetchPolicy policy,
      FetchOperation operation, HttpFetchObserver observer) {
    var bounded = operation.withTimeout(policy.totalTimeout());
    URI uri = initial;
    try {
      for (int hop = 0; ; hop++) {
        bounded.check(); policy.validate(uri);
        var response = exchange(uri, headers, policy, bounded, observer, hop > 0);
        if (response.status() < 300 || response.status() >= 400 || response.status() == 304) return response;
        if (hop >= policy.maxRedirects() || response.header("location") == null)
          throw new HttpFetchException(HttpFetchException.Code.REDIRECT_LIMIT);
        try { uri = uri.resolve(response.header("location")); }
        catch (IllegalArgumentException invalid) { throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED); }
        if (headers.keySet().stream().anyMatch(name -> !Set.of("accept", "accept-encoding", "user-agent").contains(name.toLowerCase(Locale.ROOT)))
            && (!initial.getScheme().equalsIgnoreCase(uri.getScheme()) || !initial.getHost().equalsIgnoreCase(uri.getHost())
                || effectivePort(initial) != effectivePort(uri)))
          throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
      }
    } catch (CancellationException cancelled) { observer.cancellation(); throw cancelled; }
    catch (RuntimeException failed) {
      FetchOperation.propagateControls(failed);
      var safe = FetchOperation.classify(failed);
      if (safe instanceof HttpFetchException http) observer.failure(http.code());
      throw safe;
    }
  }
  private static int effectivePort(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }
  static void validatePeer(java.net.SocketAddress peer, Set<InetAddress> approved, HttpFetchPolicy policy) {
    if (!(peer instanceof InetSocketAddress socket) || socket.isUnresolved()
        || !approved.contains(socket.getAddress()) || !policy.allowsAddress(socket.getAddress()))
      throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
  }
  public Response exchange(URI uri, Map<String, String> headers, HttpFetchPolicy policy,
      FetchOperation operation, HttpFetchObserver observer, boolean redirect) {
    operation.check(); policy.validate(uri);
    Set<InetAddress> approved = ConcurrentHashMap.newKeySet();
    var literal = PublicNetworkPolicy.literal(uri.getHost());
    if (literal != null) {
      if (!policy.allowsAddress(literal)) throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
      approved.add(literal);
    }
    try (var resolver = new ValidatingResolverGroup(policy::allowsAddress, approved)) {
      var client = reactor.netty.http.client.HttpClient.newConnection()
          .compress(false).keepAlive(false).followRedirect(false).resolver(resolver)
          .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) policy.connectTimeout().toMillis())
          .responseTimeout(policy.readTimeout())
          .doOnRequest((request, connection) -> operation.check())
          .doOnConnected(connection -> {
            try { validatePeer(connection.channel().remoteAddress(), approved, policy); }
            catch (HttpFetchException refused) { connection.dispose(); throw refused; }
          });
      operation.check();
      observer.request(redirect);
      var future = client.headers(h -> headers.forEach(h::set)).get().uri(uri.toASCIIString())
          .response((response, content) -> {
            int status = response.status().code(); observer.status(status);
            Map<String, String> received = new HashMap<>();
            response.responseHeaders().forEach(h -> received.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue()));
            String length = received.get("content-length");
            if (length != null) {
              try { if (Long.parseLong(length) > policy.maxWireBytes()) return Mono.<Response>error(new HttpFetchException(HttpFetchException.Code.WIRE_LIMIT)); }
              catch (NumberFormatException invalid) { return Mono.<Response>error(new HttpFetchException(HttpFetchException.Code.NETWORK_ERROR)); }
            }
            if (status == 200 && policy.requiredContentType() != null
                && !policy.requiredContentType().equalsIgnoreCase(received.getOrDefault("content-type", "").split(";", 2)[0].strip()))
              return Mono.<Response>error(new HttpFetchException(HttpFetchException.Code.INVALID_CONTENT_TYPE));
            return content.reduceWith(ByteArrayOutputStream::new, (bytes, buffer) -> {
              operation.check(); int size = buffer.readableBytes(); observer.bytes(size);
              BoundedBody.checkWireSize(bytes.size(), size, policy.maxWireBytes());
              byte[] chunk = new byte[size]; buffer.readBytes(chunk); bytes.writeBytes(chunk); return bytes;
            }).map(bytes -> new Response(status, Map.copyOf(received), bytes.toByteArray(), uri));
          }).single().timeout(operation.remaining()).toFuture();
      var wire = operation.await(future);
      var decoded = BoundedBody.decode(wire.body(), wire.header("content-encoding"),
          policy.maxWireBytes(), policy.maxDecodedBytes(), operation::check);
      operation.check(); return new Response(wire.status(), wire.headers(), decoded, uri);
    } catch (RuntimeException failed) { throw FetchOperation.classify(failed); }
  }
}
