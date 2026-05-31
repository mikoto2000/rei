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
  private static final String RESOLVE_HANDLE_ENDPOINT = "https://bsky.social/xrpc/com.atproto.identity.resolveHandle";
  private static final String GET_AUTHOR_FEED_ENDPOINT = "https://bsky.social/xrpc/app.bsky.feed.getAuthorFeed";
  private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
  private static final Pattern HASHTAG_PATTERN = Pattern.compile("(?<!\\S)#([\\p{L}\\p{N}_]+)");

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
    String requestBody = createRecordRequestBody(did, text, OffsetDateTime.now(), null);
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

  @Override
  public String resolveHandle(String handle) {
    URI uri = URI.create(RESOLVE_HANDLE_ENDPOINT + "?handle=" + encode(handle));
    HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        return null;
      }
      JsonNode body = objectMapper.readTree(response.body());
      return textOrNull(body, "did");
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Bluesky resolveHandle request failed", e);
    }
  }

  @Override
  public List<FeedPost> getAuthorFeed(String actorDid, int limit) {
    return getAuthorFeed(actorDid, limit, null);
  }

  @Override
  public List<FeedPost> getAuthorFeed(String actorDid, int limit, String accessJwt) {
    URI uri = URI.create(GET_AUTHOR_FEED_ENDPOINT + "?actor=" + encode(actorDid) + "&limit=" + limit);
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET();
    if (accessJwt != null && !accessJwt.isBlank()) {
      builder.header("Authorization", "Bearer " + accessJwt);
    }
    HttpRequest request = builder.build();
    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        log.warn("Bluesky getAuthorFeed failed: actorDid={}, status={}, body={}", actorDid, response.statusCode(), response.body());
        return List.of();
      }
      JsonNode body = objectMapper.readTree(response.body());
      JsonNode feed = body.get("feed");
      if (feed == null || !feed.isArray()) {
        log.warn("Bluesky getAuthorFeed returned unexpected body: actorDid={}, body={}", actorDid, response.body());
        return List.of();
      }
      List<FeedPost> posts = new ArrayList<>();
      for (JsonNode item : feed) {
        JsonNode post = item.get("post");
        if (post == null || post.isNull()) {
          continue;
        }
        String uriText = textOrNull(post, "uri");
        String cid = textOrNull(post, "cid");
        String indexedAt = textOrNull(post, "indexedAt");
        JsonNode record = post.get("record");
        String text = record != null ? textOrNull(record, "text") : null;
        boolean repost = item.get("reason") != null && !item.get("reason").isNull();
        boolean reply = record != null && record.get("reply") != null && !record.get("reply").isNull();
        String rootUri = null;
        String rootCid = null;
        if (reply) {
          JsonNode root = record.get("reply").get("root");
          if (root != null) {
            rootUri = textOrNull(root, "uri");
            rootCid = textOrNull(root, "cid");
          }
        }
        posts.add(new FeedPost(uriText, cid, text, indexedAt == null ? null : OffsetDateTime.parse(indexedAt), repost, reply, rootUri,
            rootCid));
      }
      return posts;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Bluesky getAuthorFeed request failed", e);
    }
  }

  @Override
  public PostResult createReply(String accessJwt, String did, String text, String parentUri, String parentCid, String rootUri,
      String rootCid) {
    ReplyRef reply = new ReplyRef(parentUri, parentCid, rootUri, rootCid);
    String requestBody = createRecordRequestBody(did, text, OffsetDateTime.now(), reply);
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
      JsonNode body = objectMapper.readTree(response.body());
      String postUri = textOrNull(body, "uri");
      return new PostResult(postUri != null && !postUri.isBlank(), postUri);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Bluesky reply request failed", e);
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
    return createRecordRequestBody(did, text, createdAt, null);
  }

  static String createRecordRequestBody(String did, String text, OffsetDateTime createdAt, ReplyRef replyRef) {
    StringBuilder request = new StringBuilder();
    request.append("{\"repo\":\"").append(escapeJsonStatic(did)).append("\",\"collection\":\"app.bsky.feed.post\",");
    request.append("\"record\":{\"$type\":\"app.bsky.feed.post\",\"text\":\"")
        .append(escapeJsonStatic(text))
        .append("\",\"createdAt\":\"")
        .append(createdAt)
        .append("\"");
    String facets = buildFacetsJson(text);
    if (!facets.isEmpty()) {
      request.append(",\"facets\":").append(facets);
    }
    if (replyRef != null && replyRef.parentUri() != null && replyRef.parentCid() != null) {
      request.append(",\"reply\":{\"root\":{\"uri\":\"")
          .append(escapeJsonStatic(replyRef.rootUri() == null ? replyRef.parentUri() : replyRef.rootUri()))
          .append("\",\"cid\":\"")
          .append(escapeJsonStatic(replyRef.rootCid() == null ? replyRef.parentCid() : replyRef.rootCid()))
          .append("\"},\"parent\":{\"uri\":\"")
          .append(escapeJsonStatic(replyRef.parentUri()))
          .append("\",\"cid\":\"")
          .append(escapeJsonStatic(replyRef.parentCid()))
          .append("\"}}");
    }
    request.append("}}");
    return request.toString();
  }

  static String buildFacetsJson(String text) {
    List<Facet> facets = extractFacets(text);
    if (facets.isEmpty()) {
      return "";
    }
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < facets.size(); i++) {
      Facet facet = facets.get(i);
      if (i > 0) {
        json.append(",");
      }
      json.append("{\"index\":{\"byteStart\":")
          .append(facet.byteStart())
          .append(",\"byteEnd\":")
          .append(facet.byteEnd());
      if (facet.type() == FacetType.LINK) {
        json.append("},\"features\":[{\"$type\":\"app.bsky.richtext.facet#link\",\"uri\":\"")
            .append(escapeJsonStatic(facet.value()))
            .append("\"}]}");
      } else {
        json.append("},\"features\":[{\"$type\":\"app.bsky.richtext.facet#tag\",\"tag\":\"")
            .append(escapeJsonStatic(facet.value()))
            .append("\"}]}");
      }
    }
    json.append("]");
    return json.toString();
  }

  static List<Facet> extractFacets(String text) {
    ArrayList<Facet> facets = new ArrayList<>();
    facets.addAll(extractLinkFacets(text).stream()
        .map(f -> new Facet(f.byteStart(), f.byteEnd(), FacetType.LINK, f.uri()))
        .toList());
    facets.addAll(extractTagFacets(text).stream()
        .map(f -> new Facet(f.byteStart(), f.byteEnd(), FacetType.TAG, f.tag()))
        .toList());
    facets.sort((a, b) -> Integer.compare(a.byteStart(), b.byteStart()));
    return facets;
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

  static List<TagFacet> extractTagFacets(String text) {
    ArrayList<TagFacet> facets = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return facets;
    }
    Matcher matcher = HASHTAG_PATTERN.matcher(text);
    while (matcher.find()) {
      int start = matcher.start();
      int end = matcher.end();
      int byteStart = utf8ByteLength(text.substring(0, start));
      int byteEnd = byteStart + utf8ByteLength(text.substring(start, end));
      facets.add(new TagFacet(byteStart, byteEnd, matcher.group(1)));
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

  record TagFacet(int byteStart, int byteEnd, String tag) {
  }

  private enum FacetType {
    LINK,
    TAG
  }

  record Facet(int byteStart, int byteEnd, FacetType type, String value) {
  }

  record ReplyRef(String parentUri, String parentCid, String rootUri, String rootCid) {
  }
}
