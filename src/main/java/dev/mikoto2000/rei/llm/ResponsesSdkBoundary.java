package dev.mikoto2000.rei.llm;

import com.openai.client.OpenAIClientAsync;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.async.ResponseServiceAsync;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Sanitizes typed SDK errors before Spring AI's internal aggregator can log them. The SDK continues
 * to own HTTP, SSE decoding, cancellation and resource disposal.
 */
final class ResponsesSdkBoundary {
  private ResponsesSdkBoundary() {}

  static com.openai.client.OpenAIClient safe(
      com.openai.client.OpenAIClient client, ReasoningReplayMode replay) {
    return safe(client, replay, false);
  }

  static com.openai.client.OpenAIClient safe(
      com.openai.client.OpenAIClient client, ReasoningReplayMode replay, boolean plan) {
    return safe(client, replay, plan, null);
  }

  static com.openai.client.OpenAIClient safe(
      com.openai.client.OpenAIClient client,
      ReasoningReplayMode replay,
      boolean plan,
      ResponsesToolNamespace.Exchange namespace) {
    boolean plaintext = replay == ReasoningReplayMode.PLAINTEXT;
    var service = client.responses();
    var safeService =
        (com.openai.services.blocking.ResponseService)
            Proxy.newProxyInstance(
                com.openai.services.blocking.ResponseService.class.getClassLoader(),
                new Class<?>[] {com.openai.services.blocking.ResponseService.class},
                (proxy, method, arguments) -> {
                  try {
                    var result =
                        method.invoke(
                            service, requestArguments(arguments, replay, plan, namespace));
                    if (result instanceof com.openai.models.responses.Response response) {
                      var usage = validateResponse(response, plaintext, namespace);
                      if (response
                          .status()
                          .filter(com.openai.models.responses.ResponseStatus.FAILED::equals)
                          .isPresent()) throw responseFailure(response, usage, plan);
                    }
                    return result;
                  } catch (InvocationTargetException error) {
                    throw normalize(error.getCause(), plan);
                  }
                });
    return (com.openai.client.OpenAIClient)
        Proxy.newProxyInstance(
            com.openai.client.OpenAIClient.class.getClassLoader(),
            new Class<?>[] {com.openai.client.OpenAIClient.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("responses")) return safeService;
              try {
                return method.invoke(client, arguments);
              } catch (InvocationTargetException error) {
                throw normalize(error.getCause(), plan);
              }
            });
  }

  /** Remove only the SDK's automatic encrypted include through its typed public request API. */
  private static Object[] requestArguments(
      Object[] arguments,
      ReasoningReplayMode replay,
      boolean plan,
      ResponsesToolNamespace.Exchange namespace) {
    if (arguments == null) return new Object[0];
    var rewritten = arguments.clone();
    // Kotlin's JsonField<Nothing> singleton erases to a raw Java type. Missing holds no value.
    @SuppressWarnings("unchecked")
    com.openai.core.JsonField<java.util.List<com.openai.models.responses.ResponseIncludable>>
        missing =
            (com.openai.core.JsonField<
                    java.util.List<com.openai.models.responses.ResponseIncludable>>)
                (com.openai.core.JsonField<?>) com.openai.core.JsonMissing.of();
    for (int i = 0; i < rewritten.length; i++)
      if (rewritten[i] instanceof com.openai.models.responses.ResponseCreateParams request) {
        if (replay != ReasoningReplayMode.ENCRYPTED)
          request = request.toBuilder().include(missing).build();
        if (plan) request = ResponsesToolNamespace.request(request);
        rewritten[i] = namespace == null ? request : namespace.prepare(request);
      }
    return rewritten;
  }

  private static dev.mikoto2000.rei.modelmessage.ModelTokenUsage validateResponse(
      com.openai.models.responses.Response response,
      boolean plaintext,
      ResponsesToolNamespace.Exchange namespace) {
    try {
      var status = ResponsesTransportException.known(response._status());
      ResponsesTransportException.known(response._id());
      ResponsesTransportException.known(response._createdAt());
      var output = ResponsesTransportException.known(response._output());
      if (output != null)
        for (var item : output) {
          item.validate();
          requireToolCall(
              item, namespace, com.openai.models.responses.ResponseStatus.COMPLETED.equals(status));
          if (plaintext) validatePlaintextItem(item);
        }
      var nativeUsage = ResponsesTransportException.known(response._usage());
      var usage = ResponsesTransportException.usage(nativeUsage);
      if (usage != null
          && !com.openai.models.responses.ResponseStatus.FAILED.equals(status)
          && (usage.input() == null
              || usage.output() == null
              || usage.total() == null
              || usage.input() > Integer.MAX_VALUE
              || usage.output() > Integer.MAX_VALUE
              || usage.total() > Integer.MAX_VALUE))
        throw new ResponsesTransportException(
            ResponsesTransportException.Category.INVALID_RESPONSE, usage);
      return usage;
    } catch (RuntimeException invalid) {
      throw ResponsesTransportException.normalize(invalid);
    }
  }

  /** Validate the native namespace before Spring's mapper can discard it. */
  private static void requireToolCall(
      com.openai.models.responses.ResponseOutputItem output,
      ResponsesToolNamespace.Exchange namespace,
      boolean complete) {
    if (namespace != null) {
      namespace.accept(output, complete);
      return;
    }
    if (output.functionCall().filter(call -> call.namespace().isPresent()).isPresent())
      throw new ResponsesTransportException(ResponsesTransportException.Category.INVALID_RESPONSE);
  }

  /** Unknown plaintext fields cannot be dropped before the canonical ReasoningPart is stored. */
  private static void validatePlaintextItem(com.openai.models.responses.ResponseOutputItem output) {
    output
        .reasoning()
        .ifPresent(item -> {
          if (!java.util.Set.of("id", "type", "summary", "content", "encrypted_content", "status")
              .containsAll(item._allProperties().keySet()))
            throw new ResponsesTransportException(
                ResponsesTransportException.Category.INVALID_RESPONSE);
          for (var content : item.content().orElse(java.util.List.of()))
            if (!java.util.Set.of("type", "text").containsAll(content._allProperties().keySet())
                || !com.openai.core.JsonValue.from("reasoning_text").equals(content._type()))
              throw new ResponsesTransportException(
                  ResponsesTransportException.Category.INVALID_RESPONSE);
        });
  }

  static OpenAIClientAsync safe(OpenAIClientAsync client, ReasoningReplayMode replay) {
    return safe(client, replay, false);
  }

  static OpenAIClientAsync safe(
      OpenAIClientAsync client, ReasoningReplayMode replay, boolean plan) {
    return safe(client, replay, plan, null);
  }

  static OpenAIClientAsync safe(
      OpenAIClientAsync client,
      ReasoningReplayMode replay,
      boolean plan,
      ResponsesToolNamespace.Exchange namespace) {
    boolean plaintext = replay == ReasoningReplayMode.PLAINTEXT;
    var service = client.responses();
    var safeService =
        (ResponseServiceAsync)
            Proxy.newProxyInstance(
                ResponseServiceAsync.class.getClassLoader(),
                new Class<?>[] {ResponseServiceAsync.class},
                (proxy, method, arguments) -> {
                  try {
                    if (method.getName().equals("createStreaming")) {
                      @SuppressWarnings("unchecked")
                      var stream =
                          (AsyncStreamResponse<ResponseStreamEvent>)
                              method.invoke(
                                  service, requestArguments(arguments, replay, plan, namespace));
                      return new SafeStream(stream, plaintext, plan, namespace);
                    }
                    return method.invoke(
                        service, requestArguments(arguments, replay, plan, namespace));
                  } catch (InvocationTargetException error) {
                    throw normalize(error.getCause(), plan);
                  }
                });
    return (OpenAIClientAsync)
        Proxy.newProxyInstance(
            OpenAIClientAsync.class.getClassLoader(),
            new Class<?>[] {OpenAIClientAsync.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("responses")) return safeService;
              try {
                return method.invoke(client, arguments);
              } catch (InvocationTargetException error) {
                throw normalize(error.getCause(), plan);
              }
            });
  }

  /** Read the same typed delta fields Spring consumes before its aggregator can log SDK errors. */
  private static void validateStreamFields(
      ResponseStreamEvent event, ResponsesToolNamespace.Exchange namespace) {
    event.outputTextDelta().ifPresent(delta -> validateDelta(delta.delta(), delta.outputIndex()));
    event
        .reasoningTextDelta()
        .ifPresent(delta -> validateDelta(delta.delta(), delta.outputIndex()));
    event
        .reasoningSummaryTextDelta()
        .ifPresent(delta -> validateDelta(delta.delta(), delta.outputIndex()));
    event
        .functionCallArgumentsDelta()
        .ifPresent(delta -> validateDelta(delta.delta(), delta.outputIndex()));
    event.refusalDelta().ifPresent(delta -> validateDelta(delta.delta(), 0));
    event
        .outputItemDone()
        .ifPresent(done -> {
          validateDelta("", done.outputIndex());
          done.item().validate();
          requireToolCall(done.item(), namespace, true);
        });
    event
        .outputItemAdded()
        .ifPresent(added -> {
          validateDelta("", added.outputIndex());
          requireToolCall(added.item(), namespace, false);
          if (added.item().isFunctionCall()) {
            var call = added.item().asFunctionCall();
            validateDelta(call.callId(), 0);
            validateDelta(call.name(), 0);
          }
        });
  }

  private static void validateDelta(String delta, long index) {
    if (delta == null || index < 0 || index > Integer.MAX_VALUE)
      throw new ResponsesTransportException(ResponsesTransportException.Category.INVALID_RESPONSE);
  }

  private static ResponsesTransportException normalize(Throwable error, boolean plan) {
    return plan
        ? ResponsesTransportException.normalizePlan(error)
        : ResponsesTransportException.normalize(error);
  }

  private static ResponsesTransportException responseFailure(
      com.openai.models.responses.Response response,
      dev.mikoto2000.rei.modelmessage.ModelTokenUsage usage,
      boolean plan) {
    if (plan) {
      var code =
          response.error().map(error -> error.code()._value().asString().orElse("")).orElse("");
      var failure = ResponsesTransportException.planUsageFailure(code, usage);
      if (failure != null) return failure;
    }
    return new ResponsesTransportException(ResponsesTransportException.Category.PROVIDER, usage);
  }

  private record SafeStream(
      AsyncStreamResponse<ResponseStreamEvent> delegate,
      boolean plaintext,
      boolean plan,
      ResponsesToolNamespace.Exchange namespace)
      implements AsyncStreamResponse<ResponseStreamEvent> {
    private Handler<ResponseStreamEvent> safeHandler(Handler<? super ResponseStreamEvent> handler) {
      return new Handler<>() {
        @Override
        public void onNext(ResponseStreamEvent event) {
          try {
            validateStreamFields(event, namespace);
            if (plaintext && event.isOutputItemDone())
              validatePlaintextItem(event.outputItemDone().orElseThrow().item());
            if (plaintext && event.isOutputItemAdded())
              validatePlaintextItem(event.outputItemAdded().orElseThrow().item());
            if (event.isFailed())
              throw responseFailure(
                  event.failed().orElseThrow().response(),
                  validateResponse(event.failed().orElseThrow().response(), plaintext, namespace),
                  plan);
            if (event.isCompleted())
              validateResponse(event.completed().orElseThrow().response(), plaintext, namespace);
            if (event.isIncomplete())
              validateResponse(event.incomplete().orElseThrow().response(), plaintext, namespace);
            if (event.isCreated())
              validateResponse(event.created().orElseThrow().response(), plaintext, namespace);
            if (event.isError()) {
              if (plan) {
                var failure =
                    ResponsesTransportException.planUsageFailure(
                        event.error().orElseThrow().code().orElse(""), null);
                if (failure != null) throw failure;
              }
              throw new ResponsesTransportException(ResponsesTransportException.Category.PROVIDER);
            }
          } catch (RuntimeException invalid) {
            throw normalize(invalid, plan);
          }
          handler.onNext(event);
        }

        @Override
        public void onComplete(Optional<Throwable> error) {
          handler.onComplete(error.map(failure -> normalize(failure, plan)));
        }
      };
    }

    @Override
    public AsyncStreamResponse<ResponseStreamEvent> subscribe(
        Handler<? super ResponseStreamEvent> handler) {
      delegate.subscribe(safeHandler(handler));
      return this;
    }

    @Override
    public AsyncStreamResponse<ResponseStreamEvent> subscribe(
        Handler<? super ResponseStreamEvent> handler, Executor executor) {
      delegate.subscribe(safeHandler(handler), executor);
      return this;
    }

    @Override
    public CompletableFuture<Void> onCompleteFuture() {
      return delegate
          .onCompleteFuture()
          .handle((unused, error) -> {
            if (error != null) {
              while (error instanceof CompletionException && error.getCause() != null)
                error = error.getCause();
              throw new CompletionException(normalize(error, plan));
            }
            return null;
          });
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
