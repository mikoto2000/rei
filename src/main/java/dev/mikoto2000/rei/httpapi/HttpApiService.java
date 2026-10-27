package dev.mikoto2000.rei.httpapi;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.http.*;
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

/** REST semantics over the existing DNS-pinned, bounded HTTP transport. */
@Service
@EnableConfigurationProperties(HttpApiProperties.class)
public class HttpApiService {
  private final SafeHttpFetcher fetcher;
  private final HttpApiProperties properties;
  private final ObjectMapper mapper = new ObjectMapper();

  /** Compose the shared transport with host-only policy. */
  public HttpApiService(SafeHttpFetcher fetcher, HttpApiProperties properties) {
    this.fetcher = fetcher;
    this.properties = properties;
  }

  /** Perform an API read. */
  public HttpApiResult read(HttpApiRequest request) { return execute(request, false); }

  private HttpApiResult execute(HttpApiRequest request, boolean write) {
    try {
      if (request == null) throw new IllegalArgumentException();
      String method = request.method() == null ? "GET" : request.method().toUpperCase(Locale.ROOT);
      if (!Set.of("GET", "HEAD").contains(method) || request.body() != null || write)
        throw new IllegalArgumentException();
      if (request.maxItems() != null && (request.maxItems() < 1 || request.maxItems() > 1000))
        throw new IllegalArgumentException();
      if (request.jsonPointer() != null) {
        if (request.jsonPointer().matches(".*~(?:[^01]|$).*")) throw new IllegalArgumentException();
        JsonPointer.compile(request.jsonPointer());
      }
      URI uri = buildUri(request);
      PublicNetworkPolicy.validateSyntax(uri);
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("Accept", "application/json");
      if (request.headers() != null) request.headers().forEach((name, value) -> {
        if (!Set.of("accept", "accept-language", "if-none-match", "if-modified-since", "idempotency-key")
            .contains(name.toLowerCase(Locale.ROOT)) || value == null || value.length() > 4096
            || value.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException();
        headers.put(name, value);
      });
      if (request.authProfile() != null) throw new IllegalArgumentException();
      var operation = FetchScope.current().withTimeout(Duration.ofMillis(properties.timeoutMillis()));
      for (int hop = 0; ; hop++) {
        operation.check();
        URI current = uri;
        if (properties.unsafeReadPrefixes().stream().anyMatch(prefix -> sameOrigin(prefix, current)
            && current.normalize().getPath().startsWith(prefix.normalize().getPath())))
          throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
        var response = fetcher.exchange(uri, method, null, headers, policy(uri), operation, HttpFetchObserver.NONE, hop > 0);
        if (response.status() >= 300 && response.status() < 400 && response.status() != 304) {
          if (hop >= 5 || response.header("location") == null)
            throw new HttpFetchException(HttpFetchException.Code.REDIRECT_LIMIT);
          URI next = uri.resolve(response.header("location"));
          if (!sameOrigin(uri, next)) throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
          uri = next;
          continue;
        }
        return decode(request, response, method);
      }
    } catch (CancellationException cancelled) {
      return failure("CANCELLED", false, true);
    } catch (HttpFetchException failed) {
      return failure(failed.code().name(), failed.code().name().endsWith("TIMEOUT"), false);
    } catch (IllegalArgumentException invalid) {
      return failure("INVALID_INPUT", false, false);
    }
  }

  private HttpFetchPolicy policy(URI uri) {
    URI allowed = properties.allowedOrigins().stream().filter(origin -> sameOrigin(origin, uri)).findFirst().orElse(null);
    return new HttpFetchPolicy(properties.maxBytes(), properties.maxBytes(), Duration.ofSeconds(5),
        Duration.ofMillis(properties.timeoutMillis()), Duration.ofMillis(properties.timeoutMillis()), 5, null, allowed, allowed != null);
  }

  static boolean sameOrigin(URI first, URI second) {
    return first.getScheme() != null && second.getScheme() != null && first.getHost() != null && second.getHost() != null
        && first.getScheme().equalsIgnoreCase(second.getScheme()) && first.getHost().equalsIgnoreCase(second.getHost())
        && port(first) == port(second);
  }

  private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }

  private URI buildUri(HttpApiRequest request) {
    URI base = URI.create(request.url());
    if (base.getRawFragment() != null) throw new IllegalArgumentException();
    StringBuilder url = new StringBuilder(base.toASCIIString());
    if (request.query() != null) request.query().forEach((key, value) -> {
      url.append(url.indexOf("?") < 0 ? '?' : '&').append(encode(key)).append('=').append(encode(value));
    });
    return URI.create(url.toString());
  }

  private static String encode(String value) {
    if (value == null) throw new IllegalArgumentException();
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private HttpApiResult decode(HttpApiRequest request, SafeHttpFetcher.Response response, String method) {
    String type = response.header("content-type");
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
        } catch (IllegalArgumentException ignored) { /* Fall back to UTF-8. */ }
      }
      text = new String(bytes, charset);
      if (request.jsonPointer() != null || type != null && type.toLowerCase(Locale.ROOT).matches(".*(?:/json|\\+json)(?:;.*)?")) {
        try {
          json = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
          text = null;
          if (json == null) error = "INVALID_JSON";
          else if (request.jsonPointer() != null) {
            json = json.at(request.jsonPointer());
            if (json.isMissingNode()) { json = null; error = "POINTER_NOT_FOUND"; }
            else extracted = true;
          }
          int limit = request.maxItems() == null ? 100 : request.maxItems();
          if (json != null && json.isArray() && json.size() > limit) {
            var array = mapper.createArrayNode();
            for (int i = 0; i < limit; i++) array.add(json.get(i));
            json = array;
            truncated = true;
          }
        } catch (java.io.IOException invalid) { json = null; text = null; error = "INVALID_JSON"; }
      }
    } else if (request.jsonPointer() != null) error = "EMPTY_BODY";
    int output = json != null ? json.toString().getBytes(StandardCharsets.UTF_8).length
        : text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    return new HttpApiResult(response.status(), type, json, text, extracted, truncated, error, false, false, false, bytes.length, output);
  }

  private static HttpApiResult failure(String error, boolean timedOut, boolean cancelled) {
    return new HttpApiResult(null, null, null, null, false, false, error, timedOut, cancelled, false, 0, 0);
  }
}
