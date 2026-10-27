package dev.mikoto2000.rei.httpapi;

import dev.mikoto2000.rei.http.FetchScope;
import dev.mikoto2000.rei.http.HttpFetchException;
import dev.mikoto2000.rei.http.HttpFetchPolicy;
import dev.mikoto2000.rei.http.PublicNetworkPolicy;
import dev.mikoto2000.rei.http.SafeHttpFetcher;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** REST semantics over the existing DNS-pinned, bounded HTTP transport. */
@Service
@EnableConfigurationProperties(HttpApiProperties.class)
public class HttpApiService {
  private final SafeHttpFetcher fetcher;
  private final HttpApiProperties properties;
  private final ObjectMapper mapper = new ObjectMapper();
  private final java.util.function.Function<String, String> secrets;
  private final HttpApiMetrics metrics;

  /** Compose the shared transport with host-only policy. */
  @org.springframework.beans.factory.annotation.Autowired
  public HttpApiService(SafeHttpFetcher fetcher, HttpApiProperties properties) {
    this(fetcher, properties, System::getenv);
  }

  HttpApiService(
      SafeHttpFetcher fetcher,
      HttpApiProperties properties,
      java.util.function.Function<String, String> secrets) {
    this(fetcher, properties, secrets, HttpApiMetrics.OBSERVED);
  }

  HttpApiService(
      SafeHttpFetcher fetcher,
      HttpApiProperties properties,
      java.util.function.Function<String, String> secrets,
      HttpApiMetrics metrics) {
    this.fetcher = fetcher;
    this.properties = properties;
    this.secrets = secrets;
    this.metrics = metrics;
  }

  /** Perform an API read. */
  public HttpApiResult read(HttpApiRequest request) {
    return measured(request, false);
  }

  /** Send a single write attempt. The caller must use the permission-guarded tool boundary. */
  public HttpApiResult write(HttpApiRequest request) {
    return measured(request, true);
  }

  private HttpApiResult measured(HttpApiRequest request, boolean write) {
    long started = System.nanoTime();
    metrics.call(write);
    try {
      var result = execute(request, write);
      metrics.result(result);
      return result;
    } finally {
      metrics.duration("tool", System.nanoTime() - started);
    }
  }

  private HttpApiResult execute(HttpApiRequest request, boolean write) {
    var sent = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      if (request == null || request.url() == null) throw new IllegalArgumentException();
      String method = request.method() == null ? "GET" : request.method().toUpperCase(Locale.ROOT);
      if (!(write ? Set.of("POST", "PUT", "PATCH", "DELETE") : Set.of("GET", "HEAD"))
              .contains(method)
          || !write && request.body() != null) throw new IllegalArgumentException();
      if (request.maxItems() != null && (request.maxItems() < 1 || request.maxItems() > 1000))
        throw new IllegalArgumentException();
      if (request.jsonPointer() != null) {
        if (java.util.regex.Pattern.compile("~(?:[^01]|$)").matcher(request.jsonPointer()).find())
          throw new IllegalArgumentException();
        JsonPointer.compile(request.jsonPointer());
      }
      URI uri = buildUri(request);
      PublicNetworkPolicy.validateSyntax(uri);
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("Accept", "application/json");
      if (request.headers() != null)
        request
            .headers()
            .forEach(
                (name, value) -> {
                  if (name == null
                      || !Set.of(
                              "accept",
                              "accept-language",
                              "if-none-match",
                              "if-modified-since",
                              "idempotency-key")
                          .contains(name.toLowerCase(Locale.ROOT))
                      || value == null
                      || value.length() > 4096
                      || value.chars().anyMatch(c -> c < 32 || c == 127))
                    throw new IllegalArgumentException();
                  headers.put(name, value);
                });
      String secret = null;
      if (request.authProfile() != null) {
        var profile = properties.authProfiles().get(request.authProfile());
        if (profile == null) return failure("AUTH_PROFILE_NOT_FOUND", false, false, false);
        if (!sameOrigin(profile.origin(), uri))
          return failure("AUTH_ORIGIN_MISMATCH", false, false, false);
        secret = secrets.apply(profile.secretEnv());
        if (secret == null
            || secret.isBlank()
            || secret.length() > 4096
            || secret.chars().anyMatch(c -> c < 32 || c == 127))
          return failure("AUTH_SECRET_UNAVAILABLE", false, false, false);
        headers.put(
            "bearer".equals(profile.type()) ? "Authorization" : profile.header(),
            "bearer".equals(profile.type()) ? "Bearer " + secret : secret);
      }
      byte[] body =
          request.body() == null
              ? null
              : request.body().toString().getBytes(StandardCharsets.UTF_8);
      if (body != null) {
        if (body.length > properties.maxBytes()) throw new IllegalArgumentException();
        headers.put("Content-Type", "application/json");
      }
      var operation =
          FetchScope.current().withTimeout(Duration.ofMillis(properties.timeoutMillis()));
      for (int hop = 0; ; hop++) {
        operation.check();
        URI current = uri;
        if (!write
            && properties.unsafeReadPrefixes().stream()
                .anyMatch(
                    prefix ->
                        sameOrigin(prefix, current)
                            && (current.getPath().contains("%")
                                || decodedPath(current).startsWith(decodedPath(prefix)))))
          throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
        var observer = metrics.observer(() -> sent.set(true));
        SafeHttpFetcher.Response response;
        long started = System.nanoTime();
        try {
          response =
              fetcher.exchange(
                  uri, method, body, headers, policy(uri), operation, observer, hop > 0);
        } finally {
          metrics.duration("request", System.nanoTime() - started);
        }
        if (response.status() >= 300 && response.status() < 400 && response.status() != 304) {
          if (write)
            return new HttpApiResult(
                response.status(),
                null,
                null,
                null,
                false,
                false,
                "WRITE_REDIRECT_REFUSED",
                false,
                false,
                false,
                response.body().length,
                0);
          if (hop >= 5 || response.header("location") == null)
            throw new HttpFetchException(HttpFetchException.Code.REDIRECT_LIMIT);
          URI next = uri.resolve(response.header("location"));
          if (!sameOrigin(uri, next))
            throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
          uri = next;
          continue;
        }
        var result = decode(request, response, method, secret);
        operation.check();
        return result;
      }
    } catch (CancellationException cancelled) {
      return failure("CANCELLED", false, true, write && sent.get());
    } catch (HttpFetchException failed) {
      return failure(
          failed.code().name(),
          failed.code().name().endsWith("TIMEOUT"),
          false,
          write && sent.get());
    } catch (IllegalArgumentException invalid) {
      return failure("INVALID_INPUT", false, false, false);
    }
  }

  private HttpFetchPolicy policy(URI uri) {
    URI allowed =
        properties.allowedOrigins().stream()
            .filter(origin -> sameOrigin(origin, uri))
            .findFirst()
            .orElse(null);
    return new HttpFetchPolicy(
        properties.maxBytes(),
        properties.maxBytes(),
        Duration.ofSeconds(5),
        Duration.ofMillis(properties.timeoutMillis()),
        Duration.ofMillis(properties.timeoutMillis()),
        5,
        null,
        allowed,
        allowed != null);
  }

  static boolean sameOrigin(URI first, URI second) {
    return first.getScheme() != null
        && second.getScheme() != null
        && first.getHost() != null
        && second.getHost() != null
        && first.getScheme().equalsIgnoreCase(second.getScheme())
        && first.getHost().equalsIgnoreCase(second.getHost())
        && port(first) == port(second);
  }

  private static int port(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static String decodedPath(URI uri) {
    try {
      return new URI(null, null, uri.getPath(), null)
          .normalize()
          .getPath()
          .toLowerCase(Locale.ROOT);
    } catch (java.net.URISyntaxException invalid) {
      throw new IllegalArgumentException();
    }
  }

  private URI buildUri(HttpApiRequest request) {
    URI base = URI.create(request.url());
    if (base.getRawFragment() != null) throw new IllegalArgumentException();
    StringBuilder url = new StringBuilder(base.toASCIIString());
    if (request.query() != null)
      request
          .query()
          .forEach(
              (key, value) -> {
                url.append(url.indexOf("?") < 0 ? '?' : '&')
                    .append(encode(key))
                    .append('=')
                    .append(encode(value));
              });
    return URI.create(url.toString());
  }

  private static String encode(String value) {
    if (value == null) throw new IllegalArgumentException();
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private HttpApiResult decode(
      HttpApiRequest request, SafeHttpFetcher.Response response, String method, String secret) {
    String type = response.header("content-type");
    type = redact(type, secret);
    if (type != null && type.length() > 256) type = type.substring(0, 256);
    String error = response.status() >= 400 ? "HTTP_ERROR" : null;
    JsonNode json = null;
    String text = null;
    boolean extracted = false, truncated = false;
    byte[] bytes = response.body();
    if (bytes.length > 0 && !method.equals("HEAD")) {
      Charset charset = StandardCharsets.UTF_8;
      if (type != null) {
        try {
          var media = org.springframework.http.MediaType.parseMediaType(type);
          if (media.getCharset() != null) charset = media.getCharset();
        } catch (IllegalArgumentException ignored) {
          /* Fall back to UTF-8. */
        }
      }
      text = redact(new String(bytes, charset), secret);
      if (request.jsonPointer() != null
          || type != null && type.toLowerCase(Locale.ROOT).matches(".*(?:/json|\\+json)(?:;.*)?")) {
        try {
          json =
              mapper
                  .reader()
                  .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                  .readTree(text);
          text = null;
          if (json != null && secret != null)
            json = mapper.readTree(redact(json.toString(), secret));
          if (json == null) error = "INVALID_JSON";
          else if (request.jsonPointer() != null) {
            json = json.at(request.jsonPointer());
            if (json.isMissingNode()) {
              json = null;
              error = "POINTER_NOT_FOUND";
            } else extracted = true;
          }
          int limit = request.maxItems() == null ? 100 : request.maxItems();
          if (json != null && json.isArray() && json.size() > limit) {
            var array = mapper.createArrayNode();
            for (int i = 0; i < limit; i++) array.add(json.get(i));
            json = array;
            truncated = true;
          }
        } catch (tools.jackson.core.JacksonException invalid) {
          json = null;
          text = null;
          error = "INVALID_JSON";
        }
      }
    } else if (request.jsonPointer() != null) error = "EMPTY_BODY";
    int output =
        json != null
            ? json.toString().getBytes(StandardCharsets.UTF_8).length
            : text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    return new HttpApiResult(
        response.status(),
        type,
        json,
        text,
        extracted,
        truncated,
        error,
        false,
        false,
        false,
        bytes.length,
        output);
  }

  private String redact(String value, String secret) {
    if (value == null || secret == null) return value;
    // JSON escaping and common URL encoding must not reveal a reflected credential either.
    String escaped = mapper.getNodeFactory().textNode(secret).toString();
    return value
        .replace(escaped.substring(1, escaped.length() - 1), "[REDACTED]")
        .replace(secret, "[REDACTED]")
        .replace(encode(secret), "[REDACTED]");
  }

  private static HttpApiResult failure(
      String error, boolean timedOut, boolean cancelled, boolean unknown) {
    return new HttpApiResult(
        null, null, null, null, false, false, error, timedOut, cancelled, unknown, 0, 0);
  }
}
