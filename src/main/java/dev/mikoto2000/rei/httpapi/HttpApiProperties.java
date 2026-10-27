package dev.mikoto2000.rei.httpapi;

import dev.mikoto2000.rei.http.PublicNetworkPolicy;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Host-owned configuration; credentials are environment references, never stored values. */
@ConfigurationProperties("rei.http-api")
public record HttpApiProperties(
    List<URI> allowedOrigins,
    Map<String, AuthProfile> authProfiles,
    List<URI> unsafeReadPrefixes,
    Integer maxBytes,
    Integer timeoutMillis) {
  /** Validate hard bounds and copy host configuration. */
  public HttpApiProperties {
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    authProfiles = authProfiles == null ? Map.of() : Map.copyOf(authProfiles);
    unsafeReadPrefixes = unsafeReadPrefixes == null ? List.of() : List.copyOf(unsafeReadPrefixes);
    maxBytes = maxBytes == null ? 256 * 1024 : maxBytes;
    timeoutMillis = timeoutMillis == null ? 30000 : timeoutMillis;
    if (maxBytes < 1 || maxBytes > 4 * 1024 * 1024 || timeoutMillis < 1 || timeoutMillis > 300000)
      throw new IllegalArgumentException("HTTP API limits");
    allowedOrigins.forEach(HttpApiProperties::validateOrigin);
    unsafeReadPrefixes.forEach(PublicNetworkPolicy::validateSyntax);
  }

  /** Only exact origins can grant internal access. */
  private static void validateOrigin(URI origin) {
    PublicNetworkPolicy.validateSyntax(origin);
    if (origin.getRawQuery() != null
        || origin.getRawFragment() != null
        || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/")))
      throw new IllegalArgumentException("Expected exact HTTP API origin");
  }

  /** Authentication metadata contains no credential material. */
  public record AuthProfile(URI origin, String type, String header, String secretEnv) {
    /** Validate profile without resolving its secret. */
    public AuthProfile {
      validateOrigin(origin);
      if (!java.util.Set.of("bearer", "api-key").contains(type)
          || secretEnv == null
          || !secretEnv.matches("[A-Za-z_][A-Za-z0-9_]*")
          || ("api-key".equals(type) && (header == null || !header.matches("(?i)x-[a-z0-9-]+"))))
        throw new IllegalArgumentException("Invalid HTTP API auth profile");
    }
  }
}
