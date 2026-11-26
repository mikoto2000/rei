package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.modelmessage.ModelTokenUsage;

/** Safe transport failure. Provider messages, bodies and causes must not escape this boundary. */
final class ResponsesTransportException extends IllegalStateException
    implements dev.mikoto2000.rei.toolcontract.ExternalReadFailure {
  @java.io.Serial private static final long serialVersionUID = 1L;

  enum Category {
    AUTHENTICATION,
    RATE_LIMIT,
    TRANSIENT,
    PROVIDER,
    REFUSAL,
    TIMEOUT,
    OUTPUT_LIMIT,
    REQUEST_LIMIT,
    RESPONSE_LIMIT,
    INCOMPLETE,
    INVALID_RESPONSE
  }

  /** Fixed codes survive existing user diagnostics without exposing SDK bodies or causes. */
  @Override
  public String diagnosticCode() {
    return switch (category) {
      case AUTHENTICATION -> "authentication_failed";
      case RATE_LIMIT -> "rate_limited";
      case TRANSIENT -> "provider_unavailable";
      case PROVIDER -> "provider_failure";
      case TIMEOUT -> "timeout";
      case OUTPUT_LIMIT -> "output_limit";
      case INCOMPLETE -> "response_incomplete";
      case REFUSAL -> "model_refusal";
      case INVALID_RESPONSE -> "invalid_response";
      case REQUEST_LIMIT -> "request_size_limit";
      case RESPONSE_LIMIT -> "response_size_limit";
    };
  }

  /** Only host-defined guidance crosses the shared diagnostic boundary. */
  @Override
  public String diagnosticMessage() {
    return switch (category) {
      case AUTHENTICATION -> "LLM の認証に失敗しました。接続先と認証設定を確認してください。";
      case RATE_LIMIT -> "LLM の呼び出し制限に達しました。利用枠と実行予算を確認してください。";
      case TRANSIENT -> "LLM へ接続できませんでした。接続先の稼働状況と実行結果を確認してください。";
      case PROVIDER -> "LLM が要求を処理できませんでした。明示した API とモデルの設定を確認してください。";
      case TIMEOUT -> "LLM の応答待ちがタイムアウトしました。保存済みの状態と接続先を確認してください。";
      case OUTPUT_LIMIT -> "LLM の出力上限に達しました。不完全な応答を使わず、出力設定と保存済みの状態を確認してください。";
      case INCOMPLETE -> "LLM の応答が完了しませんでした。不完全な応答を使わず、保存済みの状態を確認してください。";
      case REFUSAL -> "LLM が要求への回答を拒否しました。要求内容を確認してください。";
      case INVALID_RESPONSE -> "LLM の応答形式を検証できませんでした。API と SDK の互換性を確認してください。";
      case REQUEST_LIMIT -> "LLM への送信サイズが上限を超えました。入力と request-limit-bytes を確認してください。自動再送は行いません。";
      case RESPONSE_LIMIT ->
          "LLM の受信サイズが上限を超えました。不完全な応答を使わず、response-limit-bytes と保存済みの状態を確認してください。";
    };
  }

  private final Category category;
  private final ModelTokenUsage usage;
  private final boolean retryAllowed;

  @Override
  public boolean retryAllowed() {
    return retryAllowed;
  }

  ModelTokenUsage usage() {
    return usage;
  }

  ResponsesTransportException(Category category) {
    this(category, null);
  }

  ResponsesTransportException(Category category, ModelTokenUsage usage) {
    this(category, usage, true);
  }

  private ResponsesTransportException(
      Category category, ModelTokenUsage usage, boolean retryAllowed) {
    super("Responses transport failed: " + category);
    this.category = category;
    this.usage = usage;
    this.retryAllowed =
        retryAllowed
            && (category == Category.TRANSIENT
                || category == Category.RATE_LIMIT
                || category == Category.TIMEOUT);
  }

  static ModelTokenUsage usage(com.openai.models.responses.ResponseUsage usage) {
    if (usage == null) return null;
    var inputDetails = known(usage._inputTokensDetails());
    var outputDetails = known(usage._outputTokensDetails());
    try {
      return new ModelTokenUsage(
          exact(usage._inputTokens()),
          exact(usage._outputTokens()),
          exact(usage._totalTokens()),
          inputDetails == null ? null : exact(inputDetails._cachedTokens()),
          outputDetails == null ? null : exact(outputDetails._reasoningTokens()));
    } catch (IllegalArgumentException invalid) {
      throw new ResponsesTransportException(Category.INVALID_RESPONSE);
    }
  }

  static <T> T known(com.openai.core.JsonField<T> field) {
    if (field.isMissing() || field.isNull()) return null;
    return field
        .asKnown()
        .orElseThrow(() -> new ResponsesTransportException(Category.INVALID_RESPONSE));
  }

  private static Long exact(com.openai.core.JsonField<?> field) {
    if (field.isMissing() || field.isNull()) return null;
    var value =
        field
            .asNumber()
            .orElseThrow(() -> new ResponsesTransportException(Category.INVALID_RESPONSE));
    try {
      return new java.math.BigDecimal(value.toString()).longValueExact();
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw new ResponsesTransportException(Category.INVALID_RESPONSE);
    }
  }

  static ModelTokenUsage usage(org.springframework.ai.chat.metadata.Usage usage) {
    if (usage == null || usage instanceof org.springframework.ai.chat.metadata.EmptyUsage)
      return null;
    if (usage.getNativeUsage() instanceof com.openai.models.responses.ResponseUsage nativeUsage)
      return usage(nativeUsage);
    return new ModelTokenUsage(
        number(usage.getPromptTokens()),
        number(usage.getCompletionTokens()),
        number(usage.getTotalTokens()),
        null,
        null);
  }

  static ModelTokenUsage usage(org.springframework.ai.chat.model.ChatResponse response) {
    var usage = response.getMetadata().getUsage();
    if (usage != null
        && usage.getNativeUsage() instanceof com.openai.models.responses.ResponseUsage)
      return usage(usage);
    var generation = response.getResult();
    if (generation != null
        && generation.getOutput().getMetadata().get(ResponsesReasoningReplay.USAGE_KEY)
            instanceof ModelTokenUsage numeric) return numeric;
    return usage(usage);
  }

  private static Long number(Integer value) {
    return value == null ? null : value.longValue();
  }

  Category category() {
    return category;
  }

  static ResponsesTransportException normalize(Throwable error) {
    error = reactor.core.Exceptions.unwrap(error);
    while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null)
      error = error.getCause();
    Throwable nested = error;
    for (int depth = 0; nested != null && depth < 32; depth++, nested = nested.getCause())
      if (nested instanceof ResponsesTransportException failure) return failure;
    if (error instanceof com.openai.errors.OpenAIInvalidDataException
        || error instanceof IllegalArgumentException)
      return new ResponsesTransportException(Category.INVALID_RESPONSE);
    if (error instanceof com.openai.errors.OpenAIServiceException service) {
      int status = service.statusCode();
      return new ResponsesTransportException(
          status == 401 || status == 403
              ? Category.AUTHENTICATION
              : status == 429
                  ? Category.RATE_LIMIT
                  : status == 408 || status == 409 || status >= 500
                      ? Category.TRANSIENT
                      : Category.PROVIDER,
          null,
          !"false"
              .equals(service.headers().values("X-Should-Retry").stream().findFirst().orElse("")));
    }
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof java.util.concurrent.TimeoutException
          || cause instanceof java.net.SocketTimeoutException
          || cause instanceof java.net.http.HttpTimeoutException)
        return new ResponsesTransportException(Category.TIMEOUT);
    if (error instanceof com.openai.errors.OpenAIIoException)
      return new ResponsesTransportException(Category.TRANSIENT);
    if (error instanceof com.openai.errors.OpenAIException
        || error instanceof org.springframework.ai.openai.responses.OpenAiResponsesException)
      return new ResponsesTransportException(Category.PROVIDER);
    return new ResponsesTransportException(Category.PROVIDER);
  }
}
