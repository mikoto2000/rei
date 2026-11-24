package dev.mikoto2000.rei.modelmessage;

/** Provider-neutral numeric usage. Missing values remain unknown; no payload or provider text. */
public record ModelTokenUsage(Long input, Long output, Long total, Long cached, Long reasoning)
    implements java.io.Serializable {
  @java.io.Serial private static final long serialVersionUID = 1L;
  public static final String METADATA_KEY = "rei.llm.token-usage";

  /** Backend numeric metadata survives stream aggregation and takes precedence over its totals. */
  public static ModelTokenUsage from(org.springframework.ai.chat.model.ChatResponse response) {
    var numeric = backend(response);
    if (numeric != null) return numeric;
    var usage = response == null || response.getMetadata() == null
        ? null : response.getMetadata().getUsage();
    if (usage == null || usage instanceof org.springframework.ai.chat.metadata.EmptyUsage)
      return new ModelTokenUsage(null, null, null, null, null);
    return new ModelTokenUsage(number(usage.getPromptTokens()), number(usage.getCompletionTokens()),
        number(usage.getTotalTokens()), null, null);
  }

  private static ModelTokenUsage backend(org.springframework.ai.chat.model.ChatResponse response) {
    if (response != null && response.getResult() != null) {
      var metadata = response.getResult().getOutput().getMetadata();
      if (metadata.containsKey(METADATA_KEY)) {
        if (metadata.get(METADATA_KEY) instanceof ModelTokenUsage numeric) return numeric;
        throw new IllegalArgumentException("Invalid model token usage");
      }
    }
    return null;
  }

  /** Preserve the existing integer budget contract without rounding or overflow. */
  public static Integer totalTokens(org.springframework.ai.chat.model.ChatResponse response) {
    var numeric = backend(response);
    if (numeric == null) {
      var usage = response == null || response.getMetadata() == null
          ? null : response.getMetadata().getUsage();
      return usage == null ? null : usage.getTotalTokens();
    }
    Long total = numeric.total();
    if (total == null) return null;
    if (total > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Model token usage exceeds accounting range");
    return total.intValue();
  }

  private static Long number(Integer value) {
    return value == null ? null : value.longValue();
  }

  public ModelTokenUsage {
    if (negative(input)
        || negative(output)
        || negative(total)
        || negative(cached)
        || negative(reasoning)
        || (cached != null && input != null && cached > input)
        || (reasoning != null && output != null && reasoning > output)
        || (input != null
            && output != null
            && total != null
            && (input > Long.MAX_VALUE - output || input + output != total)))
      throw new IllegalArgumentException("Invalid model token usage");
  }

  private static boolean negative(Long value) {
    return value != null && value < 0;
  }
}
