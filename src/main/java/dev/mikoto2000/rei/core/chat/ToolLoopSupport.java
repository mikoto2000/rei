package dev.mikoto2000.rei.core.chat;

import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.toolcontract.ToolResultRejectedException;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;

/**
 * 対話中のツール実行を補助し、結果の引き継ぎや再試行の条件を整える。
 *
 * <p>Shared Spring AI tool dispatch boundary for explicit application-owned loops.
 */
public final class ToolLoopSupport {
  /** Give each existing loop iteration a fresh immutable message identity. */
  public static Prompt newIteration(Prompt prompt) {
    var boundary = exchange(prompt);
    if (boundary == null) return prompt;
    return withExchange(prompt, new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange(
        boundary.store(), boundary.scope(), boundary.run(), java.util.UUID.randomUUID().toString()));
  }

  private static Prompt withExchange(Prompt prompt,
      dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange boundary) {
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options))
      throw new IllegalStateException("Missing model message persistence options");
    var values = options.getToolContext();
    if (values == null) throw new IllegalStateException("Missing model message persistence context");
    var context = new java.util.HashMap<String, Object>(values);
    context.put(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
        boundary);
    return new Prompt(prompt.getInstructions(), options.mutate().toolContext(context).build());
  }

  /** Called only after the existing terminal, integrity and usage checks. */
  public static dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Ref persistResponse(
      Prompt prompt, ChatResponse response) {
    var generation = response.getResult();
    if (generation == null) throw new IllegalArgumentException("Missing model result");
    return persist(prompt, generation.getOutput());
  }

  /** Recognize internal retry control frames without treating them as assistant content. */
  public static int streamRetryAttempt(ChatResponse response) {
    Object value = response.getMetadata().get("rei.streamTimeoutRetry");
    return value instanceof Integer attempt ? attempt : 0;
  }

  public static int streamRetryDiscardedCharacters(ChatResponse response) {
    Object value = response.getMetadata().get("rei.streamTimeoutDiscardedCharacters");
    return value instanceof Integer count ? count : 0;
  }

  /** Capture delivered evidence at the existing tool-loop boundary, without replaying tools. */
  public String captureOutputLimitHandoff(
      java.util.List<org.springframework.ai.chat.messages.Message> delivered,
      String partial,
      String review,
      String previous) {
    return OutputLimitHandoff.capture(delivered, partial, review, previous);
  }

  /**
   * モデルに暗黙のツール権限が付いていないことを確認し、依頼ごとの明示的な登録だけを許す。
   *
   * <p>Refuse ambient tool authority; isolated requests must explicitly supply their callbacks.
   */
  public static void requireNoDefaultTools(org.springframework.ai.chat.model.ChatModel model) {
    var options = model.getOptions();
    requireNoRawTools(options);
    if (options instanceof ToolCallingChatOptions tools
        && !org.springframework.util.CollectionUtils.isEmpty(tools.getToolCallbacks())) {
      throw new IllegalArgumentException("SubAgent model must not carry default tools");
    }
  }

  /**
   * 追加のモデル設定にツールを紛れ込ませ、れいの権限確認を迂回することを防ぐ。
   *
   * <p>SDK extra-body parameters must not bypass Rei's explicit callback/permission boundary.
   */
  public static void requireNoRawTools(ChatOptions options) {
    if (options instanceof org.springframework.ai.openai.OpenAiChatOptions openAi) {
      var extraBody = openAi.getExtraBody();
      Object rawTools = extraBody == null ? null : extraBody.get("tools");
      if (rawTools != null
          && !(rawTools instanceof java.util.Collection<?> tools && tools.isEmpty())) {
        throw new IllegalArgumentException("Model must not carry raw default tools");
      }
    }
  }

  // 実行回数と時間は、れいが保持する予算で制限する。
  // 履歴に依存するライブラリー側の制限で、追加入力時に予算がリセットされたり、
  // 実行結果を保存する前にツール群が中断されたりすることを防ぐ。
  /** 管理。 */
  private static final DefaultToolExecutionExceptionProcessor DEFAULT_TOOL_ERRORS =
      DefaultToolExecutionExceptionProcessor.builder().build();

  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  private final ToolCallingManager manager =
      ToolCallingManager.builder()
          .resolutionFallbackEnabled(false)
          .unlimitedCallsPerTool()
          .unlimitedTotalToolCalls()
          .toolExecutionExceptionProcessor(ToolLoopSupport::toolFailure)
          .build();

  private static String toolFailure(ToolExecutionException error) {
    if (!Thread.currentThread().isInterrupted() && !RunExecutionContext.isControlFailure(error)) {
      var rejected = ToolResultRejectedException.find(error);
      if (rejected != null)
        return RunExecutionContext.toolRejectionResult(
            rejected,
            "Correct only this rejected call and preserve successful sibling results.",
            null);
    }
    return DEFAULT_TOOL_ERRORS.process(error);
  }

  /** 要求された処理を実行する。 */
  public ToolExecutionResult execute(Prompt prompt, ChatResponse response) {
    if (truncated(response))
      return rejectedBatch(prompt, "The output limit interrupted this tool batch. No calls ran.");
    if (hasInvalidToolCallIds(response)) return invalidToolCallIds(prompt);
    var generation = response.getResult();
    var output = generation == null ? null : generation.getOutput();
    if (output == null) throw new IllegalArgumentException("Missing generation for tool calls");
    persist(prompt, output);
    response = inheritFileContinuations(prompt, response);
    var lease = RunCancellation.enterChild(prompt);
    try (lease) {
      var readBudget = dev.mikoto2000.rei.tooldelivery.FileReadContext.open(prompt, response);
      try (readBudget) {
        return deliveryMetadata(prompt, manager.executeToolCalls(prompt, response));
      }
    }
  }

  private static dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange exchange(
      Prompt prompt) {
    if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
      var toolContext = options.getToolContext();
      if (toolContext == null) return null;
      Object value =
          toolContext.get(
              dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
      if (value == null) return null;
      if (!(value
          instanceof dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange exchange))
        throw new IllegalStateException("Invalid model message persistence boundary");
      return exchange;
    }
    return null;
  }

  private static dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Ref persist(
      Prompt prompt, org.springframework.ai.chat.messages.Message message) {
    var boundary = exchange(prompt);
    if (boundary == null && dev.mikoto2000.rei.modelmessage.ModelMessageCodec.requiresPayload(message))
      throw new IllegalStateException("Model message persistence required before tool dispatch");
    return boundary == null ? null : boundary.reference(message);
  }

  private static boolean truncated(ChatResponse response) {
    var generation = response.getResult();
    return generation != null
        && "length"
            .equalsIgnoreCase(
                java.util.Objects.toString(generation.getMetadata().getFinishReason(), "").strip());
  }

  private ChatResponse inheritFileContinuations(Prompt prompt, ChatResponse response) {
    var generations =
        response.getResults().stream()
            .map(generation -> {
              var output = generation.getOutput();
              var calls = FileReadContinuation.inherit(prompt, output.getToolCalls());
              if (java.util.Objects.equals(calls, output.getToolCalls())) return generation;
              var replacements = calls.iterator();
              var parts =
                  output.getParts().stream()
                      .map(part -> {
                        if (part
                                instanceof
                                org.springframework.ai.chat.messages.part.ToolCallPart call
                            && !org.springframework.ai.chat.messages.part.StreamingParts.isPartial(
                                call))
                          return (org.springframework.ai.chat.messages.part.MessagePart)
                              new org.springframework.ai.chat.messages.part.ToolCallPart(
                                  replacements.next(), call.payload(), call.attributes());
                        return part;
                      })
                      .toList();
              return new org.springframework.ai.chat.model.Generation(
                  org.springframework.ai.chat.messages.AssistantMessage.builder()
                      .parts(parts)
                      .properties(output.getMetadata())
                      .build(),
                  generation.getMetadata());
            })
            .toList();
    return new ChatResponse(generations, response.getMetadata());
  }

  /** 会話の実行で用いる配信付随情報を返す。 */
  private ToolExecutionResult deliveryMetadata(Prompt prompt, ToolExecutionResult result) {
    return deliveryMetadata(prompt, result, java.util.Set.of());
  }

  /** 会話の実行で用いる配信付随情報を返す。 */
  private ToolExecutionResult deliveryMetadata(
      Prompt prompt, ToolExecutionResult result, java.util.Set<String> argumentCorrections) {
    return deliveryMetadata(prompt, result, argumentCorrections, null);
  }

  private ToolExecutionResult deliveryMetadata(Prompt prompt, ToolExecutionResult result,
      java.util.Set<String> argumentCorrections, ToolResponseMessage savedPrefix) {
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) return result;
    var toolCallbacks = options.getToolCallbacks();
    if (toolCallbacks == null) return result;
    var policies =
        new java.util.HashMap<String, dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind>();
    for (var callback : toolCallbacks)
      policies.put(
          callback.getToolDefinition().name(),
          dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.kind(callback));
    var history = new java.util.ArrayList<>(result.conversationHistory());
    var registered = new java.util.HashMap<String, org.springframework.ai.tool.ToolCallback>();
    toolCallbacks.forEach(
        callback -> registered.put(callback.getToolDefinition().name(), callback));
    boolean direct = result.returnDirect();
    if (!history.isEmpty()
        && history.getLast()
            instanceof org.springframework.ai.chat.messages.ToolResponseMessage message) {
      var calls =
          new java.util.HashMap<
              String, dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind>();
      for (var response : message.getResponses()) {
        boolean rejected =
            argumentCorrections.contains(response.id()) || declaredRejection(response);
        if (rejected) direct = false;
        calls.put(
            response.id(),
            rejected
                ? dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind.GENERAL
                : policies.getOrDefault(
                    response.name(),
                    dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind.GENERAL));
      }
      var receipts = new java.util.HashMap<String, dev.mikoto2000.rei.review.ReviewTools.Receipt>();
      for (var response : message.getResponses())
        if (registered.get(response.name())
                instanceof
                dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.Registered callback
            && callback.reviewPage())
          try {
            receipts.put(
                response.id(),
                dev.mikoto2000.rei.review.ReviewTools.receipt(response.responseData()));
          } catch (IllegalArgumentException malformed) {
            /* ツールの失敗や引数の修正案内を、資料を読み終えた証拠として扱わない。 */
          }
      var metadata = new java.util.HashMap<>(message.getMetadata());
      metadata.put("rei.deliveryPolicies", java.util.Map.copyOf(calls));
      if (message.getResponses().stream().anyMatch(ToolLoopSupport::reviewBudgetRejected))
        metadata.put("rei.reviewBudgetRecovery", true);
      if (message.getResponses().stream().anyMatch(ToolLoopSupport::fileBudgetRejected))
        metadata.put("rei.fileBudgetRecovery", true);
      var readGrant = dev.mikoto2000.rei.tooldelivery.FileReadContext.grant();
      if (readGrant != null) {
        var grants =
            new java.util.HashMap<String, dev.mikoto2000.rei.tooldelivery.FileReadContext.Grant>();
        for (var response : message.getResponses())
          if (response.name().equals("readMultiFile")
              && calls.get(response.id())
                  == dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind.FILE_CONTENT)
            grants.put(response.id(), readGrant);
        if (!grants.isEmpty()) metadata.put("rei.fileReadBudgets", java.util.Map.copyOf(grants));
      }
      if (!receipts.isEmpty()) metadata.put("rei.reviewReceipts", java.util.Map.copyOf(receipts));
      if (savedPrefix != null) restorePrefixDelivery(metadata, savedPrefix);
      history.set(
          history.size() - 1,
          org.springframework.ai.chat.messages.ToolResponseMessage.builder()
              .responses(message.getResponses())
              .metadata(metadata)
              .build());
    }
    if (!history.isEmpty() && history.getLast() instanceof ToolResponseMessage message)
      persist(prompt, message);
    return ToolExecutionResult.builder().conversationHistory(history).returnDirect(direct).build();
  }

  private static void restorePrefixDelivery(java.util.Map<String, Object> metadata, ToolResponseMessage prefix) {
    var ids = prefix.getResponses().stream().map(ToolResponseMessage.ToolResponse::id)
        .collect(java.util.stream.Collectors.toSet());
    for (var key : java.util.List.of("rei.deliveryPolicies", "rei.losslessPages", "rei.reviewReceipts", "rei.fileReadBudgets")) {
      var merged = new java.util.HashMap<String, Object>();
      if (metadata.get(key) instanceof java.util.Map<?, ?> current)
        current.forEach((id, value) -> {
          if (id instanceof String name && !ids.contains(name)) merged.put(name, value);
        });
      if (prefix.getMetadata().get(key) instanceof java.util.Map<?, ?> previous)
        previous.forEach((id, value) -> {
          if (id instanceof String name && ids.contains(name)) merged.put(name, value);
        });
      if (merged.isEmpty()) metadata.remove(key);
      else metadata.put(key, java.util.Map.copyOf(merged));
    }
  }

  private dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Ref preserveApprovalPrefix(
      Prompt prompt, RunContinuation.PendingTools pending,
      java.util.List<ToolResponseMessage.ToolResponse> completed, java.util.Set<String> corrections,
      ToolResponseMessage savedPrefix) {
    if (pending.assistantReference() == null || completed.isEmpty()) return null;
    var boundary = java.util.Objects.requireNonNull(exchange(prompt), "Missing model message persistence boundary");
    var prefixPrompt = withExchange(prompt, new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange(
        boundary.store(), boundary.scope(), boundary.run(), boundary.key() + "/prefix:" + completed.size()));
    var result = deliveryMetadata(prefixPrompt, ToolExecutionResult.builder()
        .conversationHistory(java.util.List.of(ToolResponseMessage.builder().responses(completed).build()))
        .returnDirect(false).build(), corrections, savedPrefix);
    return java.util.Objects.requireNonNull(exchange(prefixPrompt)).reference(result.conversationHistory().getLast());
  }

  private static boolean declaredRejection(ToolResponseMessage.ToolResponse response) {
    String text = response.responseData();
    if (text == null || !text.startsWith("{") || !text.contains("\"validationExecuted\""))
      return false;
    try {
      var result = JSON.readTree(text);
      return result.isObject()
          && result.path("error").isString()
          && result.path("accepted").isBoolean()
          && !result.path("accepted").asBoolean()
          && result.path("validationExecuted").asBoolean(false);
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private static boolean reviewBudgetRejected(ToolResponseMessage.ToolResponse response) {
    if (!java.util.Set.of(
                "readReviewPages",
                "readReviewWorkItem",
                "claimReviewWorkItem",
                "readNextReviewWorkItem",
                "readNextReviewWorkItems")
            .contains(response.name())
        || !declaredRejection(response)) return false;
    var result =
        tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.responseData());
    return java.util.Set.of(
            "CONTEXT_BUDGET_INSUFFICIENT",
            "REVIEW_PAGE_BUDGET_TOO_SMALL",
            "REVIEW_BATCH_BUDGET_TOO_SMALL")
        .contains(result.path("error").asString());
  }

  private static boolean fileBudgetRejected(ToolResponseMessage.ToolResponse response) {
    return java.util.Set.of("readMultiFile", "readPdfPage").contains(response.name())
        && declaredRejection(response)
        && "CONTEXT_BUDGET_INSUFFICIENT"
            .equals(JSON.readTree(response.responseData()).path("error").asString());
  }

  /** 承認による中断や引数の修正を扱える実行経路を選び、未完了のツール呼び出しを進める。 */
  public ToolExecutionResult executeResumable(
      Prompt prompt,
      ChatResponse response,
      dev.mikoto2000.rei.core.stagnation.RunExecutionContext context) {
    if (truncated(response))
      return rejectedBatch(prompt, "The output limit interrupted this tool batch. No calls ran.");
    for (var candidate : response.getResults()) {
      var candidateIds = new java.util.HashSet<String>();
      for (var call : candidate.getOutput().getToolCalls()) {
        if (!validToolCallId(call.id())) return rejectedBatch(prompt, invalidToolCallIdGuidance());
        if (!candidateIds.add(call.id()))
          return rejectedBatch(prompt, context.duplicateToolIdCorrection(call.id(), call.name()));
      }
    }
    var generation = response.getResult();
    var output = generation == null ? null : generation.getOutput();
    if (output == null) throw new IllegalArgumentException("Missing generation for tool calls");
    for (var call : output.getToolCalls()) {
      if (registeredCallback(prompt, call.name()) == null) {
        // Reject the batch before dispatch and keep invalid tool-call messages out of history.
        return rejectedBatch(prompt, context.unavailableToolCorrection(call.id(), call.name()));
      }
    }
    if (!context.hasApprovalContinuation()
        && output.getToolCalls().stream()
            .noneMatch(call ->
                context.supportsArgumentCorrection(call.name(), schema(prompt, call.name()))))
      return execute(prompt, response);
    if (response.getResults().size() != 1)
      throw new IllegalArgumentException(
          "Approval continuation requires a single selected generation");
    var originalReference = persist(prompt, output);
    var executed =
        executePending(
            prompt,
            new RunContinuation.PendingTools(
                java.util.Objects.toString(output.getText(), ""),
                output.getToolCalls(),
                java.util.List.of(),
                java.util.Set.of(),
                originalReference),
            context,
            false);
    // The execution view may repair arguments, but the model's original ordered parts
    // remain the source for the next protocol request.
    var history = new java.util.ArrayList<>(executed.conversationHistory());
    history.set(prompt.getInstructions().size(), output);
    return ToolExecutionResult.builder()
        .conversationHistory(history)
        .returnDirect(executed.returnDirect())
        .build();
  }

  static ToolExecutionResult rejectedBatch(Prompt prompt, String guidance) {
    var history = new java.util.ArrayList<>(prompt.getInstructions());
    history.add(new org.springframework.ai.chat.messages.SystemMessage(guidance));
    return ToolExecutionResult.builder().conversationHistory(history).returnDirect(false).build();
  }

  private static boolean validToolCallId(String id) {
    return id != null && !id.isBlank();
  }

  private static String invalidToolCallIdGuidance() {
    return "The previous response used missing, blank, or duplicate tool-call IDs. No calls"
        + " from that response ran. Reissue still-needed calls with a distinct, nonblank"
        + " ID for each call.";
  }

  private String schema(Prompt prompt, String name) {
    var callback = registeredCallback(prompt, name);
    return callback == null ? null : callback.getToolDefinition().inputSchema();
  }

  private static boolean hasInvalidToolCallIds(ChatResponse response) {
    for (var generation : response.getResults()) {
      var ids = new java.util.HashSet<String>();
      for (var call : generation.getOutput().getToolCalls()) {
        if (!addToolCallId(ids, call.id())) return true;
      }
    }
    return false;
  }

  private static boolean addToolCallId(java.util.Set<String> ids, String id) {
    return id != null && !id.isBlank() && ids.add(id);
  }

  private static ToolExecutionResult invalidToolCallIds(Prompt prompt) {
    var history = new java.util.ArrayList<>(prompt.getInstructions());
    history.add(
        new org.springframework.ai.chat.messages.SystemMessage(
            "The previous response used missing, blank, or duplicate tool-call IDs. No calls"
                + " from that response ran. Reissue still-needed calls with a distinct, nonblank"
                + " ID for each call."));
    return ToolExecutionResult.builder().conversationHistory(history).returnDirect(false).build();
  }

  private org.springframework.ai.tool.ToolCallback registeredCallback(Prompt prompt, String name) {
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) return null;
    var callbacks = options.getToolCallbacks();
    if (callbacks == null) return null;
    return callbacks.stream()
        .filter(callback -> callback.getToolDefinition().name().equals(name))
        .findFirst()
        .orElse(null);
  }

  /** 保存済みのツール呼び出しについて、成功済みの結果を引き継ぎながら残りを実行する。 */
  public ToolExecutionResult executePending(
      Prompt prompt,
      RunContinuation.PendingTools pending,
      dev.mikoto2000.rei.core.stagnation.RunExecutionContext context) {
    return executePending(prompt, pending, context, true);
  }

  /** 保存済みのツール呼び出しについて、成功済みの結果を引き継ぎながら残りを実行する。 */
  private ToolExecutionResult executePending(
      Prompt prompt,
      RunContinuation.PendingTools pending,
      dev.mikoto2000.rei.core.stagnation.RunExecutionContext context,
      boolean replay) {
    var readBudget =
        dev.mikoto2000.rei.tooldelivery.FileReadContext.open(
            prompt, pending.calls().size(), pending.text() + pending.calls());
    try (readBudget) {
      return executePendingWithinBudget(prompt, pending, context, replay);
    }
  }

  private ToolExecutionResult executePendingWithinBudget(
      Prompt prompt,
      RunContinuation.PendingTools pending,
      dev.mikoto2000.rei.core.stagnation.RunExecutionContext context,
      boolean replay) {
    if (replay && pending.schemaVersion() == 0 && exchange(prompt) != null)
      throw new IllegalStateException("Legacy pending tools cannot enter a protected protocol Run");
    org.springframework.ai.chat.messages.AssistantMessage original = null;
    ToolResponseMessage savedPrefix = null;
    if (pending.assistantReference() != null) {
      var boundary = exchange(prompt);
      if (boundary == null)
        throw new IllegalStateException("Missing model message persistence boundary");
      var saved =
          boundary
              .store()
              .load(
                  boundary.scope(),
                  pending.assistantReference(),
                  dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN,
                  boundary.run());
      if (!(saved instanceof org.springframework.ai.chat.messages.AssistantMessage assistant)
          || assistant.getToolCalls().size() != pending.calls().size())
        throw new IllegalStateException("Invalid pending model message reference");
      for (int index = 0; index < pending.calls().size(); index++)
        if (!assistant.getToolCalls().get(index).id().equals(pending.calls().get(index).id())
            || !assistant
                .getToolCalls()
                .get(index)
                .name()
                .equals(pending.calls().get(index).name()))
          throw new IllegalStateException("Invalid pending model message reference");
      original = assistant;
    }
    if (pending.completedReference() != null) {
      var boundary = java.util.Objects.requireNonNull(exchange(prompt), "Missing model message persistence boundary");
      var saved = boundary.store().load(boundary.scope(), pending.completedReference(),
          dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN, boundary.run());
      if (!(saved instanceof ToolResponseMessage tool) || !tool.getResponses().equals(pending.completed()))
        throw new IllegalStateException("Invalid completed model message reference");
      savedPrefix = tool;
    }
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options))
      throw new IllegalArgumentException("Pending tools require explicit tool options");
    var toolCallbacks = options.getToolCallbacks();
    if (toolCallbacks == null)
      throw new IllegalArgumentException("Pending tools require explicit callbacks");
    var callbacks = new java.util.HashMap<String, org.springframework.ai.tool.ToolCallback>();
    toolCallbacks.forEach(callback -> callbacks.put(callback.getToolDefinition().name(), callback));
    // 再開時も、今回許可されたツールだけを使い、同じ呼び出しを重複して実行しない。
    var ids = new java.util.HashSet<String>();
    for (var call : pending.calls())
      if (!callbacks.containsKey(call.name()) || !addToolCallId(ids, call.id()))
        throw new IllegalArgumentException("Unrequested saved Tool or invalid tool-call ID");
    var completed = new java.util.ArrayList<>(pending.completed());
    var argumentCorrections = new java.util.HashSet<>(pending.argumentCorrections());
    var completedIds =
        completed.stream()
            .map(org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse::id)
            .collect(java.util.stream.Collectors.toSet());
    if (!completedIds.containsAll(argumentCorrections))
      throw new IllegalArgumentException("Argument correction must belong to a completed Tool");
    if (completed.size() > pending.calls().size())
      throw new IllegalArgumentException("Invalid completed Tool prefix");
    for (int i = 0; i < completed.size(); i++)
      if (!completed.get(i).id().equals(pending.calls().get(i).id())
          || !completed.get(i).name().equals(pending.calls().get(i).name()))
        throw new IllegalArgumentException("Saved Tool prefix mismatch");
    var calls = new java.util.ArrayList<>(pending.calls().subList(0, completed.size()));
    calls.addAll(
        FileReadContinuation.inherit(
            prompt, pending.calls().subList(completed.size(), pending.calls().size())));
    pending =
        new RunContinuation.PendingTools(
            pending.text(),
            calls,
            pending.completed(),
            pending.argumentCorrections(),
            pending.assistantReference(), pending.completedReference());
    // 承認待ちに入る前に成功した呼び出しは再実行せず、未完了の続きだけを処理する。
    for (int i = completed.size(); i < pending.calls().size(); i++) {
      var call = pending.calls().get(i);
      context.checkActive();
      if (replay) context.checkApprovalContinuationExecution();
      try {
        context.validateToolArguments(
            call.name(),
            call.arguments(),
            callbacks.get(call.name()).getToolDefinition().inputSchema());
      } catch (dev.mikoto2000.rei.core.policy.InvalidToolArgumentsException invalid) {
        // 引数を直せば実行できる場合は、修正の案内を返す。実行済みの兄弟呼び出しはやり直させない。
        completed.add(
            new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                call.id(),
                call.name(),
                context.argumentCorrection(call.id(), call.name(), invalid)));
        argumentCorrections.add(call.id());
        continue;
      } catch (dev.mikoto2000.rei.chatgpt.ChatGptException denied) {
        // 接続先の方針による拒否は引数修正で解除できない。承認や実行へ進まなかった理由を通知する。
        context.reportToolPermissionFailure(call.id(), call.name(), denied);
        throw denied;
      }
      try {
        var lease = RunCancellation.enterChild(prompt);
        try (lease) {
          var values = new java.util.HashMap<String, Object>();
          if (options.getToolContext() != null) values.putAll(options.getToolContext());
          values.put("toolCallId", call.id());
          String result =
              callbacks
                  .get(call.name())
                  .call(
                      call.arguments(), new org.springframework.ai.chat.model.ToolContext(values));
          completed.add(
              new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                  call.id(), call.name(), result));
        }
      } catch (RuntimeException error) {
        if (!Thread.currentThread().isInterrupted()
            && !RunExecutionContext.isControlFailure(error)) {
          var rejected = ToolResultRejectedException.find(error);
          if (rejected != null) {
            String correction =
                rejected instanceof dev.mikoto2000.rei.toolcontract.ToolReadRejectedException read
                    ? context.toolReadCorrection(call.id(), call.name(), read)
                    : context.toolOperationCorrection(call.id(), call.name(), rejected);
            completed.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), correction));
            argumentCorrections.add(call.id());
            continue;
          }
        }
        for (Throwable cause : RunCancellation.causes(error)) {
          if (cause instanceof dev.mikoto2000.rei.core.policy.ToolPermissionException permission
              && permission.approvalId() != null) {
            // 必要な承認が得られるまで、成功済みの結果と未実行の操作を保存して中断する。
            if (context.suspendForApproval(
                permission.approvalId(),
                prompt.getInstructions(),
                new RunContinuation.PendingTools(
                    pending.text(),
                    pending.calls(),
                    java.util.List.copyOf(completed),
                    argumentCorrections,
                    pending.assistantReference(),
                    preserveApprovalPrefix(prompt, pending, completed, argumentCorrections, savedPrefix))))
              throw new RunWaitingException();
          }
        }
        throw dev.mikoto2000.rei.core.stagnation.RunExecutionContext.toolCallbackFailure(error);
      }
    }
    var history = new java.util.ArrayList<>(prompt.getInstructions());
    history.add(
        original != null
            ? original
            : org.springframework.ai.chat.messages.AssistantMessage.builder()
                .content(pending.text())
                .toolCalls(pending.calls())
                .build());
    history.add(
        org.springframework.ai.chat.messages.ToolResponseMessage.builder()
            .responses(completed)
            .build());
    boolean direct =
        pending.calls().stream()
            .allMatch(call -> callbacks.get(call.name()).getToolMetadata().returnDirect());
    return deliveryMetadata(
        prompt,
        ToolExecutionResult.builder()
            .conversationHistory(history)
            .returnDirect(direct && argumentCorrections.isEmpty())
            .build(),
        argumentCorrections, savedPrefix);
  }
}
