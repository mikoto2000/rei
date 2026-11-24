package dev.mikoto2000.rei.llm;

/** Safe transport failure. Provider messages, bodies and causes must not escape this boundary. */
final class ResponsesTransportException extends IllegalStateException {
  enum Category {
    AUTHENTICATION,
    RATE_LIMIT,
    TRANSIENT,
    PROVIDER,
    TIMEOUT
  }

  private final Category category;

  ResponsesTransportException(Category category) {
    super("Responses transport failed: " + category);
    this.category = category;
  }

  Category category() {
    return category;
  }

  static RuntimeException normalize(Throwable error) {
    error = reactor.core.Exceptions.unwrap(error);
    while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null)
      error = error.getCause();
    if (error instanceof ResponsesTransportException failure) return failure;
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
    if (error instanceof RuntimeException runtime) return runtime;
    return new ResponsesTransportException(Category.PROVIDER);
  }
}
