package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.modeloptions.ChatPlanAccounts;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** One account admission per model request, inside the existing event and outage decoration. */
final class PlanRequestChatModel implements ChatModel {
  private final ChatModel delegate;
  private final Supplier<ChatPlanAccounts.Request> admission;
  private final java.util.function.Consumer<Throwable> observeFailure;

  PlanRequestChatModel(ChatModel delegate, Supplier<ChatPlanAccounts.Request> admission) {
    this(delegate, admission, ignored -> {});
  }

  PlanRequestChatModel(
      ChatModel delegate,
      Supplier<ChatPlanAccounts.Request> admission,
      java.util.function.Consumer<Throwable> observeFailure) {
    this.delegate = java.util.Objects.requireNonNull(delegate);
    this.admission = java.util.Objects.requireNonNull(admission);
    this.observeFailure = java.util.Objects.requireNonNull(observeFailure);
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    var result = new java.util.concurrent.atomic.AtomicReference<ChatResponse>();
    new org.springframework.ai.chat.model.MessageAggregator()
        .aggregate(stream(prompt), result::set)
        .blockLast();
    if (result.get() == null)
      throw new ResponsesTransportException(ResponsesTransportException.Category.INVALID_RESPONSE);
    return result.get();
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.using(
        admission::get,
        request -> {
          var invalidated =
              new java.util.concurrent.atomic.AtomicReference<ChatPlanAccounts.Invalidation>();
          return Flux.defer(() -> delegate.stream(prompt))
              .takeUntilOther(
                  reactor.core.publisher.Mono.fromCompletionStage(request.invalidated())
                      .doOnNext(invalidated::set))
              .concatWith(
                  Flux.defer(() ->
                      invalidated.get() == null
                          ? Flux.empty()
                          : Flux.error(new Invalidated(invalidated.get()))))
              .onErrorMap(ResponsesTransportException::nonRetryable)
              .doOnError(observeFailure);
        },
        ChatPlanAccounts.Request::close,
        true);
  }

  @Override
  public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
    return delegate.getOptions();
  }

  /** Fixed local guidance; no account label, credential, native request, or provider text. */
  private static final class Invalidated extends IllegalStateException
      implements dev.mikoto2000.rei.toolcontract.ExternalReadFailure {
    @java.io.Serial private static final long serialVersionUID = 1L;
    private final ChatPlanAccounts.Invalidation reason;

    private Invalidated(ChatPlanAccounts.Invalidation reason) {
      super("ChatGPT request invalidated: " + reason.name());
      this.reason = reason;
    }

    @Override
    public String diagnosticCode() {
      return switch (reason) {
        case STOPPED -> "chatgpt_cancelled";
        case SIGNED_OUT, REGISTRATION_CHANGED -> "chatgpt_auth_required";
        case AUTHENTICATION_INVALID -> "chatgpt_auth_invalid";
        case CONSENT_REQUIRED -> "chatgpt_consent_required";
      };
    }

    @Override
    public String diagnosticMessage() {
      return switch (reason) {
        case STOPPED -> "ChatGPT の呼び出しを停止しました。保存済みの状態を確認してください。自動再送は行いません。";
        case SIGNED_OUT, REGISTRATION_CHANGED ->
            "ChatGPT のサインイン状態が変わりました。アカウントと保存済みの状態を確認してください。自動再送は行いません。";
        case AUTHENTICATION_INVALID -> "ChatGPT の認証が無効になりました。再度サインインし、保存済みの状態を確認してください。自動再送は行いません。";
        case CONSENT_REQUIRED -> "ChatGPT の推論に必要な同意を確認してください。保存済みの状態を確認するまで自動再送は行いません。";
      };
    }
  }
}
