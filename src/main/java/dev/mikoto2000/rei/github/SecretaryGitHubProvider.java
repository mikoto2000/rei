package dev.mikoto2000.rei.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mikoto2000.rei.secretary.SecretaryConnections;
import dev.mikoto2000.rei.secretary.SecretaryHttp;
import dev.mikoto2000.rei.secretary.SecretaryProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Optional REST polling alongside the existing signed-webhook facts; no GitHub writes. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public class SecretaryGitHubProvider implements SecretaryProvider, AutoCloseable {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();
  private final boolean enabled;
  private final String token;
  private final Clock clock;
  private final SecretaryHttp http = new SecretaryHttp();
  private volatile boolean invalidAuth;
  private volatile String login;

  @Autowired
  public SecretaryGitHubProvider(
      @Value("${rei.secretary.github.enabled:false}") boolean enabled, Clock clock) {
    this(enabled, System.getenv(), clock);
  }

  SecretaryGitHubProvider(boolean enabled, Map<String, String> environment, Clock clock) {
    this.enabled = enabled;
    this.token = environment.getOrDefault("GH_TOKEN", environment.getOrDefault("GITHUB_TOKEN", ""));
    this.clock = clock;
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

  @Override
  public Set<Service> services() {
    return Set.of(Service.GITHUB);
  }

  @Override
  public Auth status(Service service) {
    return new Auth(
        !enabled
            ? "DISABLED"
            : token.isBlank() ? "UNCONFIGURED" : invalidAuth ? "REAUTH_REQUIRED" : "CONFIGURED",
        token.isBlank() ? "" : hash(token),
        List.of(),
        "environment_credential_not_live_verified");
  }

  private void require() {
    if (!enabled || token.isBlank() || invalidAuth) throw new Failure("AUTH_REQUIRED", 0, false);
  }

  @Override
  public String identity(SecretaryConnections.Connection connection) {
    require();
    return hash(token);
  }

  @Override
  public Auth authorizeConnection(Service service, boolean force) {
    require();
    var user = api("/user", Map.of());
    login = value(user, "login");
    if (!login.matches("[A-Za-z0-9-]{1,39}")) throw new Failure("INVALID_IDENTITY", 0, false);
    return new Auth("CONFIGURED", hash(token), List.of(), "live_identity_verified");
  }

  protected JsonNode api(String path, Map<String, String> query) {
    require();
    var response =
        http.get(
            SecretaryHttp.Host.GITHUB,
            path,
            query,
            Map.of(
                "Authorization",
                "Bearer " + token,
                "Accept",
                "application/vnd.github+json",
                "X-GitHub-Api-Version",
                "2022-11-28",
                "User-Agent",
                "Rei-Secretary"));
    int status = response.status();
    if (status == 401) {
      invalidAuth = true;
      throw new Failure("AUTH_REQUIRED", 0, false);
    }
    if (status == 429
        || status == 403
            && (response.header("X-RateLimit-Remaining").equals("0")
                || !response.header("Retry-After").isBlank())) {
      int seconds = 60;
      try {
        if (!response.header("Retry-After").isBlank())
          seconds = Integer.parseInt(response.header("Retry-After"));
        else
          seconds =
              (int)
                  Math.max(
                      1,
                      Long.parseLong(response.header("X-RateLimit-Reset"))
                          - clock.instant().getEpochSecond());
      } catch (NumberFormatException ignored) {
        /* conservative default */
      }
      throw new Failure("RATE_LIMITED", seconds, false);
    }
    if (status < 200 || status >= 300)
      throw new Failure(
          status == 404 ? "NOT_FOUND" : status == 403 ? "FORBIDDEN" : "HTTP_" + status, 60, false);
    return response.body();
  }

  private String who() {
    var cached = login;
    if (cached != null) return cached;
    cached = value(api("/user", Map.of()), "login");
    if (!cached.matches("[A-Za-z0-9-]{1,39}")) throw new Failure("INVALID_IDENTITY", 0, false);
    login = cached;
    return cached;
  }

  private static String repository(SecretaryConnections.Connection c) {
    String resource = c.config().resource();
    if (!resource.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")
        || resource.endsWith("/.")
        || resource.endsWith("/..")) throw new IllegalArgumentException("Use owner/repository");
    return resource;
  }

  private static void fields(Map<String, String> args, String... fields) {
    if (!Set.of(fields).containsAll(args.keySet()))
      throw new IllegalArgumentException("Unknown GitHub arguments");
  }

  private static int number(Map<String, String> args) {
    int number = Integer.parseInt(args.getOrDefault("number", "0"));
    if (number < 1) throw new IllegalArgumentException("PR/Issue number required");
    return number;
  }

  private static int page(Map<String, String> args) {
    int page = Integer.parseInt(args.getOrDefault("page", "1"));
    if (page < 1 || page > 50) throw new IllegalArgumentException("Invalid GitHub page");
    return page;
  }

  private Instant time(String text) {
    try {
      return Instant.parse(text);
    } catch (RuntimeException unavailable) {
      return clock.instant();
    }
  }

  private Item item(JsonNode node, boolean review) {
    int n = node.path("number").asInt();
    if (n < 1) throw new Failure("INVALID_RESPONSE", 0, false);
    boolean pr = node.has("pull_request") || node.has("head");
    String state = value(node, "state").toUpperCase(java.util.Locale.ROOT);
    if (review) state = "REVIEW_REQUESTED";
    if (node.path("merged").asBoolean()) state = "MERGED";
    var data = new HashMap<String, String>();
    data.put("number", Integer.toString(n));
    data.put("headSha", value(node.path("head"), "sha"));
    String title = bound(value(node, "title"), 500);
    String revision =
        hash(value(node, "updated_at") + ":" + state + ":" + title + ":" + data.get("headSha"));
    return new Item(
        (pr ? "pr:" : "issue:") + n,
        revision,
        pr ? "PULL_REQUEST" : "ISSUE",
        title,
        "",
        state,
        time(value(node, "updated_at")),
        null,
        review || state.equals("OPEN"),
        data);
  }

  private Page search(
      SecretaryConnections.Connection c, String operation, Map<String, String> args) {
    fields(args, "page");
    int page = page(args);
    String user = who();
    String query =
        "repo:"
            + repository(c)
            + " "
            + (operation.equals("REVIEWS")
                ? "is:pr is:open review-requested:" + user
                : (operation.equals("PRS")
                        ? "is:pr "
                        : operation.equals("ISSUES") ? "is:issue " : "")
                    + "involves:"
                    + user
                    + (operation.equals("UNFINISHED")
                        ? " is:open"
                        : " updated:>=" + LocalDate.now(clock).minusDays(30)));
    var response =
        api(
            "/search/issues",
            Map.of(
                "q",
                query,
                "sort",
                "updated",
                "order",
                "desc",
                "per_page",
                "20",
                "page",
                Integer.toString(page)));
    var result = new ArrayList<Item>();
    for (var item : response.path("items")) {
      if (result.size() >= 20) break;
      result.add(item(item, operation.equals("REVIEWS")));
    }
    long total = response.path("total_count").asLong();
    boolean more = total > page * 20L && page < 50;
    return new Page(
        result,
        more ? Integer.toString(page + 1) : "",
        more || total > 1000 || response.path("incomplete_results").asBoolean());
  }

  @Override
  public Page read(SecretaryConnections.Connection c, String operation, Map<String, String> args) {
    require();
    repository(c);
    if (Set.of("LIST", "PRS", "REVIEWS", "ISSUES", "UNFINISHED").contains(operation))
      return search(c, operation, args);
    fields(args, "number", "page");
    int n = number(args);
    String prefix = "/repos/" + repository(c) + "/pulls/" + n;
    if (operation.equals("DETAIL"))
      return new Page(List.of(item(api(prefix, Map.of()), false)), "", false);
    if (operation.equals("CI")) return new Page(List.of(ci(c, n)), "", false);
    if (operation.equals("REVIEW_SUMMARY")) {
      int page = page(args);
      var reviews =
          api(prefix + "/reviews", Map.of("per_page", "100", "page", Integer.toString(page)));
      var latest = new java.util.TreeMap<String, String>();
      for (var review : reviews) {
        if (latest.size() >= 100) break;
        String user = value(review.path("user"), "login");
        if (!user.isBlank()) latest.put(user, value(review, "state"));
      }
      var counts = new java.util.TreeMap<String, Integer>();
      latest.values().forEach(s -> counts.merge(s, 1, Integer::sum));
      String text =
          String.join(
              ", ", counts.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
      var summary =
          new Item(
              "reviews:" + n,
              hash(latest.toString()),
              "REVIEW_SUMMARY",
              "PR #" + n + " reviews",
              text,
              "OBSERVED",
              clock.instant(),
              null,
              false,
              Map.of("number", Integer.toString(n)));
      return new Page(
          List.of(summary),
          reviews.size() >= 100 ? Integer.toString(page + 1) : "",
          reviews.size() >= 100);
    }
    throw new IllegalArgumentException("Unknown GitHub read operation");
  }

  private Item ci(SecretaryConnections.Connection c, int n) {
    String prefix = "/repos/" + repository(c);
    var pull = api(prefix + "/pulls/" + n, Map.of());
    String sha = value(pull.path("head"), "sha");
    if (!sha.matches("[a-fA-F0-9]{40,64}")) throw new Failure("INVALID_HEAD", 0, false);
    var checks = api(prefix + "/commits/" + sha + "/check-runs", Map.of("per_page", "100"));
    var statuses = api(prefix + "/commits/" + sha + "/status", Map.of("per_page", "100"));
    boolean known = false, pending = false, failed = false;
    int count = 0;
    for (var check : checks.path("check_runs")) {
      known = true;
      count++;
      if (!value(check, "status").equals("completed")) pending = true;
      else if (!Set.of("success", "neutral", "skipped").contains(value(check, "conclusion")))
        failed = true;
    }
    if (statuses.path("statuses").size() > 0) {
      known = true;
      String state = value(statuses, "state");
      if (state.equals("pending")) pending = true;
      else if (!state.equals("success")) failed = true;
    }
    String state = !known ? "UNKNOWN" : failed ? "FAILURE" : pending ? "PENDING" : "SUCCESS";
    if (checks.path("total_count").asInt(count) > count
        || statuses.path("total_count").asInt(statuses.path("statuses").size())
            > statuses.path("statuses").size()) state = "PARTIAL";
    var current = api(prefix + "/pulls/" + n, Map.of());
    if (!sha.equals(value(current.path("head"), "sha"))) state = "STALE";
    return new Item(
        "ci:" + n,
        hash(sha + ":" + state + ":" + checks.path("check_runs") + ":" + statuses.path("state")),
        "CI",
        "PR #" + n + " CI (" + sha.substring(0, 12) + ")",
        "",
        state,
        clock.instant(),
        null,
        false,
        Map.of("number", Integer.toString(n), "headSha", sha, "parentId", "pr:" + n));
  }

  @Override
  public Page poll(SecretaryConnections.Connection c, String cursor) {
    String stage = "LIST";
    int page = 1, slot = 0;
    if (!cursor.isBlank())
      try {
        var state = JSON.readTree(java.util.Base64.getUrlDecoder().decode(cursor));
        stage = value(state, "stage");
        page = state.path("page").asInt(1);
        slot = state.path("slot").asInt();
        if (!Set.of("LIST", "REVIEWS", "UNFINISHED").contains(stage)
            || page < 1
            || page > 50
            || slot < 0) throw new IllegalArgumentException();
      } catch (java.io.IOException | IllegalArgumentException invalid) {
        throw new Failure("INVALID_CURSOR", 0, false);
      }
    var result = search(c, stage, Map.of("page", Integer.toString(page)));
    var items = new ArrayList<>(result.items());
    var prs =
        result.items().stream()
            .filter(
                i ->
                    i.kind().equals("PULL_REQUEST")
                        && !Set.of("CLOSED", "MERGED").contains(i.status()))
            .toList();
    if (!prs.isEmpty()) {
      int number = Integer.parseInt(prs.get(Math.floorMod(slot, prs.size())).data().get("number"));
      try {
        items.add(ci(c, number));
      } catch (Failure unavailable) {
        items.add(
            new Item(
                "ci:" + number,
                "unavailable:" + unavailable.code(),
                "CI",
                "PR #" + number + " CI unavailable",
                "",
                "UNKNOWN",
                clock.instant(),
                null,
                false,
                Map.of("number", Integer.toString(number), "reason", unavailable.code())));
      }
    }
    int nextPage = result.cursor().isBlank() ? 1 : Integer.parseInt(result.cursor());
    String nextStage =
        result.cursor().isBlank()
            ? (stage.equals("LIST") ? "REVIEWS" : stage.equals("REVIEWS") ? "UNFINISHED" : "LIST")
            : stage;
    try {
      String next =
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  JSON.writeValueAsBytes(
                      Map.of(
                          "stage",
                          nextStage,
                          "page",
                          nextPage,
                          "slot",
                          slot == Integer.MAX_VALUE ? 0 : slot + 1)));
      return new Page(items, next, result.partial());
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  @Override
  public Outcome write(
      SecretaryConnections.Connection connection,
      String operation,
      Map<String, String> arguments,
      String idempotencyKey) {
    throw new Failure("READ_ONLY", 0, false);
  }

  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    http.close();
  }
}
