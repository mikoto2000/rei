package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.modelmessage.ModelTokenUsage;

/** Safe transport failure. Provider messages, bodies and causes must not escape this boundary. */
final class ResponsesTransportException extends IllegalStateException {
  @java.io.Serial private static final long serialVersionUID = 1L;

  enum Category {
    AUTHENTICATION,
    RATE_LIMIT,
    TRANSIENT,
    PROVIDER,
    TIMEOUT,
    OUTPUT_LIMIT,
    INCOMPLETE,
    INVALID_RESPONSE
  }

  private final Category category;
  private final ModelTokenUsage usage;

  ModelTokenUsage usage() {
    return usage;
  }

  ResponsesTransportException(Category category) {
    this(category, null);
  }

  ResponsesTransportException(Category category, ModelTokenUsage usage) {
    super("Responses transport failed: " + category);
    this.category = category;
    this.usage = usage;
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
    if (error instanceof ResponsesTransportException failure) return failure;
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
                  : status >= 500 ? Category.TRANSIENT : Category.PROVIDER);
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
