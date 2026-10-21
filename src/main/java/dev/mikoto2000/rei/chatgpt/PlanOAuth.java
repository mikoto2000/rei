package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.AUTH_INVALID;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.AUTH_REQUIRED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.CONSENT_REQUIRED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.PROTOCOL;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.RECEIVE_LIMIT;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.TRANSPORT;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * ChatGPTとの連携に必要な計画O認証を扱う。
 *
 * <p>Public-client authorization. No credentials are read from other applications.
 */
public final class PlanOAuth {
  /** 補助資料。 */
  public static final String RESOURCE = "https://api.openai.com/v1";

  /** トークン。 */
  public static final URI TOKEN = URI.create("https://auth.openai.com/api/accounts/oauth/token");

  /** 利用先の検出。 */
  private static final URI DISCOVERY =
      URI.create("https://auth.openai.com/.well-known/openid-configuration");

  /** ChatGPTとの連携に必要な識別情報確認の窓口を扱う。 */
  public interface IdentityVerifier {
    /** 要求された条件を満たすことを確認する。 */
    Identity verify(String idToken, String clientId, String nonce);
  }

  /**
   * ChatGPTとの連携で受け渡す識別情報を表す。
   *
   * @param subject 対象
   * @param email メールアドレス
   */
  public record Identity(String subject, String email) {}

  /** ChatGPTとの連携に必要な開始AI識別情報確認の窓口を扱う。 */
  public static final class OpenAiIdentityVerifier implements IdentityVerifier {
    /** 受信形式の解釈。 */
    private final NimbusJwtDecoder decoder;

    /** ChatGPTとの連携を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public OpenAiIdentityVerifier() {
      this(NimbusJwtDecoder.withJwkSetUri("https://auth.openai.com/.well-known/jwks.json").build());
    }

    /** 指定された値や利用先を使って、OpenAiIdentityVerifierを初期化する。 */
    OpenAiIdentityVerifier(NimbusJwtDecoder decoder) {
      this.decoder = decoder;
      decoder.setJwtValidator(
          new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
              new JwtTimestampValidator(Duration.ofSeconds(5)),
              new JwtIssuerValidator("https://auth.openai.com")));
    }

    /** 要求された条件を満たすことを確認する。 */
    public Identity verify(String raw, String client, String nonce) {
      try {
        var jwt = decoder.decode(raw);
        if (!jwt.getAudience().contains(client)
            || !Objects.equals(nonce, jwt.getClaimAsString("nonce"))
            || jwt.getSubject() == null
            || jwt.getSubject().isBlank()
            || jwt.getIssuedAt() == null
            || jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(5))
            || jwt.getExpiresAt() == null) throw new IllegalArgumentException();
        return new Identity(jwt.getSubject(), jwt.getClaimAsString("email"));
      } catch (Exception e) {
        throw ChatGptException.of(AUTH_INVALID);
      }
    }
  }

  /**
   * ChatGPTとの連携で受け渡す試行を表す。
   *
   * @param account アカウント
   * @param clientId クライアントの識別子
   * @param subject 対象
   * @param state 状態
   * @param nonce 一回限りの識別値
   * @param verifier 確認の窓口
   * @param redirect 転送先
   * @param expiresAt 有効期限
   * @param authorization 権限の確認
   */
  public record Attempt(
      String account,
      String clientId,
      String subject,
      String state,
      String nonce,
      String verifier,
      URI redirect,
      Instant expiresAt,
      URI authorization) {
    /** ChatGPTとの連携の情報を、表示や診断で確認できる文字列表現にする。 */
    @Override
    public String toString() {
      return "Attempt[protected]";
    }
  }

  /** 保存先。 */
  private final ProtectedCredentialStore store;

  /** 通信経路。 */
  private final PlanTransport transport;

  /** 確認の窓口。 */
  private final IdentityVerifier verifier;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper();

  /** 使用量。 */
  private final Map<String, Instant> used = new java.util.concurrent.ConcurrentHashMap<>();

  /** 指定された値や利用先を使って、PlanOAuthを初期化する。 */
  public PlanOAuth(
      ProtectedCredentialStore store,
      PlanTransport transport,
      IdentityVerifier verifier,
      Clock clock) {
    this.store = store;
    this.transport = transport;
    this.verifier = verifier;
    this.clock = clock;
  }

  /** ChatGPTとの連携で用いる開始を返す。 */
  public Attempt begin(String account, URI redirect) {
    if (!"http".equals(redirect.getScheme())
        || !"127.0.0.1".equals(redirect.getHost())
        || !"/auth/callback".equals(redirect.getPath())
        || redirect.getPort() < 1
        || redirect.getQuery() != null
        || redirect.getFragment() != null
        || redirect.getUserInfo() != null) throw ChatGptException.of(AUTH_INVALID);
    return store.locked(
        s -> {
          var old = s.load(account);
          var client = old == null ? "dynamic_agent_client" : old.clientId();
          var state = random();
          var nonce = random();
          var pkce = random();
          var query = new LinkedHashMap<String, String>();
          query.put("client_id", client);
          if (old == null) query.put("agent_name_hint", "rei");
          query.put("ext_agent_host_id", s.hostId());
          query.put("response_type", "code");
          query.put("redirect_uri", redirect.toString());
          query.put(
              "scope",
              "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct");
          query.put("resource", RESOURCE);
          query.put("state", state);
          query.put("nonce", nonce);
          query.put("code_challenge_method", "S256");
          query.put("code_challenge", challenge(pkce));
          if (old != null && old.idToken() != null) query.put("id_token_hint", old.idToken());
          return new Attempt(
              account,
              client,
              old == null ? null : old.subject(),
              state,
              nonce,
              pkce,
              redirect,
              clock.instant().plusSeconds(600),
              URI.create("https://auth.openai.com/api/accounts/authorize?" + form(query)));
        });
  }

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  public void complete(Attempt attempt, Map<String, String> callback) {
    used.entrySet().removeIf(e -> !clock.instant().isBefore(e.getValue()));
    if (!Objects.equals(attempt.state(), callback.get("state"))
        || !clock.instant().isBefore(attempt.expiresAt())
        || used.putIfAbsent(attempt.state(), attempt.expiresAt()) != null)
      throw ChatGptException.of(AUTH_INVALID);
    if (callback.containsKey("error")) throw ChatGptException.of(AUTH_INVALID);
    String client = callback.getOrDefault("client_id", attempt.clientId());
    if (client == null
        || !client.matches("oaiapp_[A-Za-z0-9_-]{1,256}")
        || !attempt.clientId().equals("dynamic_agent_client") && !attempt.clientId().equals(client)
        || callback.get("code") == null) throw ChatGptException.of(AUTH_INVALID);
    var grant =
        request(
            TOKEN,
            Map.of(
                "grant_type",
                "authorization_code",
                "client_id",
                client,
                "code",
                callback.get("code"),
                "code_verifier",
                attempt.verifier(),
                "redirect_uri",
                attempt.redirect().toString(),
                "resource",
                RESOURCE));
    var identity = verifier.verify(required(grant, "id_token"), client, attempt.nonce());
    if (attempt.subject() != null && !attempt.subject().equals(identity.subject()))
      throw ChatGptException.of(AUTH_INVALID);
    var tokens = tokenSet(grant, client, identity, null);
    store.locked(
        s -> {
          var current = s.load(attempt.account());
          if (current != null
              && (!current.clientId().equals(client)
                  || !current.subject().equals(identity.subject())))
            throw ChatGptException.of(AUTH_INVALID);
          s.save(attempt.account(), tokens);
          return null;
        });
  }

  /** ChatGPTとの連携で用いるアクセスを返す。 */
  public TokenSet access(String account) {
    return store.locked(
        s -> {
          var saved = s.load(account);
          if (saved == null || saved.accessToken() == null)
            throw ChatGptException.of(AUTH_REQUIRED);
          if (!saved.scopes().contains("chatgpt.tokens.use.direct"))
            throw ChatGptException.of(CONSENT_REQUIRED);
          if (saved.expiresAt() > clock.instant().getEpochSecond()) return saved;
          if (saved.refreshToken() == null) throw ChatGptException.of(AUTH_REQUIRED);
          try {
            var grant =
                request(
                    TOKEN,
                    Map.of(
                        "grant_type",
                        "refresh_token",
                        "client_id",
                        saved.clientId(),
                        "refresh_token",
                        saved.refreshToken(),
                        "resource",
                        RESOURCE));
            var replacement =
                tokenSet(
                    grant, saved.clientId(), new Identity(saved.subject(), saved.email()), saved);
            s.save(account, replacement);
            return replacement;
          } catch (ChatGptException e) {
            if (e.code() == AUTH_INVALID) s.save(account, saved.withoutTokens());
            throw e;
          }
        });
  }

  /** ChatGPTとの連携において、ログアウトに該当するか判定する。 */
  public boolean logout(String account) {
    return store.locked(
        s -> {
          var saved = s.load(account);
          if (saved == null) return true;
          boolean confirmed = false;
          try {
            if (saved.refreshToken() != null) {
              var discovery = getDiscovery();
              URI revoke = URI.create(required(discovery, "revocation_endpoint"));
              if (!revoke.getScheme().equals("https")
                  || !revoke.getHost().equals("auth.openai.com"))
                throw ChatGptException.of(AUTH_INVALID);
              try (var response =
                  transport.send(
                      revoke,
                      "POST",
                      Map.of("Content-Type", "application/x-www-form-urlencoded"),
                      form(Map.of(
                              "token",
                              saved.refreshToken(),
                              "token_type_hint",
                              "refresh_token",
                              "client_id",
                              saved.clientId()))
                          .getBytes(StandardCharsets.UTF_8),
                      Duration.ofSeconds(15),
                      () -> false)) {
                confirmed = response.status() == 200;
              }
            } else confirmed = true;
          } catch (Exception ignored) {
          } finally {
            s.save(account, saved.withoutTokens());
          }
          return confirmed;
        });
  }

  /** ChatGPTとの連携で用いる非公開化識別子を返す。 */
  private static String maskIdentifier(String value) {
    if (value == null || value.length() < 12) return "[masked]";
    return value.substring(0, 6) + "…" + value.substring(value.length() - 4);
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public List<String> status() {
    return store.locked(
        s ->
            s.accounts().stream()
                .map(
                    a -> {
                      var token = s.load(a);
                      return a
                          + " | "
                          + maskIdentifier(token.clientId())
                          + " | "
                          + maskIdentifier(token.subject())
                          + " | "
                          + (token.accessToken() == null
                              ? "signed out"
                              : token.scopes().contains("chatgpt.tokens.use.direct")
                                  ? "ChatGPT plan authorized"
                                  : "plan disabled");
                    })
                .toList());
  }

  /**
   * ChatGPTとの連携で用いる許可済みアカウント一覧を返す。
   *
   * <p>Local authorization labels only; no token, subject, client ID or network request.
   */
  public java.util.Set<String> authorizedAccounts() {
    return store.locked(
        s ->
            s.accounts().stream()
                .filter(
                    account -> {
                      var token = s.load(account);
                      return token.accessToken() != null
                          && token.scopes().contains("chatgpt.tokens.use.direct");
                    })
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
  }

  /** ChatGPTとの連携で参照する利用先の検出を返す。 */
  private JsonNode getDiscovery() {
    try (var response =
        transport.send(DISCOVERY, "GET", Map.of(), null, Duration.ofSeconds(15), () -> false)) {
      return decode(response, "oauth-discovery");
    }
  }

  /** ChatGPTとの連携で用いる要求を返す。 */
  private JsonNode request(URI uri, Map<String, String> fields) {
    try (var response =
        transport.send(
            uri,
            "POST",
            Map.of("Content-Type", "application/x-www-form-urlencoded"),
            form(fields).getBytes(StandardCharsets.UTF_8),
            Duration.ofSeconds(20),
            () -> false)) {
      return decode(response, "oauth-token");
    }
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  private JsonNode decode(PlanTransport.Response response, String operation) {
    try {
      byte[] bytes =
          PlanIO.read(
              response.body(),
              Duration.ofSeconds(20),
              () -> false,
              () -> PlanIO.bytes(response.body(), 65536));
      if (bytes.length > 65536) throw ChatGptException.of(PROTOCOL);
      var value = json.readTree(bytes);
      if (response.status() != 200) {
        String code =
            value.path("error").isTextual()
                ? value.path("error").asText()
                : value.path("error").path("code").asText();
        if (Set.of(
                "invalid_grant",
                "invalid_refresh_token",
                "token_expired",
                "refresh_token_expired",
                "refresh_token_invalidated",
                "refresh_token_reused")
            .contains(code)) throw ChatGptException.of(AUTH_INVALID);
        throw ChatGptException.of(TRANSPORT);
      }
      return value;
    } catch (ChatGptException e) {
      if (e.code() == RECEIVE_LIMIT) {
        String type =
            response.contentType() == null
                ? ""
                : response.contentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        // PlanIO.bytes reads at most limit+1; this count is observed, not Content-Length.
        throw ChatGptException.response(
            RECEIVE_LIMIT, operation, response.status(), type, 65537, 65536, "");
      }
      throw e;
    } catch (Exception e) {
      throw ChatGptException.of(PROTOCOL);
    }
  }

  /** ChatGPTとの連携で用いるトークン集合を返す。 */
  private TokenSet tokenSet(JsonNode value, String client, Identity identity, TokenSet previous) {
    if (!"Bearer".equalsIgnoreCase(required(value, "token_type")))
      throw ChatGptException.of(PROTOCOL);
    long seconds = value.path("expires_in").asLong(0);
    if (seconds <= 0 || seconds > 86400) throw ChatGptException.of(PROTOCOL);
    String scope =
        value.path("scope").asText(previous == null ? "" : String.join(" ", previous.scopes()));
    return new TokenSet(
        client,
        identity.subject(),
        identity.email(),
        value.path("id_token").asText(previous == null ? null : previous.idToken()),
        required(value, "access_token"),
        required(value, "refresh_token"),
        new HashSet<>(Arrays.asList(scope.split(" +"))),
        clock.instant().plusSeconds(seconds).getEpochSecond());
  }

  /** ChatGPTとの連携で用いる必須を返す。 */
  static String required(JsonNode value, String key) {
    if (!value.path(key).isTextual() || value.path(key).asText().isBlank())
      throw ChatGptException.of(PROTOCOL);
    return value.path(key).asText();
  }

  /** ChatGPTとの連携で用いるformを返す。 */
  static String form(Map<String, String> fields) {
    return fields.entrySet().stream()
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  /** ChatGPTとの連携で用いるchallengeを返す。 */
  static String challenge(String verifier) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256")
                  .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (Exception e) {
      throw ChatGptException.of(AUTH_INVALID);
    }
  }

  /** ChatGPTとの連携で用いる乱数を返す。 */
  private static String random() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
