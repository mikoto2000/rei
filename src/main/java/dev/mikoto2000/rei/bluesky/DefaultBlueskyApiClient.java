package dev.mikoto2000.rei.bluesky;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class DefaultBlueskyApiClient implements BlueskyApiClient {

  private static final URI CREATE_SESSION_URI = URI.create("https://bsky.social/xrpc/com.atproto.server.createSession");
  private static final URI CREATE_RECORD_URI = URI.create("https://bsky.social/xrpc/com.atproto.repo.createRecord");
  private static final Pattern ACCESS_JWT_PATTERN = Pattern.compile("\"accessJwt\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern DID_PATTERN = Pattern.compile("\"did\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern URI_PATTERN = Pattern.compile("\"uri\"\\s*:\\s*\"([^\"]+)\"");

  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  @Override
  public AuthResult authenticate(String handle, String appPassword) {
    String requestBody = "{\"identifier\":\"" + escapeJson(handle) + "\",\"password\":\"" + escapeJson(appPassword) + "\"}";
    HttpRequest request = HttpRequest.newBuilder(CREATE_SESSION_URI)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();
    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        return new AuthResult(false, null, null);
      }
      String accessJwt = matchFirst(ACCESS_JWT_PATTERN, response.body());
      String did = matchFirst(DID_PATTERN, response.body());
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
        + "\"record\":{\"text\":\"" + escapeJson(text) + "\",\"createdAt\":\"" + OffsetDateTime.now() + "\"}}";
    HttpRequest request = HttpRequest.newBuilder(CREATE_RECORD_URI)
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + accessJwt)
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();
    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        return new PostResult(false, null);
      }
      String postUri = matchFirst(URI_PATTERN, response.body());
      if (postUri == null || postUri.isBlank()) {
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

  private String matchFirst(Pattern pattern, String body) {
    Matcher matcher = pattern.matcher(body);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return null;
  }

  private String escapeJson(String input) {
    return input
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }
}
