package dev.mikoto2000.rei.http.cache;

import dev.mikoto2000.rei.http.*;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Conservative application cache: complete public direct responses only. */
final class HttpCacheRules {
  private static final Set<String> HEADERS = Set.of("accept", "accept-encoding", "user-agent", "cache-control");
  static boolean requestEligible(HttpResponseCache.Request request, SensitiveInfoDetector sensitive) {
    if (request.policy().allowPrivateOrigin() || request.headers().keySet().stream().anyMatch(name -> !HEADERS.contains(name))) return false;
    if (restricted(request.headers().get("cache-control"))) return false;
    String host = request.uri().getHost(); var literal = PublicNetworkPolicy.literal(host);
    if (host.equalsIgnoreCase("localhost") || literal != null && !PublicNetworkPolicy.isPublic(literal)) return false;
    try { return !sensitive.containsSensitiveInfo(URLDecoder.decode(request.uri().toASCIIString(), StandardCharsets.UTF_8)); }
    catch (IllegalArgumentException invalid) { return false; }
  }
  static boolean shareable(HttpResponseCache.Request request, SafeHttpFetcher.Response response, SensitiveInfoDetector sensitive) {
    if (!request.uri().equals(response.finalUri()) || restricted(response.header("cache-control"))
        || response.header("set-cookie") != null || Arrays.stream(Objects.toString(response.header("vary"), "").split(","))
            .anyMatch(value -> value.strip().equals("*"))) return false;
    String type = Objects.toString(response.header("content-type"), "").toLowerCase(Locale.ROOT);
    if (!type.isEmpty() && !type.startsWith("text/") && !type.contains("json") && !type.contains("xml")) return false;
    for (byte value : response.body()) if (value == 0) return false;
    return !sensitive.containsSensitiveInfo(new String(response.body(), StandardCharsets.UTF_8));
  }
  static boolean restricted(String control) {
    return directive(control, "no-store") || directive(control, "private") || directive(control, "must-understand");
  }
  static boolean directive(String value, String name) {
    if (value == null) return false;
    return Arrays.stream(value.split(",")).map(part -> part.strip().split("=", 2)[0].strip())
        .anyMatch(part -> part.equalsIgnoreCase(name));
  }
  static Long seconds(String value, String name) {
    if (value == null) return null;
    Long minimum = null;
    for (String part : value.split(",")) {
      String[] pair = part.strip().split("=", 2);
      if (!pair[0].strip().equalsIgnoreCase(name)) continue;
      long parsed = 0;
      try { String raw = pair.length < 2 ? "" : pair[1].strip().replace("\"", "");
        parsed = Long.parseLong(raw); if (parsed < 0) parsed = 0;
      } catch (NumberFormatException invalid) { /* Invalid lifetime is stale. */ }
      minimum = minimum == null ? parsed : Math.min(minimum, parsed);
    }
    return minimum;
  }
  static Instant date(String value) {
    try { return value == null ? null : ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
    catch (RuntimeException invalid) { return null; }
  }
  static Duration freshTtl(HttpResponseCache.Request request, SafeHttpFetcher.Response response, int configured,
      Instant requestTime, Instant received) {
    String control = response.header("cache-control"), requestControl = request.headers().get("cache-control");
    if (directive(control, "no-cache") || directive(requestControl, "no-cache")) return Duration.ZERO;
    Duration ttl = Duration.ofSeconds(configured);
    for (Long max : List.of(Optional.ofNullable(seconds(control, "max-age")), Optional.ofNullable(seconds(control, "s-maxage")),
        Optional.ofNullable(seconds(requestControl, "max-age"))).stream().flatMap(Optional::stream).toList())
      ttl = ttl.compareTo(Duration.ofSeconds(max)) > 0 ? Duration.ofSeconds(max) : ttl;
    Instant serverDate = date(response.header("date"));
    if (response.header("expires") != null && seconds(control, "max-age") == null && seconds(control, "s-maxage") == null) {
      Instant expires = date(response.header("expires")); if (expires == null) return Duration.ZERO;
      Duration lifetime = Duration.between(serverDate == null ? received : serverDate, expires);
      if (lifetime.compareTo(ttl) < 0) ttl = lifetime;
    }
    if (ttl.isNegative() || ttl.isZero()) return Duration.ZERO;
    Duration apparent = serverDate == null || serverDate.isAfter(received) ? Duration.ZERO : Duration.between(serverDate, received);
    long age = 0;
    if (response.header("age") != null) {
      try { age = Long.parseLong(response.header("age")); if (age < 0) return Duration.ZERO; }
      catch (NumberFormatException invalid) { return Duration.ZERO; }
    }
    age = Math.min(age, configured + 1L);
    Duration delay = received.isBefore(requestTime) ? Duration.ZERO : Duration.between(requestTime, received);
    Duration corrected = Duration.ofSeconds(age).plus(delay);
    Duration ageDuration = apparent.compareTo(corrected) > 0 ? apparent : corrected;
    ttl = ttl.minus(ageDuration); return ttl.isNegative() ? Duration.ZERO : ttl;
  }
  static Map<String, String> conditions(HttpResponseCache.Request request, SafeHttpFetcher.Response old, boolean force) {
    var headers = new TreeMap<>(request.headers());
    if (force || old != null) headers.merge("cache-control", "no-cache", (previous, value) -> previous + ", " + value);
    if (old != null) {
      String etag = old.header("etag");
      if (etag != null && etag.length() <= 1024 && etag.matches("(?:W/)?\"[^\"\\r\\n]*\"")) headers.put("if-none-match", etag);
      else if (date(old.header("last-modified")) != null) headers.put("if-modified-since", old.header("last-modified"));
    }
    return Map.copyOf(headers);
  }
  static SafeHttpFetcher.Response validated(SafeHttpFetcher.Response old, SafeHttpFetcher.Response response,
      Map<String, String> conditions, Instant now) {
    if (old == null || !old.finalUri().equals(response.finalUri())
        || !conditions.containsKey("if-none-match") && !conditions.containsKey("if-modified-since")) throw new HttpFetchException(HttpFetchException.Code.NETWORK_ERROR);
    String etag = response.header("etag"), expected = conditions.get("if-none-match");
    if (expected != null && etag != null && !etag.replaceFirst("^W/", "").equals(expected.replaceFirst("^W/", "")))
      throw new HttpFetchException(HttpFetchException.Code.NETWORK_ERROR);
    if (conditions.containsKey("if-modified-since")) {
      Instant changed = date(response.header("last-modified")), previous = date(conditions.get("if-modified-since"));
      if (changed != null && changed.isAfter(previous)) throw new HttpFetchException(HttpFetchException.Code.NETWORK_ERROR);
    }
    var headers = new HashMap<>(old.headers()); headers.remove("age"); headers.remove("date"); headers.putAll(response.headers());
    return new SafeHttpFetcher.Response(200, Map.copyOf(headers), old.body(), old.finalUri(), old.retrievedAt(), now);
  }
}
