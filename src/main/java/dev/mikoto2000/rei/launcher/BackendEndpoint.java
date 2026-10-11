package dev.mikoto2000.rei.launcher;

import java.net.URI;
import java.util.UUID;

/** Public discovery metadata. Never contains credentials. */
public record BackendEndpoint(int schemaVersion, String instanceId, String storageId, long pid,
    String baseUrl, int apiProtocolVersion, String status) {
  public static final int CURRENT_API_PROTOCOL=2;
  public BackendEndpoint {
    if (schemaVersion != 1 || pid <= 0 || apiProtocolVersion <= 0 || !"READY".equals(status))
      throw new IllegalArgumentException("Unsupported backend endpoint");
    canonicalUuid(instanceId); canonicalUuid(storageId);
    URI uri = URI.create(baseUrl);
    if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
        || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null || uri.getRawFragment() != null
        || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))
      throw new IllegalArgumentException("Backend discovery requires literal IPv4 loopback HTTP");
  }
  private static void canonicalUuid(String value) {
    if (value == null || !UUID.fromString(value).toString().equals(value))
      throw new IllegalArgumentException("Invalid backend identity");
  }
  public URI uri() { return URI.create(baseUrl); }
  public boolean sameInstance(BackendEndpoint other) {
    return equals(other);
  }
}
