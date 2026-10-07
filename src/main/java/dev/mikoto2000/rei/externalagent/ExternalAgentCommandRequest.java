package dev.mikoto2000.rei.externalagent;

/** Explicit provider/action grammar. Repository text and tool arguments never grant authority. */
public record ExternalAgentCommandRequest(String agent, String action, String target) {
  public static final String USAGE = "Usage: /agent <codex|claude> review [target] | /agent codex implement <target> | /agent codex implementation <id> | /agent codex merge <id> <patchHash>";
  public static ExternalAgentCommandRequest parse(String text) {
    String[] parts = text.strip().split("\\s+", 4);
    if (parts.length < 3 || !parts[0].equals("/agent")) throw new IllegalArgumentException(USAGE);
    if (!known(parts[1], ExternalAgentRequest.Agent.values())) throw new IllegalArgumentException("Unsupported external agent: " + parts[1]);
    if(!parts[2].equals("review")) {
      if(!parts[1].equals("codex") || parts.length!=4 || parts[3].isBlank())throw new IllegalArgumentException(USAGE);
      switch(parts[2]) {
        case "implement" -> {if(parts[3].length()>1024)throw new IllegalArgumentException(USAGE);}
        case "implementation" -> {if(!parts[3].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException(USAGE);}
        case "merge" -> {if(!parts[3].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12} [0-9a-f]{64}"))throw new IllegalArgumentException(USAGE);}
        default -> throw new IllegalArgumentException("Unsupported external action: "+parts[2]);
      }
    }
    return new ExternalAgentCommandRequest(parts[1], parts[2], parts.length == 4 ? parts[3] : null);
  }
  private static boolean known(String name, Enum<?>[] values) {
    return java.util.Arrays.stream(values).anyMatch(value -> value.name().toLowerCase(java.util.Locale.ROOT).equals(name));
  }
}
