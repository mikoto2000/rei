package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.AUTH_REQUIRED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.CANCELLED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.MODEL_UNAVAILABLE;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.PROTOCOL;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.RECEIVE_LIMIT;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.SEND_LIMIT;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.TIMEOUT;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.TRANSPORT;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.values;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * 計画を外部の接続先へ依頼する窓口。
 *
 * <p>Dedicated OAuth inference adapter: explicit request whitelist, no Tools, repair or retries.
 */
public final class PlanClient implements PlanInference {
  /** モデル利用可能な項目上限バイト数。 */
  static final int MODEL_CATALOG_LIMIT_BYTES = 2 * 1024 * 1024;

  /** HTTP通信エラー上限バイト数。 */
  static final int HTTP_ERROR_LIMIT_BYTES = 65536;

  /**
   * ChatGPTとの連携で受け渡すモデルを表す。
   *
   * @param id 識別子
   * @param name 名前
   */
  public record Model(String id, String name) {}

  /**
   * ChatGPTとの連携で受け渡す入力を表す。
   *
   * @param role 役割
   * @param content 内容
   */
  public record Input(String role, String content) {
    /** 受け取った情報からInputを作成する。 */
    public Input {
      if (role == null || !Set.of("user", "assistant").contains(role) || content == null)
        throw new IllegalArgumentException("Explicit user/assistant text required");
    }

    /** ChatGPTとの連携の情報を、表示や診断で確認できる文字列表現にする。 */
    @Override
    public String toString() {
      return "Input[" + role + ", protected text]";
    }
  }

  /** 受け渡し先の形式に合わせて、要求を変換する。 */
  private static byte[] encodeRequest(String model, String instructions, List<Input> input) {
    try {
      return new ObjectMapper()
          .writeValueAsBytes(
              Map.of(
                  "model",
                  model,
                  "instructions",
                  instructions,
                  "input",
                  input,
                  "store",
                  false,
                  "stream",
                  true));
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw ChatGptException.of(PROTOCOL);
    }
  }

  /** ChatGPTとの連携で用いるencoded要求バイト数を返す。 */
  public static int encodedRequestBytes(String model, String instructions, List<Input> input) {
    return encodeRequest(model, instructions, input).length;
  }

  /**
   * ChatGPTとの連携で受け渡す利用可能な項目を表す。
   *
   * @param clientId クライアントの識別子
   * @param subject 対象
   * @param expires 有効期限
   * @param models モデル一覧
   */
  private record Catalog(String clientId, String subject, Instant expires, List<Model> models) {}

  /** OAuth認証。 */
  private final PlanOAuth oauth;

  /** 通信経路。 */
  private final PlanTransport transport;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 対象ごとの世代番号。 */
  private final Map<String, java.util.concurrent.atomic.AtomicInteger> generations =
      new ConcurrentHashMap<>();

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /** 利用可能な項目。 */
  private final Map<String, Catalog> catalog = new ConcurrentHashMap<>();

  /** 指定された値や利用先を使って、PlanClientを初期化する。 */
  public PlanClient(PlanOAuth oauth, PlanTransport transport, Clock clock) {
    this.oauth = oauth;
    this.transport = transport;
    this.clock = clock;
  }

  /** ChatGPTとの連携で用いるモデル一覧を返す。 */
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

  /** ChatGPTとの連携に関するキャッシュの無効化を処理する。 */
  public void invalidate() {
    catalog.clear();
  }

  /** 実行中の処理を停止する。 */
  public void stop(String account) {
    generations
        .computeIfAbsent(account, a -> new java.util.concurrent.atomic.AtomicInteger())
        .incrementAndGet();
  }

  /**
   * ChatGPTとの連携で受け渡す利用権を表す。
   *
   * <p>Own SIWC credentials only; a logout/stop invalidates an in-flight external process.
   *
   * @param tokens トークン数
   * @param valid 有効
   */
  public record Lease(TokenSet tokens, BooleanSupplier valid) {
    /** ChatGPTとの連携の情報を、表示や診断で確認できる文字列表現にする。 */
    @Override
    public String toString() {
      return "Lease[protected]";
    }
  }

  /** ChatGPTとの連携で用いる利用権を返す。 */
  public Lease lease(String account, String model) {
    var generation =
        generations.computeIfAbsent(account, a -> new java.util.concurrent.atomic.AtomicInteger());
    int initial = generation.get();
    if (models(account, false).stream().noneMatch(m -> m.id().equals(model)))
      throw ChatGptException.of(MODEL_UNAVAILABLE);
    var tokens = oauth.access(account);
    BooleanSupplier valid =
        () -> generation.get() == initial && clock.instant().getEpochSecond() < tokens.expiresAt();
    if (!valid.getAsBoolean()) throw ChatGptException.of(AUTH_REQUIRED);
    return new Lease(tokens, valid);
  }

  /** ChatGPTとの連携で用いる推定を返す。 */
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

  /** ChatGPTとの連携で用いる推定を返す。 */
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
    return inferHistory(
        account,
        model,
        instructions,
        List.of(new Input("user", task)),
        timeout,
        sendLimit,
        receiveLimit,
        cancelled,
        onDispatch);
  }

  /** ChatGPTとの連携で用いる推定履歴を返す。 */
  public PlanSseAssembler.Result inferHistory(
      String account,
      String model,
      String instructions,
      List<Input> input,
      Duration timeout,
      int sendLimit,
      int receiveLimit,
      BooleanSupplier cancelled,
      Runnable onDispatch) {
    input = List.copyOf(input);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (input.isEmpty() || input.size() > 16) throw ChatGptException.of(SEND_LIMIT);
    long deadline = System.nanoTime() + timeout.toNanos();
    var generation =
        generations.computeIfAbsent(account, a -> new java.util.concurrent.atomic.AtomicInteger());
    int initialGeneration = generation.get();
    BooleanSupplier stop = () -> cancelled.getAsBoolean() || generation.get() != initialGeneration;
    if (stop.getAsBoolean()) throw ChatGptException.of(CANCELLED);
    if (models(account, false).stream().noneMatch(m -> m.id().equals(model)))
      throw ChatGptException.of(MODEL_UNAVAILABLE);
    byte[] body = encodeRequest(model, instructions, input);
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

  /** ChatGPTとの連携で用いるメディア種別を返す。 */
  private static String mediaType(PlanTransport.Response response) {
    return response.contentType() == null
        ? ""
        : response.contentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
  }

  /** ChatGPTとの連携において、識別情報文字コードに該当するか判定する。 */
  private static boolean identityEncoding(PlanTransport.Response response) {
    return response.contentEncoding() == null
        || response.contentEncoding().isBlank()
        || response.contentEncoding().equalsIgnoreCase("identity");
  }

  /** 処理を継続できなかった理由を、失敗情報として表す。 */
  private static ChatGptException failure(
      ChatGptException.Code code,
      PlanTransport.Response response,
      String operation,
      int received,
      int limit) {
    return ChatGptException.response(
        code, operation, response.status(), mediaType(response), received, limit, "");
  }

  /** ChatGPTとの連携で用いるHTTP通信エラーを返す。 */
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
