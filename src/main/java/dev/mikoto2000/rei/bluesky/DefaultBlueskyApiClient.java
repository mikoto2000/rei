package dev.mikoto2000.rei.bluesky;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class DefaultBlueskyApiClient implements BlueskyApiClient {
  private static final Logger log = LoggerFactory.getLogger(DefaultBlueskyApiClient.class);

  private static final URI CREATE_SESSION_URI = URI.create("https://bsky.social/xrpc/com.atproto.server.createSession");
  private static final URI CREATE_RECORD_URI = URI.create("https://bsky.social/xrpc/com.atproto.repo.createRecord");
  private static final String GET_RECORD_ENDPOINT = "https://bsky.social/xrpc/com.atproto.repo.getRecord";
  private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public AuthResult authenticate(String handle, String appPassword) {
    String requestBody = "{\"identifier\":\"" + escapeJson(handle) + "\",\"password\":\"" + escapeJson(appPassword) + "\"}";
    HttpRequest request = HttpRequest.newBuilder(CREATE_SESSION_URI)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();
    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      log.debug("Bluesky createSession response: status={}, body={}", response.statusCode(), response.body());
      if (response.statusCode() / 100 != 2) {
        return new AuthResult(false, null, null);
      }
      JsonNode body = objectMapper.readTree(response.body());
      String accessJwt = textOrNull(body, "accessJwt");
      String did = textOrNull(body, "did");
      if (accessJwt == null || did == null) {
        return new AuthResult(false, null, null);
      }
      return new AuthResult(true, accessJwt, did);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Bluesky authentication request failed", e);
    }
  }

  @Override
  public PostResult createPost(String accessJwt, String did, String text) {
    String requestBody = createRecordRequestBody(did, text, OffsetDateTime.now());
    HttpRequest request = HttpRequest.newBuilder(CREATE_RECORD_URI)
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + accessJwt)
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();
    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      log.debug("Bluesky createRecord response: status={}, body={}", response.statusCode(), response.body());
      if (response.statusCode() / 100 != 2) {
        return new PostResult(false, null);
      }
      JsonNode body = objectMapper.readTree(response.body());
      String postUri = textOrNull(body, "uri");
      if (postUri == null || postUri.isBlank()) {
        return new PostResult(false, null);
      }
      if (!existsRecord(accessJwt, did, postUri)) {
        return new PostResult(false, null);
      }
      return new PostResult(true, postUri);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Bluesky post request failed", e);
    }
  }

  private boolean existsRecord(String accessJwt, String did, String postUri) throws IOException, InterruptedException {
    String[] parts = postUri.split("/");
    if (parts.length < 5) {
      return false;
    }
    String collection = parts[3];
    String rkey = parts[4];
    URI getRecordUri = URI.create(GET_RECORD_ENDPOINT
        + "?repo=" + encode(did)
        + "&collection=" + encode(collection)
        + "&rkey=" + encode(rkey));
    HttpRequest request = HttpRequest.newBuilder(getRecordUri)
        .header("Authorization", "Bearer " + accessJwt)
        .GET()
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    log.debug("Bluesky getRecord response: status={}, body={}", response.statusCode(), response.body());
    if (response.statusCode() / 100 != 2) {
      return false;
    }
    JsonNode body = objectMapper.readTree(response.body());
    String uri = textOrNull(body, "uri");
    return postUri.equals(uri);
  }

  private String textOrNull(JsonNode node, String fieldName) {
    JsonNode field = node.get(fieldName);
    if (field == null || field.isNull()) {
      return null;
    }
    String text = field.asText();
    return text == null || text.isBlank() ? null : text;
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String escapeJson(String input) {
    return input
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  static String createRecordRequestBody(String did, String text, OffsetDateTime createdAt) {
    StringBuilder request = new StringBuilder();
    request.append("{\"repo\":\"").append(escapeJsonStatic(did)).append("\",\"collection\":\"app.bsky.feed.post\",");
    request.append("\"record\":{\"$type\":\"app.bsky.feed.post\",\"text\":\"")
        .append(escapeJsonStatic(text))
        .append("\",\"createdAt\":\"")
        .append(createdAt)
        .append("\"");
    String facets = buildLinkFacetsJson(text);
    if (!facets.isEmpty()) {
      request.append(",\"facets\":").append(facets);
    }
    request.append("}}");
    return request.toString();
  }

  static String buildLinkFacetsJson(String text) {
    List<LinkFacet> facets = extractLinkFacets(text);
    if (facets.isEmpty()) {
      return "";
    }
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < facets.size(); i++) {
      LinkFacet facet = facets.get(i);
      if (i > 0) {
        json.append(",");
      }
      json.append("{\"index\":{\"byteStart\":")
          .append(facet.byteStart())
          .append(",\"byteEnd\":")
          .append(facet.byteEnd())
          .append("},\"features\":[{\"$type\":\"app.bsky.richtext.facet#link\",\"uri\":\"")
          .append(escapeJsonStatic(facet.uri()))
          .append("\"}]}");
    }
    json.append("]");
    return json.toString();
  }

  static List<LinkFacet> extractLinkFacets(String text) {
    ArrayList<LinkFacet> facets = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return facets;
    }
    Matcher matcher = URL_PATTERN.matcher(text);
    while (matcher.find()) {
      int start = matcher.start();
      int end = matcher.end();
      String url = text.substring(start, end);
      int trimmedEnd = trimTrailingPunctuation(url);
      if (trimmedEnd <= 0) {
        continue;
      }
      url = url.substring(0, trimmedEnd);
      end = start + trimmedEnd;
      int byteStart = utf8ByteLength(text.substring(0, start));
      int byteEnd = byteStart + utf8ByteLength(text.substring(start, end));
      facets.add(new LinkFacet(byteStart, byteEnd, url));
    }
    return facets;
  }

  private static int trimTrailingPunctuation(String url) {
    int end = url.length();
    while (end > 0) {
      char c = url.charAt(end - 1);
      if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' || c == ')' || c == ']') {
        end--;
      } else {
        break;
      }
    }
    return end;
  }

  private static int utf8ByteLength(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private static String escapeJsonStatic(String input) {
    return input
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  record LinkFacet(int byteStart, int byteEnd, String uri) {
  }
}
