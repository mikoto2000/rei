package dev.mikoto2000.rei.core.contextbudget;

import dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.Page;
import dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.PolicyValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * モデルへ渡す文脈の管理に必要なツールの結果Compressorを扱う。
 *
 * <p>Exact source/delegated pages; diagnostic excerpts for general logs. Raw bodies are saved
 * first.
 */
public class ToolResultCompressor {
  /** 保存先。 */
  private final RawToolResultStore store;

  /** 概算。 */
  private final TokenEstimator estimator;

  /** 判定の境界値／トークン数。 */
  private final int threshold, tokens;

  /** 配信。 */
  private ToolResultDeliveryProperties delivery = new ToolResultDeliveryProperties();

  /** モデルへ渡す文脈の管理で使用する配信設定を設定する。 */
  public void setDeliveryProperties(ToolResultDeliveryProperties properties) {
    properties.validate();
    delivery = properties;
  }

  /** 指定された値や利用先を使って、ToolResultCompressorを初期化する。 */
  public ToolResultCompressor(
      RawToolResultStore store, TokenEstimator estimator, int threshold, int tokens) {
    this.store = store;
    this.estimator = estimator;
    this.threshold = threshold;
    this.tokens = tokens;
  }

  /** モデルへ渡す文脈の管理に関するpreserveを処理する。 */
  public void preserve(Message message, String conversation, String run) {
    if (message instanceof ToolResponseMessage tool)
      for (var response : tool.getResponses())
        if (policy(tool, response.id()) != ResultDelivery.Kind.RAW_RESULT_PAGE)
          store.save(
              conversation,
              run,
              response.name(),
              response.id(),
              Objects.toString(response.responseData(), ""));
  }

  /** モデルへ渡す文脈の管理で用いる簡潔な形式を返す。 */
  public Message compact(Message message, String conversation, String run) {
    if (!(message instanceof ToolResponseMessage tool)) return message;
    var pages = new HashMap<String, Page>();
    var responses = new ArrayList<ToolResponseMessage.ToolResponse>();
    boolean changed = false;
    for (var response : tool.getResponses()) {
      String raw = Objects.toString(response.responseData(), "");
      ResultDelivery.Kind policy = policy(tool, response.id());
      if (policy == ResultDelivery.Kind.RAW_RESULT_PAGE) {
        responses.add(response);
        continue;
      }
      String ref = store.save(conversation, run, response.name(), response.id(), raw);
      if (lossless(policy)) {
        if (tool.getMetadata().get("rei.reviewReceipts") instanceof Map<?, ?> receipts
            && receipts.containsKey(response.id())) {
          if (estimator.text(raw) > delivery.getPageMaxTokens())
            throw new IllegalStateException(
                "CONTEXT_BUDGET_INSUFFICIENT: reduce readReviewFile tokenBudget");
          responses.add(response);
          continue;
        }
        if (estimator.text(raw) <= fileReadLimit(tool, response)) {
          responses.add(response);
          continue;
        }
        changed = true;
        responses.add(
            new ToolResponseMessage.ToolResponse(
                response.id(),
                response.name(),
                new LosslessResultPager(estimator)
                    .page(
                        ref,
                        response.id(),
                        response.name(),
                        raw,
                        0,
                        delivery.getPageTargetTokens())));
        pages.put(response.id(), new Page(ref, 0));
        continue;
      }
      if (estimator.text(raw) <= threshold) {
        responses.add(response);
        continue;
      }
      changed = true;
      String header =
          "[Compact tool result; omitted output is available via readRawToolResult]\n"
              + "toolName: "
              + response.name()
              + "\ntoolCallId: "
              + response.id()
              + "\nrawResultRef: "
              + ref
              + "\n";
      String diagnostics = important(raw);
      String compact =
          header
              + "importantOutput:\n"
              + bounded(diagnostics, tokens / 2)
              + "\nhead:\n"
              + bounded(raw, tokens / 4)
              + "\ntail:\n"
              + bounded(raw.substring(Math.max(0, raw.length() - tokens)), tokens / 4);
      responses.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(), compact));
      org.slf4j.LoggerFactory.getLogger(getClass())
          .debug(
              "Tool result compacted: before={}, after={}",
              estimator.text(raw),
              estimator.text(compact));
    }
    var metadata = new HashMap<>(tool.getMetadata());
    if (!pages.isEmpty()) metadata.put("rei.losslessPages", Map.copyOf(pages));
    return changed
        ? ToolResponseMessage.builder().responses(responses).metadata(metadata).build()
        : message;
  }

  private int fileReadLimit(ToolResponseMessage tool, ToolResponseMessage.ToolResponse response) {
    if (response.name().equals("readMultiFile")
        && policy(tool, response.id()) == ResultDelivery.Kind.FILE_CONTENT
        && tool.getMetadata().get("rei.fileReadBudgets") instanceof Map<?, ?> grants
        && grants.get(response.id())
            instanceof dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.FileReadBudgetValue grant)
      return Math.max(
          delivery.getPageMaxTokens(), Math.min(delivery.getBulkMaxTokens(), grant.tokens()));
    return delivery.getPageMaxTokens();
  }

  /**
   * 残っている予算に収めるため、ツールの結果Compressorを利用可能な量に整える。
   *
   * <p>Shrink only contiguous source pages after history projection, never excerpts of an existing
   * page.
   */
  public org.springframework.ai.chat.prompt.Prompt fit(
      org.springframework.ai.chat.prompt.Prompt prompt, String conversation, String run, int hard) {
    var messages = new ArrayList<>(prompt.getInstructions());
    long total = ContextBudgetManager.estimateMessages(messages, estimator);
    for (int index = 0; index < messages.size() && total > hard; index++) {
      if (!(messages.get(index) instanceof ToolResponseMessage tool)) continue;
      var responses = new ArrayList<>(tool.getResponses());
      var pages = new HashMap<String, Page>();
      if (tool.getMetadata().get("rei.losslessPages") instanceof Map<?, ?> existing)
        existing.forEach(
            (key, value) -> {
              if (key instanceof String id && value instanceof Page page) pages.put(id, page);
            });
      for (int r = 0; r < responses.size() && total > hard; r++) {
        var response = responses.get(r);
        var policy = policy(tool, response.id());
        if (!lossless(policy) && policy != ResultDelivery.Kind.RAW_RESULT_PAGE) continue;
        if (tool.getMetadata().get("rei.reviewReceipts") instanceof Map<?, ?> receipts
            && receipts.containsKey(response.id())) continue;
        long remainder =
            estimator.text(response.responseData())
                - (total - hard)
                - delivery.getPageSafetyTokens();
        if (remainder < delivery.getPageMinTokens()) continue;
        int budget = (int) Math.min(delivery.getPageMaxTokens(), remainder);
        String replacement;
        if (lossless(policy)) {
          var page = pages.get(response.id());
          String source;
          if (page == null) {
            source = response.responseData();
            page =
                new Page(store.save(conversation, run, response.name(), response.id(), source), 0);
          } else source = store.read(conversation, page.ref()).rawResult();
          replacement =
              new LosslessResultPager(estimator)
                  .page(page.ref(), response.id(), response.name(), source, page.offset(), budget);
          pages.put(response.id(), page);
        } else {
          // These headers originate in the registered RawToolResultTools, not arbitrary result
          // JSON.
          var page = RawResultPage.parse(response.responseData());
          replacement =
              new RawToolResultTools(store, estimator)
                  .pageText(
                      page.rawResultRef(),
                      store.read(conversation, page.rawResultRef()),
                      page.offset(),
                      Math.min(16000, page.nextOffset() - page.offset()),
                      budget);
        }
        responses.set(
            r, new ToolResponseMessage.ToolResponse(response.id(), response.name(), replacement));
        var metadata = new HashMap<>(tool.getMetadata());
        if (!pages.isEmpty()) metadata.put("rei.losslessPages", Map.copyOf(pages));
        messages.set(
            index, ToolResponseMessage.builder().responses(responses).metadata(metadata).build());
        total = ContextBudgetManager.estimateMessages(messages, estimator);
      }
    }
    return new org.springframework.ai.chat.prompt.Prompt(messages, prompt.getOptions());
  }

  /** モデルへ渡す文脈の管理で用いる判断基準を返す。 */
  public static ResultDelivery.Kind policy(ToolResponseMessage message, String call) {
    Object policies = message.getMetadata().get("rei.deliveryPolicies");
    if (policies instanceof Map<?, ?> registered
        && registered.get(call) instanceof PolicyValue value) {
      String kind = value.deliveryKind();
      if (kind == null) return ResultDelivery.Kind.GENERAL;
      try {
        return ResultDelivery.Kind.valueOf(kind);
      } catch (IllegalArgumentException invalid) {
        return ResultDelivery.Kind.GENERAL;
      }
    }
    return ResultDelivery.Kind.GENERAL;
  }

  /** モデルへ渡す文脈の管理において、losslessに該当するか判定する。 */
  public static boolean lossless(ResultDelivery.Kind kind) {
    return kind == ResultDelivery.Kind.FILE_CONTENT || kind == ResultDelivery.Kind.LOSSLESS_RESULT;
  }

  /** モデルへ渡す文脈の管理で用いるimportantを返す。 */
  private String important(String raw) {
    // JSON command results frequently put escaped stdout/stderr in a single physical line.
    try {
      var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
      if (node != null && node.isObject()) {
        var text = new StringBuilder();
        for (String key :
            List.of(
                "status",
                "exitCode",
                "exit_code",
                "error",
                "diagnosis",
                "stderr",
                "command",
                "stdout",
                "output")) {
          var value = node.get(key);
          if (value != null)
            text.append(key)
                .append(": ")
                .append(value.isContainerNode() ? value.toString() : value.asText())
                .append('\n');
        }
        if (!text.isEmpty()) raw = text.toString();
      }
    } catch (Exception ignored) {
    }
    return raw.lines()
        .filter(
            line ->
                line.toLowerCase(Locale.ROOT)
                    .matches(
                        ".*(exit.?code|status|command|fail|error|stderr|diagnosis|expected|exception|tests run|build |process).*"))
        .limit(80)
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  /** 利用先の上限を超えない量へ、指定された内容を調整する。 */
  private String bounded(String text, int budget) {
    int end = text.length();
    while (end > 0 && estimator.text(text.substring(0, end)) > budget) end = end * 3 / 4;
    return text.substring(0, end);
  }
}
