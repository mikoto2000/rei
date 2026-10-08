package dev.mikoto2000.rei.http;

import java.net.*;
import java.time.Duration;

public record HttpFetchPolicy(int maxWireBytes, int maxDecodedBytes, Duration connectTimeout,
    Duration readTimeout, Duration totalTimeout, int maxRedirects, String requiredContentType,
    URI configuredOrigin, boolean allowPrivateOrigin) {
  public HttpFetchPolicy {
    if (maxWireBytes <= 0 || maxDecodedBytes <= 0 || maxWireBytes > 100 * 1024 * 1024
        || maxDecodedBytes > 100 * 1024 * 1024 || maxRedirects < 0 || maxRedirects > 10)
      throw new IllegalArgumentException("HTTP fetch limits");
    for (var duration : new Duration[] { connectTimeout, readTimeout, totalTimeout })
      if (duration == null || duration.isZero() || duration.isNegative() || duration.compareTo(Duration.ofMinutes(5)) > 0)
        throw new IllegalArgumentException("HTTP fetch timeout");
    if (configuredOrigin != null) PublicNetworkPolicy.validateSyntax(configuredOrigin);
  }
  public static HttpFetchPolicy html(Duration timeout) {
    return new HttpFetchPolicy(2 * 1024 * 1024, 4 * 1024 * 1024, Duration.ofSeconds(5),
        Duration.ofSeconds(10), timeout, 5, null, null, false);
  }
  private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }
  public void validate(URI uri) {
    PublicNetworkPolicy.validateSyntax(uri);
    if (configuredOrigin != null) {
      if (!configuredOrigin.getScheme().equalsIgnoreCase(uri.getScheme())
          || !configuredOrigin.getHost().equalsIgnoreCase(uri.getHost()) || port(configuredOrigin) != port(uri))
        throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
      if (allowPrivateOrigin || loopbackOrigin()) return;
    }
    PublicNetworkPolicy.validate(uri);
  }
  private boolean loopbackOrigin() {
    if (configuredOrigin == null) return false;
    if ("localhost".equalsIgnoreCase(configuredOrigin.getHost())) return true;
    InetAddress address = PublicNetworkPolicy.literal(configuredOrigin.getHost());
    return address != null && address.isLoopbackAddress();
  }
  public boolean allowsAddress(InetAddress address) {
    if (configuredOrigin != null && allowPrivateOrigin) return true;
    if (loopbackOrigin()) return address.isLoopbackAddress();
    return PublicNetworkPolicy.isPublic(address);
  }
}
