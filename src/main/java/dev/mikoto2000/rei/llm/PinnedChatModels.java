package dev.mikoto2000.rei.llm;

import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.ai.chat.model.ChatModel;

/** Bounded native models sharing a host-owned transport, with no ambient connection lookup. */
final class PinnedChatModels implements AutoCloseable {
  private final Function<RunLlmConnection, String> credential;
  private final Consumer<RunLlmConnection> verify;
  private final dev.mikoto2000.rei.llm.capture.CaptureStore captures;
  private final org.springframework.util.ConcurrentLruCache<RunLlmConnection, ChatModel> cache;
  private final java.util.concurrent.atomic.AtomicBoolean closed =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final okhttp3.OkHttpClient transport =
      new okhttp3.OkHttpClient.Builder()
          .proxy(java.net.Proxy.NO_PROXY)
          .followRedirects(false)
          .followSslRedirects(false)
          .retryOnConnectionFailure(false)
          .build();

  PinnedChatModels(
      Function<RunLlmConnection, String> credential,
      Consumer<RunLlmConnection> verify,
      dev.mikoto2000.rei.llm.capture.CaptureStore captures,
      int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("Model cache capacity must be positive");
    this.credential = java.util.Objects.requireNonNull(credential);
    this.verify = java.util.Objects.requireNonNull(verify);
    this.captures = captures;
    this.cache = new org.springframework.util.ConcurrentLruCache<>(capacity, this::create);
  }

  ChatModel model(RunLlmConnection connection) {
    verify(connection);
    return cache.get(connection);
  }

  private ChatModel create(RunLlmConnection connection) {
    verify(connection);
    if (connection.endpoint().apiType() == LlmApiType.RESPONSES && connection.named())
      throw new IllegalStateException("Named Responses requires its verified profile policy");
    var client = client(connection, java.util.List.of());
    try {
      if (connection.endpoint().apiType() == LlmApiType.RESPONSES)
        return ResponsesAdapter.createWithClient(connection.endpoint(),
            (org.springframework.ai.openai.responses.OpenAiResponsesChatOptions) RunNativeOptions.options(connection),
            client, true);
      var model =
          org.springframework.ai.openai.OpenAiChatModel.builder()
              .options(
                  (org.springframework.ai.openai.OpenAiChatOptions)
                      RunNativeOptions.options(connection))
              .openAiClient(client)
              .openAiClientAsync(client.async())
              .build();
      return captures == null
          ? model
          : new CapturingChatModel(
              model, captures, interceptor -> client(connection, java.util.List.of(interceptor)));
    } catch (RuntimeException | Error failed) {
      client.close();
      throw failed;
    }
  }

  private com.openai.client.OpenAIClient client(
      RunLlmConnection connection, java.util.List<okhttp3.Interceptor> extra) {
    var interceptors = new java.util.ArrayList<okhttp3.Interceptor>();
    interceptors.add(new ChatStreamTimeoutInterceptor(connection.endpoint().readTimeout()));
    interceptors.add(new ShowUiSdkRequestInterceptor());
    interceptors.addAll(extra);
    return PinnedOpenAiClients.open(
        connection,
        () -> {
          verify(connection);
          return credential.apply(connection);
        },
        transport,
        interceptors,
        () -> verify(connection));
  }

  private void verify(RunLlmConnection connection) {
    if (closed.get()) throw new IllegalStateException("Run model transport is closed");
    verify.accept(connection);
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    cache.clear();
    transport.dispatcher().cancelAll();
    transport.connectionPool().evictAll();
    transport.dispatcher().executorService().shutdown();
  }
}
