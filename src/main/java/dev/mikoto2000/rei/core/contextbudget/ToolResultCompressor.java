package dev.mikoto2000.rei.core.contextbudget;

import java.util.*;
import org.springframework.ai.chat.messages.*;

/** Deterministic representation: selected diagnostic lines plus head/tail; the raw body is always saved first. */
public class ToolResultCompressor {
  private final RawToolResultStore store;
  private final TokenEstimator estimator;
  private final int threshold, tokens;
  private ToolResultDeliveryProperties delivery = new ToolResultDeliveryProperties();
  private record PagedResult(String ref, int offset) { }
  public void setDeliveryProperties(ToolResultDeliveryProperties properties) { properties.validate(); delivery = properties; }
  public ToolResultCompressor(RawToolResultStore store, TokenEstimator estimator, int threshold, int tokens) {
    this.store = store; this.estimator = estimator; this.threshold = threshold; this.tokens = tokens;
  }
  public void preserve(Message message, String conversation, String run) {
    if (message instanceof ToolResponseMessage tool) for (var response : tool.getResponses())
      if (policy(tool, response.id()) != ResultDelivery.Kind.RAW_RESULT_PAGE) store.save(conversation, run, response.name(), response.id(), Objects.toString(response.responseData(), ""));
  }
  public Message compact(Message message, String conversation, String run) {
    if (!(message instanceof ToolResponseMessage tool)) return message;
    var pages = new HashMap<String,PagedResult>();
    var responses = new ArrayList<ToolResponseMessage.ToolResponse>();
    boolean changed = false;
    for (var response : tool.getResponses()) {
      String raw = Objects.toString(response.responseData(), "");
      ResultDelivery.Kind policy = policy(tool, response.id());
      if (policy == ResultDelivery.Kind.RAW_RESULT_PAGE) { responses.add(response); continue; }
      String ref = store.save(conversation, run, response.name(), response.id(), raw);
      if (policy == ResultDelivery.Kind.FILE_CONTENT) {
        if (estimator.text(raw) <= delivery.getPageTargetTokens()) { responses.add(response); continue; }
        changed = true;
        responses.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(),
            new LosslessResultPager(estimator).page(ref, response.id(), response.name(), raw, 0, delivery.getPageTargetTokens())));
        pages.put(response.id(), new PagedResult(ref, 0));
        continue;
      }
      if (estimator.text(raw) <= threshold) { responses.add(response); continue; }
      changed = true;
      String header = "[Compact tool result; omitted output is available via readRawToolResult]\n"
          + "toolName: " + response.name() + "\ntoolCallId: " + response.id() + "\nrawResultRef: " + ref + "\n";
      String diagnostics = important(raw);
      String compact = header + "importantOutput:\n" + bounded(diagnostics, tokens / 2)
          + "\nhead:\n" + bounded(raw, tokens / 4) + "\ntail:\n"
          + bounded(raw.substring(Math.max(0, raw.length() - tokens)), tokens / 4);
      responses.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(), compact));
      org.slf4j.LoggerFactory.getLogger(getClass()).debug("Tool result compacted: before={}, after={}",
          estimator.text(raw), estimator.text(compact));
    }
    var metadata = new HashMap<>(tool.getMetadata());
    if (!pages.isEmpty()) metadata.put("rei.losslessPages", Map.copyOf(pages));
    return changed ? ToolResponseMessage.builder().responses(responses).metadata(metadata).build() : message;
  }
  /** Shrink only contiguous source pages after history projection, never excerpts of an existing page. */
  public org.springframework.ai.chat.prompt.Prompt fit(org.springframework.ai.chat.prompt.Prompt prompt,
      String conversation, String run, int hard) {
    var messages = new ArrayList<>(prompt.getInstructions());
    long total = ContextBudgetManager.estimateMessages(messages, estimator);
    for (int index = 0; index < messages.size() && total > hard; index++) {
      if (!(messages.get(index) instanceof ToolResponseMessage tool)) continue;
      var responses = new ArrayList<>(tool.getResponses());
      var pages = new HashMap<String,PagedResult>();
      if (tool.getMetadata().get("rei.losslessPages") instanceof Map<?,?> existing)
        existing.forEach((key,value) -> { if (key instanceof String id && value instanceof PagedResult page) pages.put(id,page); });
      for (int r = 0; r < responses.size() && total > hard; r++) {
        var response = responses.get(r); var policy = policy(tool, response.id());
        if (policy != ResultDelivery.Kind.FILE_CONTENT && policy != ResultDelivery.Kind.RAW_RESULT_PAGE) continue;
        long remainder = estimator.text(response.responseData()) - (total - hard) - delivery.getPageSafetyTokens();
        if (remainder < delivery.getPageMinTokens()) continue;
        int budget = (int)Math.min(delivery.getPageMaxTokens(), remainder);
        String replacement;
        if (policy == ResultDelivery.Kind.FILE_CONTENT) {
          var page = pages.get(response.id());
          String source;
          if (page == null) {
            source = response.responseData();
            page = new PagedResult(store.save(conversation,run,response.name(),response.id(),source),0);
          } else source = store.read(conversation,page.ref()).rawResult();
          replacement = new LosslessResultPager(estimator).page(page.ref(),response.id(),response.name(),source,page.offset(),budget);
          pages.put(response.id(),page);
        } else {
          // These headers originate in the registered RawToolResultTools, not arbitrary result JSON.
          String[] header = response.responseData().split("\\n",8);
          if (header.length != 8 || !header[2].startsWith("rawResultRef: ") || !header[3].startsWith("offset: "))
            throw new IllegalStateException("CONTEXT_BUDGET_INSUFFICIENT");
          String ref = header[2].substring(14); int offset = Integer.parseInt(header[3].substring(8));
          replacement = new RawToolResultTools(store,estimator).pageText(ref,store.read(conversation,ref),offset,16000,budget);
        }
        responses.set(r,new ToolResponseMessage.ToolResponse(response.id(),response.name(),replacement));
        var metadata = new HashMap<>(tool.getMetadata()); if (!pages.isEmpty()) metadata.put("rei.losslessPages",Map.copyOf(pages));
        messages.set(index,ToolResponseMessage.builder().responses(responses).metadata(metadata).build());
        total = ContextBudgetManager.estimateMessages(messages,estimator);
      }
    }
    return new org.springframework.ai.chat.prompt.Prompt(messages,prompt.getOptions());
  }
  static ResultDelivery.Kind policy(ToolResponseMessage message, String call) {
    Object policies = message.getMetadata().get("rei.deliveryPolicies");
    if (policies instanceof Map<?, ?> registered && registered.get(call) instanceof ResultDelivery.Kind kind) return kind;
    return ResultDelivery.Kind.GENERAL;
  }
  private String important(String raw) {
    // JSON command results frequently put escaped stdout/stderr in a single physical line.
    try {
      var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
      if (node != null && node.isObject()) {
        var text = new StringBuilder();
        for (String key : List.of("command", "status", "exitCode", "exit_code", "error", "diagnosis", "stderr", "stdout", "output")) {
          var value = node.get(key);
          if (value != null) text.append(key).append(": ").append(value.isContainerNode() ? value.toString() : value.asText()).append('\n');
        }
        if (!text.isEmpty()) raw = text.toString();
      }
    } catch (Exception ignored) { }
    return raw.lines().filter(line -> line.toLowerCase(Locale.ROOT)
        .matches(".*(exit.?code|status|command|fail|error|expected|exception|tests run|build |process).*"))
        .limit(80).collect(java.util.stream.Collectors.joining("\n"));
  }
  private String bounded(String text, int budget) {
    int end = text.length();
    while (end > 0 && estimator.text(text.substring(0, end)) > budget) end = end * 3 / 4;
    return text.substring(0, end);
  }
}
