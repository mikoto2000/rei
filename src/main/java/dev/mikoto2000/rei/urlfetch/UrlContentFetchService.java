package dev.mikoto2000.rei.urlfetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UrlContentFetchService {

  private static final int DEFAULT_TIMEOUT_SECONDS = 30;
  private static final int CHARSET_SCAN_BYTES = 4096;
  private static final Pattern CONTENT_TYPE_CHARSET = Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*\"?([^;\\s\"]+)");
  private static final Pattern META_CHARSET = Pattern.compile("(?is)<meta\\b[^>]*\\bcharset\\s*=\\s*['\"]?([^\\s'\"/>;]+)");
  private static final Pattern META_CONTENT_CHARSET = Pattern.compile("(?is)<meta\\b[^>]*\\bcontent\\s*=\\s*['\"][^'\"]*?charset\\s*=\\s*([^\\s'\";]+)");

  private final UrlValidator urlValidator;
  private final HttpClient httpClient;

  @Autowired
  public UrlContentFetchService(UrlValidator urlValidator) {
    this(urlValidator, HttpClient.newHttpClient());
  }

  UrlContentFetchService(UrlValidator urlValidator, HttpClient httpClient) {
    this.urlValidator = urlValidator;
    this.httpClient = httpClient;
  }

  public UrlContentFetchResult fetch(String url) {
    UrlContentFetchResult validation = urlValidator.validate(url);
    if (!validation.success()) {
      return validation;
    }

    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
          .header("Accept", "text/plain,text/html,application/xhtml+xml,application/json")
          .GET()
          .build();
      HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() >= 400) {
        return UrlContentFetchResult.failure(
            "HTTP_ERROR",
            "HTTP request failed with status: " + response.statusCode(),
            response.statusCode());
      }
      if (response.body() == null) {
        return UrlContentFetchResult.failure("EXTRACTION_ERROR", "Response body is empty");
      }
      String rawContentType = response.headers() == null ? null : response.headers().firstValue("Content-Type")
          .orElse(null);
      String contentType = normalizeContentType(rawContentType);
      Charset charset = charset(rawContentType, response.body());
      return UrlContentFetchResult.success(new String(response.body(), charset), contentType);
    } catch (IOException e) {
      return UrlContentFetchResult.failure("NETWORK_ERROR", "Network error: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return UrlContentFetchResult.failure("NETWORK_ERROR", "Request interrupted");
    } catch (RuntimeException e) {
      return UrlContentFetchResult.failure("EXTRACTION_ERROR", "Failed to fetch URL content: " + e.getMessage());
    }
  }

  private String normalizeContentType(String rawContentType) {
    if (rawContentType == null || rawContentType.isBlank()) {
      return null;
    }
    return rawContentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
  }

  private Charset charset(String rawContentType, byte[] body) {
    return charsetFromContentType(rawContentType)
        .or(() -> charsetFromHtml(body))
        .orElse(StandardCharsets.UTF_8);
  }

  private Optional<Charset> charsetFromContentType(String rawContentType) {
    if (rawContentType == null || rawContentType.isBlank()) {
      return Optional.empty();
    }
    Matcher matcher = CONTENT_TYPE_CHARSET.matcher(rawContentType);
    if (!matcher.find()) {
      return Optional.empty();
    }
    return charsetByName(matcher.group(1));
  }

  private Optional<Charset> charsetFromHtml(byte[] body) {
    if (body == null || body.length == 0) {
      return Optional.empty();
    }
    String head = new String(body, 0, Math.min(body.length, CHARSET_SCAN_BYTES), StandardCharsets.ISO_8859_1);
    Optional<Charset> metaCharset = charsetFrom(head, META_CHARSET);
    return metaCharset.isPresent() ? metaCharset : charsetFrom(head, META_CONTENT_CHARSET);
  }

  private Optional<Charset> charsetFrom(String value, Pattern pattern) {
    Matcher matcher = pattern.matcher(value);
    if (!matcher.find()) {
      return Optional.empty();
    }
    return charsetByName(matcher.group(1));
  }

  private Optional<Charset> charsetByName(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Charset.forName(name.trim()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
