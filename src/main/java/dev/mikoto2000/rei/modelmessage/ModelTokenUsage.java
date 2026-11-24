package dev.mikoto2000.rei.modelmessage;

/** Provider-neutral numeric usage. Missing values remain unknown; no payload or provider text. */
public record ModelTokenUsage(Long input, Long output, Long total, Long cached, Long reasoning)
    implements java.io.Serializable {
  @java.io.Serial private static final long serialVersionUID = 1L;
  public static final String METADATA_KEY = "rei.llm.token-usage";

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
