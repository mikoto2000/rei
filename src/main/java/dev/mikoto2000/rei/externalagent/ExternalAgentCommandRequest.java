package dev.mikoto2000.rei.externalagent;

/**
 * 外部エージェントへの委譲で受け渡す外部エージェントコマンド要求を表す。
 *
 * <p>Explicit provider/action grammar. Repository text and tool arguments never grant authority.
 *
 * @param agent エージェント
 * @param action 操作
 * @param target 対象
 */
public record ExternalAgentCommandRequest(String agent, String action, String target) {
  /** 使用状況。 */
  public static final String USAGE =
      "Usage: /agent <codex|claude> review [target] | /agent codex material-review [target] | /agent <codex|claude> implement <target> | /agent <codex|claude> implementation <id> | /agent <codex|claude> merge <id> <patchHash>";

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public static ExternalAgentCommandRequest parse(String text) {
    String[] parts = text.strip().split("\\s+", 4);
    if (parts.length < 3 || !parts[0].equals("/agent")) throw new IllegalArgumentException(USAGE);
    if (!known(parts[1], ExternalAgentRequest.Agent.values()))
      throw new IllegalArgumentException("Unsupported external agent: " + parts[1]);
    boolean materialReview = parts[2].equals("material-review") && parts[1].equals("codex");
    if (!parts[2].equals("review") && !materialReview) {
      if (parts.length != 4 || parts[3].isBlank()) throw new IllegalArgumentException(USAGE);
      switch (parts[2]) {
        case "implement" -> {
          // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
          if (parts[3].length() > 1024) throw new IllegalArgumentException(USAGE);
        }
        case "implementation" -> {
          if (!parts[3].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException(USAGE);
        }
        case "merge" -> {
          if (!parts[3].matches(
              "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12} [0-9a-f]{64}"))
            throw new IllegalArgumentException(USAGE);
        }
        default -> throw new IllegalArgumentException("Unsupported external action: " + parts[2]);
      }
    }
    return new ExternalAgentCommandRequest(parts[1], parts[2], parts.length == 4 ? parts[3] : null);
  }

  /** 外部エージェントへの委譲において、既知に該当するか判定する。 */
  private static boolean known(String name, Enum<?>[] values) {
    return java.util.Arrays.stream(values)
        .anyMatch(value -> value.name().toLowerCase(java.util.Locale.ROOT).equals(name));
  }
}
