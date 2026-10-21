package dev.mikoto2000.rei.bluesky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 既定BlueskyAPIを外部の接続先へ依頼する窓口。 */
@Component
public class DefaultBlueskyApiClient implements BlueskyApiClient {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(DefaultBlueskyApiClient.class);

  /** 作成会話URI。 */
  private static final URI CREATE_SESSION_URI =
      URI.create("https://bsky.social/xrpc/com.atproto.server.createSession");

  /** 作成記録URI。 */
  private static final URI CREATE_RECORD_URI =
      URI.create("https://bsky.social/xrpc/com.atproto.repo.createRecord");

  /** 取得記録接続先。 */
  private static final String GET_RECORD_ENDPOINT =
      "https://bsky.social/xrpc/com.atproto.repo.getRecord";

  /** 対象の解決操作対象接続先。 */
  private static final String RESOLVE_HANDLE_ENDPOINT =
      "https://bsky.social/xrpc/com.atproto.identity.resolveHandle";

  /** 取得著者フィード接続先。 */
  private static final String GET_AUTHOR_FEED_ENDPOINT =
      "https://bsky.social/xrpc/app.bsky.feed.getAuthorFeed";

  /** URL照合規則。 */
  private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

  /** HASHTAG照合規則。 */
  private static final Pattern HASHTAG_PATTERN =
      Pattern.compile("(?<![A-Za-z0-9_])#([\\p{L}\\p{N}_]+)");

  /** 宛先の指定照合規則。 */
  private static final Pattern MENTION_PATTERN =
      Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)");

  /** BSKY投稿URL照合規則。 */
  private static final Pattern BSKY_POST_URL_PATTERN =
      Pattern.compile("^https://bsky\\.app/profile/([^/]+)/post/([^/?#]+).*$");

  /** この機能の動作設定。 */
  private final BlueskyProperties properties;

  /** 外部情報を取得するHTTP通信の窓口。 */
  private final HttpClient httpClient;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper objectMapper = new ObjectMapper();

  /** Blueskyとの連携を利用するため、指定を補って共通の初期化へ引き渡す。 */
  DefaultBlueskyApiClient() {
    this(new BlueskyProperties());
  }

  /** 指定された値や利用先を使って、DefaultBlueskyApiClientを初期化する。 */
  @Autowired
  DefaultBlueskyApiClient(BlueskyProperties properties) {
    this.properties = properties;
    this.httpClient = HttpClient.newBuilder().connectTimeout(requestTimeout()).build();
  }

  /** Blueskyとの連携で用いる認証を返す。 */
  @Override
  public AuthResult authenticate(String handle, String appPassword) {
    String requestBody =
        "{\"identifier\":\""
            + escapeJson(handle)
            + "\",\"password\":\""
            + escapeJson(appPassword)
            + "\"}";
    HttpRequest request =
        requestBuilder(CREATE_SESSION_URI)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      log.debug(
          "Bluesky createSession response: status={}, body={}",
          response.statusCode(),
          maskSessionResponse(response.body()));
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
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Bluesky authentication request failed", e);
    }
  }

  /** 新たな対象を利用できるよう、投稿を作成する。 */
  @Override
  public PostResult createPost(String accessJwt, String did, String text) {
    String requestBody =
        createRecordRequestBody(did, text, OffsetDateTime.now(), null, this::resolveMentionHandle);
    HttpRequest request =
        requestBuilder(CREATE_RECORD_URI)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + accessJwt)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      log.debug(
          "Bluesky createRecord response: status={}, body={}",
          response.statusCode(),
          response.body());
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
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Bluesky post request failed", e);
    }
  }

  /** 指定や設定に応じた対象を利用するため、操作対象を確定する。 */
  @Override
  public String resolveHandle(String handle) {
    URI uri = URI.create(RESOLVE_HANDLE_ENDPOINT + "?handle=" + encode(handle));
    HttpRequest request = requestBuilder(uri).GET().build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        return null;
      }
      JsonNode body = objectMapper.readTree(response.body());
      return textOrNull(body, "did");
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Bluesky resolveHandle request failed", e);
    }
  }

  /** Blueskyとの連携で参照する著者フィードを返す。 */
  @Override
  public List<FeedPost> getAuthorFeed(String actorDid, int limit) {
    return getAuthorFeed(actorDid, limit, null);
  }

  /** Blueskyとの連携で参照する著者フィードを返す。 */
  @Override
  public List<FeedPost> getAuthorFeed(String actorDid, int limit, String accessJwt) {
    URI uri =
        URI.create(GET_AUTHOR_FEED_ENDPOINT + "?actor=" + encode(actorDid) + "&limit=" + limit);
    HttpRequest.Builder builder = requestBuilder(uri).GET();
    if (accessJwt != null && !accessJwt.isBlank()) {
      builder.header("Authorization", "Bearer " + accessJwt);
    }
    HttpRequest request = builder.build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        log.warn(
            "Bluesky getAuthorFeed failed: actorDid={}, status={}, body={}",
            actorDid,
            response.statusCode(),
            response.body());
        return List.of();
      }
      JsonNode body = objectMapper.readTree(response.body());
      JsonNode feed = body.get("feed");
      if (feed == null || !feed.isArray()) {
        log.warn(
            "Bluesky getAuthorFeed returned unexpected body: actorDid={}, body={}",
            actorDid,
            response.body());
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
        boolean reply =
            record != null && record.get("reply") != null && !record.get("reply").isNull();
        String rootUri = null;
        String rootCid = null;
        if (reply) {
          JsonNode root = record.get("reply").get("root");
          if (root != null) {
            rootUri = textOrNull(root, "uri");
            rootCid = textOrNull(root, "cid");
          }
        }
        posts.add(
            new FeedPost(
                uriText,
                cid,
                text,
                indexedAt == null ? null : OffsetDateTime.parse(indexedAt),
                repost,
                reply,
                rootUri,
                rootCid));
      }
      return posts;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Bluesky getAuthorFeed request failed", e);
    }
  }

  /** 新たな対象を利用できるよう、返信を作成する。 */
  @Override
  public PostResult createReply(
      String accessJwt,
      String did,
      String text,
      String parentUri,
      String parentCid,
      String rootUri,
      String rootCid) {
    ReplyRef reply = new ReplyRef(parentUri, parentCid, rootUri, rootCid);
    String requestBody =
        createRecordRequestBody(did, text, OffsetDateTime.now(), reply, this::resolveMentionHandle);
    HttpRequest request =
        requestBuilder(CREATE_RECORD_URI)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + accessJwt)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        return new PostResult(false, null);
      }
      JsonNode body = objectMapper.readTree(response.body());
      String postUri = textOrNull(body, "uri");
      return new PostResult(postUri != null && !postUri.isBlank(), postUri);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Bluesky reply request failed", e);
    }
  }

  /** 指定や設定に応じた対象を利用するため、返信対象を確定する。 */
  @Override
  public ReplyTarget resolveReplyTarget(String accessJwt, String targetPostUriOrUrl) {
    PostRef postRef = toPostRef(targetPostUriOrUrl);
    URI getRecordUri =
        URI.create(
            GET_RECORD_ENDPOINT
                + "?repo="
                + encode(postRef.repo())
                + "&collection="
                + encode(postRef.collection())
                + "&rkey="
                + encode(postRef.rkey()));
    HttpRequest request =
        requestBuilder(getRecordUri).header("Authorization", "Bearer " + accessJwt).GET().build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        log.warn(
            "Bluesky resolveReplyTarget failed: target={}, status={}, body={}",
            targetPostUriOrUrl,
            response.statusCode(),
            response.body());
        return null;
      }
      JsonNode body = objectMapper.readTree(response.body());
      String parentUri = textOrNull(body, "uri");
      String parentCid = textOrNull(body, "cid");
      if (parentUri == null || parentCid == null) {
        return null;
      }
      JsonNode value = body.get("value");
      String targetText = value != null ? textOrNull(value, "text") : null;
      String rootUri = parentUri;
      String rootCid = parentCid;
      if (value != null && value.get("reply") != null && !value.get("reply").isNull()) {
        JsonNode root = value.get("reply").get("root");
        if (root != null) {
          rootUri = textOrNull(root, "uri") == null ? parentUri : textOrNull(root, "uri");
          rootCid = textOrNull(root, "cid") == null ? parentCid : textOrNull(root, "cid");
        }
      }
      return new ReplyTarget(parentUri, parentCid, rootUri, rootCid, targetText);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Bluesky resolveReplyTarget request failed", e);
    }
  }

  /** Blueskyとの連携において、存在記録に該当するか判定する。 */
  private boolean existsRecord(String accessJwt, String did, String postUri)
      throws IOException, InterruptedException {
    String[] parts = postUri.split("/");
    if (parts.length < 5) {
      return false;
    }
    String collection = parts[3];
    String rkey = parts[4];
    URI getRecordUri =
        URI.create(
            GET_RECORD_ENDPOINT
                + "?repo="
                + encode(did)
                + "&collection="
                + encode(collection)
                + "&rkey="
                + encode(rkey));
    HttpRequest request =
        requestBuilder(getRecordUri).header("Authorization", "Bearer " + accessJwt).GET().build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    log.debug(
        "Bluesky getRecord response: status={}, body={}", response.statusCode(), response.body());
    if (response.statusCode() / 100 != 2) {
      return false;
    }
    JsonNode body = objectMapper.readTree(response.body());
    String uri = textOrNull(body, "uri");
    return postUri.equals(uri);
  }

  /** Blueskyとの連携で用いる非公開化会話応答を返す。 */
  String maskSessionResponse(String responseBody) {
    try {
      JsonNode body = objectMapper.readTree(responseBody);
      if (!(body instanceof ObjectNode object)) {
        return "[REDACTED]";
      }
      if (object.has("accessJwt")) {
        object.put("accessJwt", "[REDACTED]");
      }
      if (object.has("refreshJwt")) {
        object.put("refreshJwt", "[REDACTED]");
      }
      return objectMapper.writeValueAsString(object);
    } catch (IOException e) {
      // Never fall back to logging an unparsed authentication response.
      return "[REDACTED]";
    }
  }

  /** Blueskyとの連携で用いる要求組み立てを返す。 */
  private HttpRequest.Builder requestBuilder(URI uri) {
    return HttpRequest.newBuilder(uri).timeout(requestTimeout());
  }

  /** Blueskyとの連携で用いる要求タイムアウトを返す。 */
  private Duration requestTimeout() {
    return Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds()));
  }

  /** Blueskyとの連携で用いるテキストまたは未指定を返す。 */
  private String textOrNull(JsonNode node, String fieldName) {
    JsonNode field = node.get(fieldName);
    if (field == null || field.isNull()) {
      return null;
    }
    String text = field.asText();
    return text == null || text.isBlank() ? null : text;
  }

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** Blueskyとの連携で用いる特殊文字の保護JSON変換を返す。 */
  private String escapeJson(String input) {
    return input
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  /** 新たな対象を利用できるよう、記録要求の本文を作成する。 */
  static String createRecordRequestBody(String did, String text, OffsetDateTime createdAt) {
    return createRecordRequestBody(did, text, createdAt, null);
  }

  /** 新たな対象を利用できるよう、記録要求の本文を作成する。 */
  static String createRecordRequestBody(
      String did, String text, OffsetDateTime createdAt, ReplyRef replyRef) {
    return createRecordRequestBody(did, text, createdAt, replyRef, handle -> null);
  }

  /** 新たな対象を利用できるよう、記録要求の本文を作成する。 */
  static String createRecordRequestBody(
      String did,
      String text,
      OffsetDateTime createdAt,
      ReplyRef replyRef,
      Function<String, String> mentionResolver) {
    StringBuilder request = new StringBuilder();
    request
        .append("{\"repo\":\"")
        .append(escapeJsonStatic(did))
        .append("\",\"collection\":\"app.bsky.feed.post\",");
    request
        .append("\"record\":{\"$type\":\"app.bsky.feed.post\",\"text\":\"")
        .append(escapeJsonStatic(text))
        .append("\",\"createdAt\":\"")
        .append(createdAt)
        .append("\"");
    String facets = buildFacetsJson(text, mentionResolver);
    if (!facets.isEmpty()) {
      request.append(",\"facets\":").append(facets);
    }
    if (replyRef != null && replyRef.parentUri() != null && replyRef.parentCid() != null) {
      request
          .append(",\"reply\":{\"root\":{\"uri\":\"")
          .append(
              escapeJsonStatic(
                  replyRef.rootUri() == null ? replyRef.parentUri() : replyRef.rootUri()))
          .append("\",\"cid\":\"")
          .append(
              escapeJsonStatic(
                  replyRef.rootCid() == null ? replyRef.parentCid() : replyRef.rootCid()))
          .append("\"},\"parent\":{\"uri\":\"")
          .append(escapeJsonStatic(replyRef.parentUri()))
          .append("\",\"cid\":\"")
          .append(escapeJsonStatic(replyRef.parentCid()))
          .append("\"}}");
    }
    request.append("}}");
    return request.toString();
  }

  /** 利用先に渡せるよう、FacetsJSON変換を組み立てる。 */
  static String buildFacetsJson(String text) {
    return buildFacetsJson(text, handle -> null);
  }

  /** 利用先に渡せるよう、FacetsJSON変換を組み立てる。 */
  static String buildFacetsJson(String text, Function<String, String> mentionResolver) {
    List<Facet> facets = extractFacets(text, mentionResolver);
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
      } else if (facet.type() == FacetType.TAG) {
        json.append("},\"features\":[{\"$type\":\"app.bsky.richtext.facet#tag\",\"tag\":\"")
            .append(escapeJsonStatic(facet.value()))
            .append("\"}]}");
      } else {
        json.append("},\"features\":[{\"$type\":\"app.bsky.richtext.facet#mention\",\"did\":\"")
            .append(escapeJsonStatic(facet.value()))
            .append("\"}]}");
      }
    }
    json.append("]");
    return json.toString();
  }

  /** 後続の判断に必要な情報を得るため、Facetsを抽出する。 */
  static List<Facet> extractFacets(String text) {
    return extractFacets(text, handle -> null);
  }

  /** 後続の判断に必要な情報を得るため、Facetsを抽出する。 */
  static List<Facet> extractFacets(String text, Function<String, String> mentionResolver) {
    ArrayList<Facet> facets = new ArrayList<>();
    facets.addAll(
        extractLinkFacets(text).stream()
            .map(f -> new Facet(f.byteStart(), f.byteEnd(), FacetType.LINK, f.uri()))
            .toList());
    facets.addAll(
        extractTagFacets(text).stream()
            .map(f -> new Facet(f.byteStart(), f.byteEnd(), FacetType.TAG, f.tag()))
            .toList());
    facets.addAll(
        extractMentionFacets(text, mentionResolver).stream()
            .map(f -> new Facet(f.byteStart(), f.byteEnd(), FacetType.MENTION, f.did()))
            .toList());
    facets.sort((a, b) -> Integer.compare(a.byteStart(), b.byteStart()));
    return facets;
  }

  /** 後続の判断に必要な情報を得るため、リンクFacetsを抽出する。 */
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

  /** 後続の判断に必要な情報を得るため、タグFacetsを抽出する。 */
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

  /** 後続の判断に必要な情報を得るため、宛先の指定Facetsを抽出する。 */
  static List<MentionFacet> extractMentionFacets(
      String text, Function<String, String> mentionResolver) {
    ArrayList<MentionFacet> facets = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return facets;
    }
    Matcher matcher = MENTION_PATTERN.matcher(text);
    while (matcher.find()) {
      String handle = matcher.group(1);
      String did = mentionResolver.apply(handle);
      if (did == null || did.isBlank()) {
        continue;
      }
      int start = matcher.start();
      int end = matcher.end();
      int byteStart = utf8ByteLength(text.substring(0, start));
      int byteEnd = byteStart + utf8ByteLength(text.substring(start, end));
      facets.add(new MentionFacet(byteStart, byteEnd, handle, did));
    }
    return facets;
  }

  /** 指定や設定に応じた対象を利用するため、宛先の指定操作対象を確定する。 */
  private String resolveMentionHandle(String handle) {
    try {
      return resolveHandle(handle);
    } catch (RuntimeException e) {
      log.warn(
          "Bluesky mention handle resolution failed: handle={}, message={}",
          handle,
          e.getMessage());
      return null;
    }
  }

  /** Blueskyとの連携で用いる余白の除去TrailingPunctuationを返す。 */
  private static int trimTrailingPunctuation(String url) {
    int end = url.length();
    while (end > 0) {
      char c = url.charAt(end - 1);
      if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' || c == ')'
          || c == ']') {
        end--;
      } else {
        break;
      }
    }
    return end;
  }

  /** Blueskyとの連携で用いるUTF8バイト長さを返す。 */
  private static int utf8ByteLength(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  /** Blueskyとの連携で用いる特殊文字の保護JSON変換Staticを返す。 */
  private static String escapeJsonStatic(String input) {
    return input
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  /**
   * Blueskyとの連携で受け渡すリンクFacetを表す。
   *
   * @param byteStart バイト開始
   * @param byteEnd バイト終了
   * @param uri 参照先のURI
   */
  record LinkFacet(int byteStart, int byteEnd, String uri) {}

  /**
   * Blueskyとの連携で受け渡すタグFacetを表す。
   *
   * @param byteStart バイト開始
   * @param byteEnd バイト終了
   * @param tag タグ
   */
  record TagFacet(int byteStart, int byteEnd, String tag) {}

  /**
   * Blueskyとの連携で受け渡す宛先の指定Facetを表す。
   *
   * @param byteStart バイト開始
   * @param byteEnd バイト終了
   * @param handle 操作対象
   * @param did 分散識別子
   */
  record MentionFacet(int byteStart, int byteEnd, String handle, String did) {}

  /** Blueskyとの連携におけるFacet種別の選択肢。 */
  private enum FacetType {
    /** リンク。 */
    LINK,
    /** タグ。 */
    TAG,
    /** 宛先の指定。 */
    MENTION
  }

  /**
   * Blueskyとの連携で受け渡すFacetを表す。
   *
   * @param byteStart バイト開始
   * @param byteEnd バイト終了
   * @param type 種別
   * @param value 値
   */
  record Facet(int byteStart, int byteEnd, FacetType type, String value) {}

  /**
   * Blueskyとの連携で受け渡す返信参照先を表す。
   *
   * @param parentUri 親URI
   * @param parentCid 親内容の識別子
   * @param rootUri 作業ルートURI
   * @param rootCid 作業ルート内容の識別子
   */
  record ReplyRef(String parentUri, String parentCid, String rootUri, String rootCid) {}

  /** 利用先へ渡せるよう、投稿参照先の形式に変換する。 */
  private PostRef toPostRef(String targetPostUriOrUrl) {
    if (targetPostUriOrUrl == null || targetPostUriOrUrl.isBlank()) {
      throw new IllegalArgumentException("target post is blank");
    }
    String value = targetPostUriOrUrl.strip();
    if (value.startsWith("at://")) {
      String[] parts = value.split("/");
      if (parts.length < 5) {
        throw new IllegalArgumentException("invalid at-uri: " + value);
      }
      return new PostRef(parts[2], parts[3], parts[4]);
    }
    String lower = value.toLowerCase(Locale.ROOT);
    if (lower.startsWith("https://bsky.app/profile/")) {
      Matcher matcher = BSKY_POST_URL_PATTERN.matcher(value);
      if (!matcher.matches()) {
        throw new IllegalArgumentException("invalid bsky post url: " + value);
      }
      return new PostRef(matcher.group(1), "app.bsky.feed.post", matcher.group(2));
    }
    throw new IllegalArgumentException("unsupported post target format: " + value);
  }

  /**
   * Blueskyとの連携で受け渡す投稿参照先を表す。
   *
   * @param repo リポジトリ
   * @param collection 集約対象
   * @param rkey 投稿の識別キー
   */
  record PostRef(String repo, String collection, String rkey) {}
}
