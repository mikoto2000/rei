package dev.mikoto2000.rei.http;

import java.net.*;
import java.util.*;

/** Conservative public HTTP destinations; DNS and actual peers are also checked by the transport. */
public final class PublicNetworkPolicy {
  private PublicNetworkPolicy() {}
  public static void validateSyntax(URI uri) {
    if (uri == null || uri.toString().length() > 8192 || uri.getScheme() == null
        || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
        || uri.getHost() == null || uri.getHost().contains("%") || uri.getUserInfo() != null
        || uri.getPort() == 0 || uri.getPort() > 65535)
      throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
  }
  public static void validate(URI uri) {
    validateSyntax(uri);
    int expectedPort = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    String host = uri.getHost().toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
    if (uri.getPort() != -1 && uri.getPort() != expectedPort
        || host.equals("localhost") || host.endsWith(".localhost"))
      throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
    InetAddress literal = literal(uri.getHost());
    if (literal != null && !isPublic(literal)) throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
  }
  public static InetAddress literal(String host) {
    if (!io.netty.util.NetUtil.isValidIpV4Address(host) && !io.netty.util.NetUtil.isValidIpV6Address(host)) return null;
    try { return InetAddress.getByName(host); }
    catch (UnknownHostException error) { throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED); }
  }
  public static boolean isPublic(InetAddress address) {
    if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
        || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
    byte[] bytes = address.getAddress();
    int first = bytes[0] & 255, second = bytes[1] & 255, third = bytes[2] & 255;
    if (bytes.length == 4) return first != 0 && first != 127 && first < 224
        && !(first == 100 && second >= 64 && second <= 127)
        && !(first == 192 && second == 0 && (third == 0 || third == 2))
        && !(first == 192 && second == 88 && third == 99)
        && !(first == 198 && (second == 18 || second == 19 || second == 51 && third == 100))
        && !(first == 203 && second == 0 && third == 113);
    // Global-unicast only; reject IETF protocol/transition and documentation blocks conservatively.
    return (first & 0xe0) == 0x20
        && !(first == 0x20 && second == 0x02)
        && !(first == 0x20 && second == 0x01 && (third < 2 || third == 0x0d && (bytes[3] & 255) == 0xb8))
        && !(first == 0x3f && second == 0xff && (third & 0xf0) == 0);
  }
}
