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
 * Sanitizes typed SDK errors before Spring AI's internal aggregator can log them.
 * The SDK continues to own HTTP, SSE decoding, cancellation and resource disposal.
 */
final class ResponsesSdkBoundary {
  private ResponsesSdkBoundary() {}

  static OpenAIClientAsync safe(OpenAIClientAsync client) {
    var service = client.responses();
    var safeService = (ResponseServiceAsync) Proxy.newProxyInstance(
        ResponseServiceAsync.class.getClassLoader(), new Class<?>[] {ResponseServiceAsync.class},
        (proxy, method, arguments) -> {
          try {
            if (method.getName().equals("createStreaming")) {
              @SuppressWarnings("unchecked")
              var stream = (AsyncStreamResponse<ResponseStreamEvent>) method.invoke(service, arguments);
              return new SafeStream(stream);
            }
            return method.invoke(service, arguments);
          } catch (InvocationTargetException error) {
            throw ResponsesTransportException.normalize(error.getCause());
          }
        });
    return (OpenAIClientAsync) Proxy.newProxyInstance(OpenAIClientAsync.class.getClassLoader(),
        new Class<?>[] {OpenAIClientAsync.class}, (proxy, method, arguments) -> {
          if (method.getName().equals("responses")) return safeService;
          try {
            return method.invoke(client, arguments);
          } catch (InvocationTargetException error) {
            throw ResponsesTransportException.normalize(error.getCause());
          }
        });
  }

  private record SafeStream(AsyncStreamResponse<ResponseStreamEvent> delegate)
      implements AsyncStreamResponse<ResponseStreamEvent> {
    private Handler<ResponseStreamEvent> safeHandler(Handler<? super ResponseStreamEvent> handler) {
      return new Handler<>() {
        @Override
        public void onNext(ResponseStreamEvent event) {
          if (event.isError() || event.isFailed())
            throw new ResponsesTransportException(ResponsesTransportException.Category.PROVIDER);
          handler.onNext(event);
        }

        @Override
        public void onComplete(Optional<Throwable> error) {
          handler.onComplete(error.map(ResponsesTransportException::normalize));
        }
      };
    }

    @Override
    public AsyncStreamResponse<ResponseStreamEvent> subscribe(Handler<? super ResponseStreamEvent> handler) {
      delegate.subscribe(safeHandler(handler));
      return this;
    }

    @Override
    public AsyncStreamResponse<ResponseStreamEvent> subscribe(Handler<? super ResponseStreamEvent> handler,
        Executor executor) {
      delegate.subscribe(safeHandler(handler), executor);
      return this;
    }

    @Override
    public CompletableFuture<Void> onCompleteFuture() {
      return delegate.onCompleteFuture().handle((unused, error) -> {
        if (error != null) {
          while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
          throw new CompletionException(ResponsesTransportException.normalize(error));
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
