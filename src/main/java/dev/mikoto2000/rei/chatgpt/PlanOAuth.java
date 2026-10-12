package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.security.oauth2.jwt.*;

/** Public-client authorization. No credentials are read from other applications. */
public final class PlanOAuth {
  public static final String RESOURCE = "https://api.openai.com/v1";
  public static final URI TOKEN = URI.create("https://auth.openai.com/api/accounts/oauth/token");
  private static final URI DISCOVERY =
      URI.create("https://auth.openai.com/.well-known/openid-configuration");

  public interface IdentityVerifier {
    Identity verify(String idToken, String clientId, String nonce);
  }

  public record Identity(String subject, String email) {}

  public static final class OpenAiIdentityVerifier implements IdentityVerifier {
    private final NimbusJwtDecoder decoder;

    public OpenAiIdentityVerifier() {
      this(NimbusJwtDecoder.withJwkSetUri("https://auth.openai.com/.well-known/jwks.json").build());
    }

    OpenAiIdentityVerifier(NimbusJwtDecoder decoder) {
      this.decoder = decoder;
      decoder.setJwtValidator(
          new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
              new JwtTimestampValidator(Duration.ofSeconds(5)),
              new JwtIssuerValidator("https://auth.openai.com")));
    }

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
    @Override
    public String toString() {
      return "Attempt[protected]";
    }
  }

  private final ProtectedCredentialStore store;
  private final PlanTransport transport;
  private final IdentityVerifier verifier;
  private final Clock clock;
  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, Instant> used = new java.util.concurrent.ConcurrentHashMap<>();

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

  private static String maskIdentifier(String value) {
    if (value == null || value.length() < 12) return "[masked]";
    return value.substring(0, 6) + "…" + value.substring(value.length() - 4);
  }

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
  /** Local authorization labels only; no token, subject, client ID or network request. */
  public java.util.Set<String> authorizedAccounts() {
    return store.locked(s -> s.accounts().stream().filter(account -> {
      var token=s.load(account);
      return token.accessToken()!=null && token.scopes().contains("chatgpt.tokens.use.direct");
    }).collect(java.util.stream.Collectors.toUnmodifiableSet()));
  }

  private JsonNode getDiscovery() {
    try (var response =
        transport.send(DISCOVERY, "GET", Map.of(), null, Duration.ofSeconds(15), () -> false)) {
      return decode(response, "oauth-discovery");
    }
  }

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

  static String required(JsonNode value, String key) {
    if (!value.path(key).isTextual() || value.path(key).asText().isBlank())
      throw ChatGptException.of(PROTOCOL);
    return value.path(key).asText();
  }

  static String form(Map<String, String> fields) {
    return fields.entrySet().stream()
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(java.util.stream.Collectors.joining("&"));
  }

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

  private static String random() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
