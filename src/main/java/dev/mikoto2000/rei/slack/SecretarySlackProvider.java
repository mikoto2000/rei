package dev.mikoto2000.rei.slack;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mikoto2000.rei.secretary.SecretaryConnections;
import dev.mikoto2000.rei.secretary.SecretaryHttp;
import dev.mikoto2000.rei.secretary.SecretaryProvider;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Reuses the existing human admission, bot credential, channel ownership, and delivery transport.
 */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
@EnableConfigurationProperties(SlackProperties.class)
public class SecretarySlackProvider implements SecretaryProvider, AutoCloseable {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();
  private static final java.util.concurrent.Semaphore SENDS = new java.util.concurrent.Semaphore(2);
  private final boolean enabled;
  private final String botToken, userToken;
  private final Clock clock;
  private final ObjectProvider<SlackProperties> policies;
  private final SecretaryHttp http = new SecretaryHttp();
  private volatile boolean botInvalid, userInvalid;
  private volatile String team, self;
  private com.slack.api.util.http.SlackHttpClient sdkHttp;
  private com.slack.api.methods.MethodsClient sdkMethods;

  @Autowired
  public SecretarySlackProvider(
      @Value("${rei.secretary.slack.enabled:false}") boolean enabled,
      ObjectProvider<SlackProperties> policies,
      Clock clock) {
    this(enabled, policies, System.getenv(), clock);
  }

  private SecretarySlackProvider(
      boolean enabled,
      ObjectProvider<SlackProperties> policies,
      Map<String, String> environment,
      Clock clock) {
    this.enabled = enabled;
    this.policies = policies;
    this.botToken = environment.getOrDefault("SLACK_BOT_TOKEN", "");
    this.userToken = environment.getOrDefault("SLACK_USER_TOKEN", "");
    this.clock = clock;
  }

  SecretarySlackProvider(
      boolean enabled, SlackProperties properties, Map<String, String> environment, Clock clock) {
    this(enabled, provider(properties), environment, clock);
  }

  private static ObjectProvider<SlackProperties> provider(SlackProperties properties) {
    var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    beans.addBean("properties", properties);
    return beans.getBeanProvider(SlackProperties.class);
  }

  private SlackProperties properties() {
    var p = policies.getIfAvailable();
    if (p == null) throw new Failure("ALLOWLIST_REQUIRED", 0, false);
    return p;
  }

  private static String value(JsonNode node, String key) {
    return node.path(key).asText("");
  }

  private static String bound(String text, int max) {
    return text.substring(0, Math.min(max, text.length()));
  }

  private static String hash(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String timestamp(String value) {
    if (value == null || !value.matches("[0-9]{1,12}\\.[0-9]{1,6}"))
      throw new IllegalArgumentException("Invalid Slack timestamp");
    return value;
  }

  private static String timestamp(Instant time) {
    return time.getEpochSecond()
        + "."
        + String.format(java.util.Locale.ROOT, "%06d", time.getNano() / 1000);
  }

  private static Instant instant(String value) {
    var parts = timestamp(value).split("\\.");
    return Instant.ofEpochSecond(
        Long.parseLong(parts[0]), Long.parseLong((parts[1] + "000000").substring(0, 6)) * 1000);
  }

  @Override
  public Set<Service> services() {
    return Set.of(Service.SLACK);
  }

  @Override
  public Auth status(Service service) {
    return new Auth(
        !enabled
            ? "DISABLED"
            : botToken.isBlank() ? "UNCONFIGURED" : botInvalid ? "REAUTH_REQUIRED" : "CONFIGURED",
        botToken.isBlank() ? "" : hash(botToken),
        List.of("channels:history", "groups:history", "im:history", "mpim:history", "chat:write"),
        userToken.isBlank()
            ? "user_token_required_for_channel_threads"
            : "environment_credentials_not_live_verified");
  }

  private void require() {
    if (!enabled || botToken.isBlank() || botInvalid) throw new Failure("AUTH_REQUIRED", 0, false);
  }

  protected JsonNode api(String method, Map<String, String> query, boolean user) {
    require();
    if (!Set.of("auth.test", "conversations.history", "conversations.replies").contains(method))
      throw new IllegalArgumentException("Unknown Slack method");
    String credential = user ? userToken : botToken;
    if (credential.isBlank() || user && userInvalid)
      throw new Failure("USER_TOKEN_REQUIRED", 0, false);
    var response =
        http.get(
            SecretaryHttp.Host.SLACK,
            "/" + method,
            query,
            Map.of("Authorization", "Bearer " + credential, "Accept", "application/json"));
    int code = response.status();
    if (code == 429) {
      int seconds = 60;
      try {
        seconds = Integer.parseInt(response.header("Retry-After"));
      } catch (NumberFormatException ignored) {
        /* conservative default */
      }
      throw new Failure("RATE_LIMITED", seconds, false);
    }
    if (code < 200 || code >= 300) throw new Failure("HTTP_" + code, 60, false);
    var body = response.body();
    if (!body.path("ok").asBoolean()) {
      String error = value(body, "error");
      if (Set.of("invalid_auth", "token_revoked", "token_expired", "account_inactive", "not_authed")
          .contains(error)) {
        if (user) userInvalid = true;
        else botInvalid = true;
        throw new Failure("AUTH_REQUIRED", 0, false);
      }
      throw new Failure(
          error.equals("ratelimited") || error.equals("rate_limited")
              ? "RATE_LIMITED"
              : error.equals("missing_scope") ? "MISSING_SCOPE" : "SLACK_REJECTED",
          60,
          false);
    }
    return body;
  }

  private synchronized void profile() {
    require();
    if (team == null || self == null) {
      var identity = api("auth.test", Map.of(), false);
      team = value(identity, "team_id");
      self = value(identity, "user_id");
    }
    if (team.isBlank() || self.isBlank()) throw new Failure("INVALID_IDENTITY", 0, false);
  }

  private SlackProperties scope(SecretaryConnections.Connection c) {
    profile();
    var p = properties();
    String channel = c.config().resource();
    if (!channel.matches("[CGD][A-Z0-9]{1,30}")
        || p.getAllowedWorkspaceIds() == null
        || !p.getAllowedWorkspaceIds().contains(team)
        || p.getAllowedChannelIds() == null
        || !p.getAllowedChannelIds().contains(channel)
        || p.getAllowedUserIds() == null
        || p.getAllowedUserIds().isEmpty()) throw new Failure("SCOPE_REJECTED", 0, false);
    String project = p.getChannelProjects() == null ? null : p.getChannelProjects().get(channel);
    if (channel.startsWith("D") && project == null) project = p.getDmProjectId();
    if (!c.config().projectId().equals(project))
      throw new Failure("PROJECT_CHANNEL_MISMATCH", 0, false);
    return p;
  }

  @Override
  public String identity(SecretaryConnections.Connection c) {
    var p = scope(c);
    return hash(
        botToken
            + "\n"
            + userToken
            + "\n"
            + team
            + "\n"
            + self
            + "\n"
            + new java.util.TreeSet<>(p.getAllowedWorkspaceIds())
            + new java.util.TreeSet<>(p.getAllowedChannelIds())
            + new java.util.TreeSet<>(p.getAllowedUserIds())
            + new java.util.TreeMap<>(
                p.getChannelProjects() == null ? Map.of() : p.getChannelProjects()));
  }

  @Override
  public Auth authorizeConnection(Service service, boolean force) {
    botInvalid = false;
    userInvalid = false;
    team = null;
    self = null;
    profile();
    return new Auth(
        "CONFIGURED", hash(botToken), status(service).scopes(), "live_bot_identity_verified");
  }

  private boolean userRead(SecretaryConnections.Connection c) {
    if (c.config().resource().startsWith("D")) return false;
    if (userToken.isBlank() || userInvalid) throw new Failure("USER_TOKEN_REQUIRED", 0, false);
    var identity = api("auth.test", Map.of(), true);
    if (!team.equals(value(identity, "team_id"))
        || !properties().getAllowedUserIds().contains(value(identity, "user_id")))
      throw new Failure("USER_WORKSPACE_MISMATCH", 0, false);
    return true;
  }

  private Item item(SecretaryConnections.Connection c, JsonNode node, boolean actionable) {
    String ts = timestamp(value(node, "ts"));
    var data = new java.util.HashMap<String, String>();
    data.put("channel", c.config().resource());
    data.put("team", team);
    data.put("threadTs", value(node, "thread_ts").isBlank() ? ts : value(node, "thread_ts"));
    data.put("author", value(node, "user"));
    String text = bound(value(node, "text"), 8192);
    return new Item(
        ts,
        hash(text + ":" + node.path("edited")),
        "SLACK_MESSAGE",
        bound(text.replaceAll("[\\p{Cntrl}]", " "), 300),
        text,
        "OBSERVED",
        instant(ts),
        null,
        actionable,
        data);
  }

  private static void fields(Map<String, String> args, String... allowed) {
    if (!Set.of(allowed).containsAll(args.keySet()))
      throw new IllegalArgumentException("Unknown Slack argument");
  }

  private List<Item> humans(
      SecretaryConnections.Connection c, JsonNode response, boolean mentions) {
    var p = scope(c);
    var result = new ArrayList<Item>();
    for (var message : response.path("messages")) {
      if (result.size() >= 15) break;
      String author = value(message, "user"),
          bot = value(message, "bot_id"),
          subtype = value(message, "subtype");
      if (author.isBlank() || author.equals(self) || !bot.isBlank() || !subtype.isBlank()) continue;
      String ts = timestamp(value(message, "ts"));
      String type =
          c.config().resource().startsWith("D")
              ? "im"
              : c.config().resource().startsWith("G") ? "group" : "channel";
      var normalized =
          new SlackMessage(
              "secretary:" + ts,
              team,
              c.config().resource(),
              ts,
              value(message, "thread_ts"),
              author,
              bot,
              subtype,
              "message",
              type,
              value(message, "text"));
      if (mentions && !SlackAdmission.accept(p, self, normalized, false)) continue;
      result.add(item(c, message, mentions));
    }
    return List.copyOf(result);
  }

  @Override
  public Page read(SecretaryConnections.Connection c, String operation, Map<String, String> args) {
    scope(c);
    if (operation.equals("MENTIONS") || operation.equals("LIST")) {
      fields(args, "cursor", "oldest");
      var query = new java.util.HashMap<String, String>();
      query.put("channel", c.config().resource());
      query.put("limit", "15");
      if (args.containsKey("cursor")) query.put("cursor", args.get("cursor"));
      if (args.containsKey("oldest")) query.put("oldest", timestamp(args.get("oldest")));
      var response = api("conversations.history", query, false);
      String cursor = value(response.path("response_metadata"), "next_cursor");
      return new Page(
          humans(c, response, true),
          cursor,
          !cursor.isBlank() || response.path("has_more").asBoolean());
    }
    if (!Set.of("THREAD", "REPLY").contains(operation))
      throw new IllegalArgumentException("Unknown Slack read operation");
    fields(args, "ts", "cursor");
    String ts = timestamp(args.get("ts"));
    boolean user = userRead(c);
    var query = new java.util.HashMap<String, String>();
    query.put("channel", c.config().resource());
    query.put("ts", ts);
    query.put("limit", "15");
    if (args.containsKey("cursor")) query.put("cursor", args.get("cursor"));
    var response = api("conversations.replies", query, user);
    var items = humans(c, response, false);
    String cursor = value(response.path("response_metadata"), "next_cursor");
    if (operation.equals("REPLY")) {
      return new Page(
          List.of(
              new Item(
                  ts,
                  "template-v1",
                  "REPLY_DRAFT",
                  "Slack reply preview",
                  "ご連絡ありがとうございます。確認のうえ、このスレッドでご連絡します。",
                  "PREVIEW",
                  clock.instant(),
                  null,
                  false,
                  Map.of("thread", ts, "generation", "local_template_no_model"))),
          "",
          false);
    }
    return new Page(items, cursor, !cursor.isBlank() || response.path("has_more").asBoolean());
  }

  @Override
  public Page poll(SecretaryConnections.Connection c, String cursor) {
    scope(c);
    String oldest = timestamp(clock.instant().minusSeconds(7 * 86400L)),
        latest = timestamp(clock.instant()),
        page = "";
    if (!cursor.isBlank())
      try {
        var state = JSON.readTree(java.util.Base64.getUrlDecoder().decode(cursor));
        oldest = timestamp(value(state, "oldest"));
        page = value(state, "page");
        if (!page.isBlank()) latest = timestamp(value(state, "latest"));
      } catch (java.io.IOException | IllegalArgumentException invalid) {
        throw new Failure("INVALID_CURSOR", 0, false);
      }
    var query = new java.util.HashMap<String, String>();
    query.put("channel", c.config().resource());
    query.put("limit", "15");
    query.put("oldest", oldest);
    query.put("latest", latest);
    if (!page.isBlank()) query.put("cursor", page);
    var response = api("conversations.history", query, false);
    String next = value(response.path("response_metadata"), "next_cursor");
    boolean partial = !next.isBlank() || response.path("has_more").asBoolean();
    try {
      String encoded =
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  JSON.writeValueAsBytes(
                      Map.of(
                          "oldest",
                          next.isBlank() ? latest : oldest,
                          "latest",
                          latest,
                          "page",
                          next)));
      return new Page(humans(c, response, true), encoded, partial);
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  protected synchronized SlackTransport transport(String team) {
    if (sdkMethods == null) {
      var client =
          new okhttp3.OkHttpClient.Builder()
              .retryOnConnectionFailure(false)
              .followRedirects(false)
              .followSslRedirects(false)
              .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
              .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
              .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
              .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
              .build();
      sdkHttp = new com.slack.api.util.http.SlackHttpClient(client);
      var config = new com.slack.api.SlackConfig();
      config.setPrettyResponseLoggingEnabled(false);
      sdkMethods = com.slack.api.Slack.getInstance(config, sdkHttp).methods(botToken);
    }
    return new SdkSlackTransport(sdkMethods, team);
  }

  @Override
  public Outcome write(
      SecretaryConnections.Connection c, String operation, Map<String, String> args, String key) {
    scope(c);
    if (!Set.of("SLACK_POST", "SLACK_REPORT").contains(operation))
      throw new IllegalArgumentException("Unknown Slack action");
    fields(args, "body", "thread", "taskId", "taskRevision");
    String body = args.get("body");
    if (body == null || body.isBlank()) throw new IllegalArgumentException("Slack body required");
    String text = body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    if (text.length() > 3500) throw new IllegalArgumentException("Slack message too large");
    String thread = args.getOrDefault("thread", "");
    if (!thread.isBlank()) {
      timestamp(thread);
      userRead(c);
    }
    if (!SENDS.tryAcquire()) throw new Failure("LOCAL_BUSY", 60, false);
    SlackTransport.Delivery delivered;
    try {
      delivered =
          transport(team)
              .send(
                  new SlackStore.Outgoing(
                      "secretary:" + key,
                      team,
                      c.config().resource(),
                      thread.isBlank() ? null : thread,
                      text));
    } finally {
      SENDS.release();
    }
    if (delivered.state().equals("RETRY"))
      throw new Failure("RATE_LIMITED", delivered.retryAfterSeconds(), false);
    if (delivered.state().equals("FAILED")) throw new Failure("REMOTE_REJECTED", 0, false);
    if (!delivered.state().equals("SENT")) throw new Failure("EFFECT_UNCONFIRMED", 0, true);
    String ts = timestamp(delivered.ts());
    try {
      JsonNode response =
          thread.isBlank()
              ? api(
                  "conversations.history",
                  Map.of(
                      "channel",
                      c.config().resource(),
                      "oldest",
                      ts,
                      "latest",
                      ts,
                      "inclusive",
                      "true",
                      "limit",
                      "1"),
                  false)
              : api(
                  "conversations.replies",
                  Map.of("channel", c.config().resource(), "ts", thread, "limit", "15"),
                  userRead(c));
      boolean verified = false;
      for (var message : response.path("messages"))
        if (ts.equals(value(message, "ts"))
            && self.equals(value(message, "user"))
            && text.equals(value(message, "text"))) verified = true;
      return new Outcome(
          verified ? "COMPLETED" : "UNCERTAIN",
          ts,
          ts,
          Map.of("channel", c.config().resource(), "thread", thread),
          verified);
    } catch (Failure failure) {
      throw new Failure("VERIFICATION_UNAVAILABLE", failure.retrySeconds(), true);
    }
  }

  @Override
  public Outcome verify(
      SecretaryConnections.Connection c,
      String operation,
      Map<String, String> args,
      String key,
      Outcome previous) {
    scope(c);
    if (!Set.of("SLACK_POST", "SLACK_REPORT").contains(operation))
      throw new IllegalArgumentException("Unknown Slack action");
    String expected =
        args.getOrDefault("body", "")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    if (!expected.contains("[rei-action:" + key + "]"))
      return new Outcome("UNCERTAIN", "", "", Map.of(), false);
    String thread = args.getOrDefault("thread", "");
    var response =
        thread.isBlank()
            ? api(
                "conversations.history",
                Map.of("channel", c.config().resource(), "limit", "15"),
                false)
            : api(
                "conversations.replies",
                Map.of("channel", c.config().resource(), "ts", timestamp(thread), "limit", "15"),
                userRead(c));
    JsonNode match = null;
    for (var message : response.path("messages"))
      if (self.equals(value(message, "user")) && expected.equals(value(message, "text"))) {
        if (match != null) return new Outcome("UNCERTAIN", "", "", Map.of(), false);
        match = message;
      }
    if (match == null) return new Outcome("UNCERTAIN", "", "", Map.of(), false);
    String ts = timestamp(value(match, "ts"));
    return new Outcome(
        "COMPLETED", ts, ts, Map.of("channel", c.config().resource(), "thread", thread), true);
  }

  @Override
  @jakarta.annotation.PreDestroy
  public synchronized void close() {
    http.close();
    if (sdkHttp != null)
      try {
        sdkHttp.close();
      } catch (Exception ignored) {
        org.slf4j.LoggerFactory.getLogger(SecretarySlackProvider.class)
            .warn("Secretary Slack transport cleanup failed");
      }
  }
}
