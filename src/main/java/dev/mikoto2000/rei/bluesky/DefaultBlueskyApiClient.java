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
    String requestBody = "{\"repo\":\"" + escapeJson(did) + "\",\"collection\":\"app.bsky.feed.post\","
        + "\"record\":{\"$type\":\"app.bsky.feed.post\",\"text\":\"" + escapeJson(text) + "\",\"createdAt\":\"" + OffsetDateTime.now() + "\"}}";
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
}
