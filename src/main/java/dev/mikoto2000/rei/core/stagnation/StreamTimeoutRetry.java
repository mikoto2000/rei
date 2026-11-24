package dev.mikoto2000.rei.core.stagnation;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Retries only a timed-out model invocation; tools remain outside this boundary. */
final class StreamTimeoutRetry {
  private static final String RETRY = "rei.streamTimeoutRetry";
  private static final String DISCARDED = "rei.streamTimeoutDiscardedCharacters";

  private StreamTimeoutRetry() {}

  static Flux<ChatResponse> stream(
      ChatModel model,
      Prompt prompt,
      RunExecutionContext context,
      int maximum,
      Consumer<ChatResponse> completed) {
    return attempt(model, prompt, context, maximum, 0, completed);
  }

  private static Flux<ChatResponse> attempt(
      ChatModel model,
      Prompt prompt,
      RunExecutionContext context,
      int maximum,
      int attempt,
      Consumer<ChatResponse> completed) {
    return Flux.defer(() -> {
      context.checkActive();
      context.checkModelTokenBudget();
      var characters = new java.util.concurrent.atomic.AtomicInteger();
      return new MessageAggregator()
          .aggregate(
              Flux.defer(() -> model.stream(prompt))
                  .contextWrite(values ->
                      attempt > 0 ? values.put("rei.modelAdmission.waitForRecovery", true) : values)
                  .doOnNext(response -> {
                    var generation = response.getResult();
                    String text = generation == null ? null : generation.getOutput().getText();
                    if (text != null) characters.addAndGet(text.length());
                  }),
              completed)
          .onErrorResume(error -> {
            if (attempt >= maximum || !isReadTimeout(error)) return Flux.error(error);
            // Charge the failed invocation before admitting another one. Unknown usage with
            // a
            // token ceiling stops here, preserving the existing Run/Goal accounting rules.
            context.recordTotalTokens(null);
            context.checkActive();
            return Mono.delay(Duration.ofMillis(100L << attempt))
                .flatMapMany(ignored -> {
                  context.checkActive();
                  context.checkModelTokenBudget();
                  context.consumeNextLlmCall();
                  var marker =
                      new ChatResponse(
                          List.of(),
                          ChatResponseMetadata.builder()
                              .keyValue(RETRY, attempt + 1)
                              .keyValue(DISCARDED, characters.get())
                              .build());
                  return Flux.concat(
                      Flux.just(marker),
                      attempt(model, prompt, context, maximum, attempt + 1, completed));
                });
          });
    });
  }

  static boolean isReadTimeout(Throwable error) {
    // Follow only known transport/async wrappers; arbitrary tool/application errors are excluded.
    for (int depth = 0; error != null && depth < 16; depth++) {
      if (error instanceof dev.mikoto2000.rei.toolcontract.ExternalReadFailure failure)
        return failure.retryAllowed() && "timeout".equals(failure.diagnosticCode());
      if (error instanceof SocketTimeoutException || error instanceof HttpTimeoutException)
        return true;
      if (!(error instanceof CompletionException
          || error instanceof ExecutionException
          || error instanceof com.openai.errors.OpenAIIoException)) return false;
      error = error.getCause();
    }
    return false;
  }
}
