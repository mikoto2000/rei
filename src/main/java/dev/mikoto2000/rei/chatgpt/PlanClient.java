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
  public record Model(String id, String name) {}

  private record Catalog(String clientId, String subject, Instant expires, List<Model> models) {}

  private final PlanOAuth oauth;
  private final PlanTransport transport;
  private final Clock clock;
  private final Map<String, java.util.concurrent.atomic.AtomicInteger> generations =
      new ConcurrentHashMap<>();
  private final ObjectMapper json = new ObjectMapper();
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
            Map.of("Authorization", "Bearer " + tokens.accessToken()),
            null,
            Duration.ofSeconds(20),
            () -> false)) {
      byte[] bytes =
          PlanIO.read(
              response.body(),
              Duration.ofSeconds(20),
              () -> false,
              () -> PlanIO.bytes(response.body(), 262144));
      check(response.status(), bytes);
      var node = json.readTree(bytes);
      if (!node.path("models").isArray()) throw ChatGptException.of(PROTOCOL);
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
                "text/event-stream"),
            body,
            Duration.ofNanos(deadline - System.nanoTime()),
            stop)) {
      var remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
      if (response.status() != 200) {
        byte[] error =
            PlanIO.read(
                response.body(), remaining, stop, () -> PlanIO.bytes(response.body(), 65536));
        check(response.status(), error);
      }
      if (!response.contentType().toLowerCase(Locale.ROOT).startsWith("text/event-stream"))
        throw ChatGptException.of(PROTOCOL);
      return PlanIO.read(
          response.body(),
          remaining,
          stop,
          () -> new PlanSseAssembler().read(response.body(), receiveLimit));
    }
  }

  private void check(int status, byte[] bytes) {
    if (status == 200) return;
    String code = "";
    try {
      code = json.readTree(bytes).path("error").path("code").asText();
    } catch (Exception ignored) {
    }
    throw ChatGptException.of(PlanSseAssembler.classify(code, status));
  }
}
