package dev.mikoto2000.rei.llm;

/** Safe transport failure. Provider messages, bodies and causes must not escape this boundary. */
final class ResponsesTransportException extends IllegalStateException {
  enum Category {
    AUTHENTICATION, RATE_LIMIT, TRANSIENT, PROVIDER, TIMEOUT
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
    if (error instanceof ResponsesTransportException failure) return failure;
    if (error instanceof com.openai.errors.OpenAIServiceException service) {
      int status = service.statusCode();
      return new ResponsesTransportException(status == 401 || status == 403
          ? Category.AUTHENTICATION : status == 429 ? Category.RATE_LIMIT
          : status >= 500 ? Category.TRANSIENT : Category.PROVIDER);
    }
    if (error instanceof java.util.concurrent.TimeoutException)
      return new ResponsesTransportException(Category.TIMEOUT);
    if (error instanceof com.openai.errors.OpenAIException
        || error instanceof org.springframework.ai.openai.responses.OpenAiResponsesException)
      return new ResponsesTransportException(Category.PROVIDER);
    if (error instanceof RuntimeException runtime) return runtime;
    return new ResponsesTransportException(Category.PROVIDER);
  }
}
