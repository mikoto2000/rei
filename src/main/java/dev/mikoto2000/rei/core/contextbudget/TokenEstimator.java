package dev.mikoto2000.rei.core.contextbudget;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Replaceable estimate, not an exact tokenizer. Non-ASCII text is deliberately more conservative.
 */
public interface TokenEstimator {
  int text(String text);

  default int message(Message message) {
    long total = 8L + text(message.getText());
    if (message instanceof AssistantMessage assistant)
      for (var call : assistant.getToolCalls())
        total += 12L + text(call.id()) + text(call.name()) + text(call.arguments());
    if (message instanceof ToolResponseMessage tool)
      for (var result : tool.getResponses())
        total += 12L + text(result.id()) + text(result.name()) + text(result.responseData());
    if (message instanceof UserMessage user) total += 4096L * user.getMedia().size();
    return (int) Math.min(Integer.MAX_VALUE, total);
  }

  /**
   * Reuses the bundled byte-pair tokenizers, taking the larger estimate and accounting for wire
   * escaping.
   */
  static TokenEstimator tokenizerAware() {
    TokenEstimator heuristic = conservative();
    return value -> {
      if (value == null || value.isEmpty()) return 0;
      // A giant unbroken word can make BPE merging expensive. One token per encoded byte
      // is a conservative upper bound for the bundled byte-pair tokenizers.
      if (value.length() > 32768)
        return (int)
            Math.min(
                Integer.MAX_VALUE,
                value
                    .codePoints()
                    .mapToLong(
                        c ->
                            c == '"' || c == '\\' || c == '\b' || c == '\t' || c == '\n'
                                    || c == '\f' || c == '\r'
                                ? 2
                                : c < 32 || c >= 0xD800 && c <= 0xDFFF
                                    ? 6
                                    : c < 128 ? 1 : c < 2048 ? 2 : c < 65536 ? 3 : 4)
                    .sum());
      try {
        String encoded = TokenizerHolder.JSON.writeValueAsString(value);
        String wire = encoded.substring(1, encoded.length() - 1);
        if (wire.length() > 32768)
          return wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        long units =
            wire.codePoints()
                .mapToLong(c -> c < 128 ? 1 : c < 2048 ? 8 : c < 65536 ? 12 : 16)
                .sum();
        int estimate = (int) Math.min(Integer.MAX_VALUE, (units + 3) / 4);
        estimate = Math.max(estimate, heuristic.text(wire));
        estimate = Math.max(estimate, TokenizerHolder.CL.countTokensOrdinary(value));
        estimate = Math.max(estimate, TokenizerHolder.O.countTokensOrdinary(value));
        estimate = Math.max(estimate, TokenizerHolder.CL.countTokensOrdinary(wire));
        return Math.max(estimate, TokenizerHolder.O.countTokensOrdinary(wire));
      } catch (java.io.IOException invalid) {
        throw new IllegalArgumentException("Cannot estimate serialized text", invalid);
      }
    };
  }

  final class TokenizerHolder {
    private TokenizerHolder() {}

    static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();
    static final com.knuddels.jtokkit.api.EncodingRegistry REGISTRY =
        com.knuddels.jtokkit.Encodings.newDefaultEncodingRegistry();
    static final com.knuddels.jtokkit.api.Encoding CL =
        REGISTRY.getEncoding(com.knuddels.jtokkit.api.EncodingType.CL100K_BASE);
    static final com.knuddels.jtokkit.api.Encoding O =
        REGISTRY.getEncoding(com.knuddels.jtokkit.api.EncodingType.O200K_BASE);
  }

  static TokenEstimator conservative() {
    return value -> {
      if (value == null || value.isEmpty()) return 0;
      long units = value.codePoints().mapToLong(c -> c < 128 ? 1 : 8).sum();
      return (int) Math.min(Integer.MAX_VALUE, (units + 3) / 4);
    };
  }
}
