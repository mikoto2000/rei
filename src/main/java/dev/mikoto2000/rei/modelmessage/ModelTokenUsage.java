package dev.mikoto2000.rei.modelmessage;

/** Provider-neutral numeric usage. Missing values remain unknown; no payload or provider text. */
public record ModelTokenUsage(Long input, Long output, Long total, Long cached, Long reasoning)
    implements java.io.Serializable {
  @java.io.Serial private static final long serialVersionUID = 1L;
  public static final String METADATA_KEY = "rei.llm.token-usage";
}
