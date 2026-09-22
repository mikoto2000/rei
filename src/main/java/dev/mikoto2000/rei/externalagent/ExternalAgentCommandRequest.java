package dev.mikoto2000.rei.externalagent;

/** UI grammar, deliberately restricted to Phase 1. The remaining text is one target. */
public record ExternalAgentCommandRequest(String agent, String action, String target) {
  public static final String USAGE = "Usage: /agent codex review [target]";
  public static ExternalAgentCommandRequest parse(String text) {
    String[] parts = text.strip().split("\\s+", 4);
    if (parts.length < 3 || !parts[0].equals("/agent")) throw new IllegalArgumentException(USAGE);
    if (!known(parts[1], ExternalAgentRequest.Agent.values())) throw new IllegalArgumentException("Unsupported external agent: " + parts[1]);
    if (!known(parts[2], ExternalAgentRequest.Action.values())) throw new IllegalArgumentException("Unsupported external action: " + parts[2]);
    return new ExternalAgentCommandRequest(parts[1], parts[2], parts.length == 4 ? parts[3] : null);
  }
  private static boolean known(String name, Enum<?>[] values) {
    return java.util.Arrays.stream(values).anyMatch(value -> value.name().toLowerCase(java.util.Locale.ROOT).equals(name));
  }
}
