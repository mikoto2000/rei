package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.*;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Dedicated OAuth inference adapter: explicit request whitelist, no Tools, repair or retries. */
public final class PlanClient {
  static final int MODEL_CATALOG_LIMIT_BYTES = 2 * 1024 * 1024;
  static final int HTTP_ERROR_LIMIT_BYTES = 65536;

  public record Model(String id, String name) {}

  private record Catalog(String clientId, String subject, Instant expires, List<Model> models) {}

  private final PlanOAuth oauth;
  private final PlanTransport transport;
  private final Clock clock;
  private final Map<String, java.util.concurrent.atomic.AtomicInteger> generations =
      new ConcurrentHashMap<>();
  private final ObjectMapper json =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final Map<String, Catalog> catalog = new ConcurrentHashMap<>();

  public PlanClient(PlanOAuth oauth, PlanTransport transport, Clock clock) {
    this.oauth = oauth;
    this.transport = transport;
    this.clock = clock;
  }

  public List<Model> models(String account, boolean refresh) {
    var tokens = oauth.access(account);
    var saved = catalog.get(account);
    if (!refresh
        && saved != null
        && saved.clientId().equals(tokens.clientId())
        && saved.subject().equals(tokens.subject())
        && clock.instant().isBefore(saved.expires())) return saved.models();
    try (var response =
        transport.send(
            URI.create(PlanOAuth.RESOURCE + "/models"),
            "GET",
            Map.of(
                "Authorization",
                "Bearer " + tokens.accessToken(),
                "Accept",
                "application/json",
                "Accept-Encoding",
                "identity"),
            null,
            Duration.ofSeconds(20),
            () -> false)) {
      var duration = Duration.ofSeconds(20);
      if (response.status() != 200) throw httpError(response, "models", duration, () -> false);
      String mediaType = mediaType(response);
      if (!mediaType.equals("application/json") || !identityEncoding(response))
        throw failure(PROTOCOL, response, "models", -1, MODEL_CATALOG_LIMIT_BYTES);
      byte[] bytes =
          PlanIO.read(
              response.body(),
              duration,
              () -> false,
              () -> PlanIO.prefix(response.body(), MODEL_CATALOG_LIMIT_BYTES));
      if (bytes.length > MODEL_CATALOG_LIMIT_BYTES)
        throw failure(RECEIVE_LIMIT, response, "models", bytes.length, MODEL_CATALOG_LIMIT_BYTES);
      JsonNode node;
      try {
        node = json.readTree(bytes);
        if (node == null || !node.path("models").isArray()) throw new IllegalArgumentException();
      } catch (Exception invalid) {
        throw failure(PROTOCOL, response, "models", bytes.length, MODEL_CATALOG_LIMIT_BYTES);
      }
      var values = new ArrayList<Model>();
      var ids = new HashSet<String>();
      for (var model : node.path("models")) {
        if (!model.path("visibility").asText().equals("list")) continue;
        String id = PlanOAuth.required(model, "slug");
        if (!id.matches("[A-Za-z0-9_.-]{1,128}") || !ids.add(id))
          throw ChatGptException.of(PROTOCOL);
        values.add(new Model(id, PlanOAuth.required(model, "display_name")));
      }
      var result = List.copyOf(values);
      catalog.put(
          account,
          new Catalog(
              tokens.clientId(), tokens.subject(), clock.instant().plusSeconds(60), result));
      return result;
    } catch (ChatGptException e) {
      throw e;
    } catch (Exception e) {
      throw ChatGptException.of(PROTOCOL);
    }
  }

  public void invalidate() {
    catalog.clear();
  }

  public void stop(String account) {
    generations
        .computeIfAbsent(account, a -> new java.util.concurrent.atomic.AtomicInteger())
        .incrementAndGet();
  }

  public PlanSseAssembler.Result infer(
      String account,
      String model,
      String instructions,
      String task,
      Duration timeout,
      int sendLimit,
      int receiveLimit,
      BooleanSupplier cancelled) {
    return infer(
        account, model, instructions, task, timeout, sendLimit, receiveLimit, cancelled, () -> {});
  }

  public PlanSseAssembler.Result infer(
      String account,
      String model,
      String instructions,
      String task,
      Duration timeout,
      int sendLimit,
      int receiveLimit,
      BooleanSupplier cancelled,
      Runnable onDispatch) {
    long deadline = System.nanoTime() + timeout.toNanos();
    var generation =
        generations.computeIfAbsent(account, a -> new java.util.concurrent.atomic.AtomicInteger());
    int initialGeneration = generation.get();
    BooleanSupplier stop = () -> cancelled.getAsBoolean() || generation.get() != initialGeneration;
    if (stop.getAsBoolean()) throw ChatGptException.of(CANCELLED);
    if (models(account, false).stream().noneMatch(m -> m.id().equals(model)))
      throw ChatGptException.of(MODEL_UNAVAILABLE);
    byte[] body;
    try {
      body =
          json.writeValueAsBytes(
              Map.of(
                  "model",
                  model,
                  "instructions",
                  instructions,
                  "input",
                  List.of(Map.of("role", "user", "content", task)),
                  "store",
                  false,
                  "stream",
                  true));
    } catch (Exception e) {
      throw ChatGptException.of(PROTOCOL);
    }
    if (body.length > sendLimit) throw ChatGptException.of(SEND_LIMIT);
    var token = oauth.access(account);
    if (stop.getAsBoolean()) throw ChatGptException.of(CANCELLED);
    if (System.nanoTime() >= deadline) throw ChatGptException.of(TIMEOUT);
    onDispatch.run();
    try (var response =
        transport.send(
            URI.create(PlanOAuth.RESOURCE + "/responses"),
            "POST",
            Map.of(
                "Authorization",
                "Bearer " + token.accessToken(),
                "Content-Type",
                "application/json",
                "Accept",
                "text/event-stream",
                "Accept-Encoding",
                "identity"),
            body,
            Duration.ofNanos(deadline - System.nanoTime()),
            stop)) {
      var remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
      if (response.status() != 200) {
        throw httpError(response, "responses", remaining, stop);
      }
      String type = mediaType(response);
      if ((!type.isEmpty() && !type.equals("text/event-stream")) || !identityEncoding(response))
        throw failure(PROTOCOL, response, "responses", -1, receiveLimit);
      var assembler = new PlanSseAssembler();
      return PlanIO.read(
          response.body(),
          remaining,
          stop,
          () -> {
            try {
              return assembler.read(response.body(), receiveLimit, type.isEmpty());
            } catch (ChatGptException e) {
              throw failure(
                  e.code(), response, "responses", assembler.receivedBytes(), receiveLimit);
            }
          });
    }
  }

  private static String mediaType(PlanTransport.Response response) {
    return response.contentType() == null
        ? ""
        : response.contentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
  }

  private static boolean identityEncoding(PlanTransport.Response response) {
    return response.contentEncoding() == null
        || response.contentEncoding().isBlank()
        || response.contentEncoding().equalsIgnoreCase("identity");
  }

  private static ChatGptException failure(
      ChatGptException.Code code,
      PlanTransport.Response response,
      String operation,
      int received,
      int limit) {
    return ChatGptException.response(
        code, operation, response.status(), mediaType(response), received, limit, "");
  }

  private ChatGptException httpError(
      PlanTransport.Response response, String operation, Duration timeout, BooleanSupplier stop) {
    byte[] bytes =
        PlanIO.read(
            response.body(),
            timeout,
            stop,
            () -> PlanIO.prefix(response.body(), HTTP_ERROR_LIMIT_BYTES));
    String code = "";
    // A truncated or encoded body cannot establish an API code. Preserve the observed status.
    if (bytes.length <= HTTP_ERROR_LIMIT_BYTES
        && identityEncoding(response)
        && mediaType(response).equals("application/json")) {
      try {
        var node = json.readTree(bytes);
        if (node != null) code = node.path("error").path("code").asText();
      } catch (Exception ignored) {
      }
    }
    var classified = PlanSseAssembler.classify(code, response.status());
    if (classified == PROTOCOL && response.status() >= 500 && response.status() != 503)
      classified = TRANSPORT;
    return ChatGptException.response(
        classified,
        operation,
        response.status(),
        mediaType(response),
        bytes.length,
        HTTP_ERROR_LIMIT_BYTES,
        code);
  }
}
