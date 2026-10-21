package dev.mikoto2000.rei.core.configuration;

/**
 * ツール使用状況の判断基準をまとめる。
 *
 * <p>Tool planning instructions shared without inheriting personality, history or authority.
 */
public final class ToolUsagePolicy {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ToolUsagePolicy() {}

  /** 結果をまとめて扱うための識別値。 */
  public static final String BATCHING =
      """
      For multiple independent, already-known targets of the same Multi tool, pass all targets in one array
      in a single call. Do not emit multiple one-item calls to the same tool in the same response.
      Split only when a later target depends on an earlier result or documented limits require it
      (file count, bytes, lines, tokens, or staged writes). A one-item array remains valid for a single target.
      """;

  /** ファイルREADS。 */
  public static final String FILE_READS =
      """
      Read file contents with readMultiFile, including a single file. Do not use runCommand with
      Get-Content, gc, cat, type or other shell file readers. Shell is for builds, tests, Git and
      development commands without a dedicated tool. A truncated result is a page, not a failed read:
      pass the returned nextRead unchanged (including expectedVersion and offset) in the next files array.
      Do not switch to shell to bypass paging or output limits. New delivered file ranges count as
      information acquisition, not completed work; repeated pages do not establish progress.
      """;

  /** 指示一覧。 */
  public static final String INSTRUCTIONS = "Common tool usage policy:\n" + BATCHING + FILE_READS;

  /** 既存の記録へ経緯を残すため、終点を追記する。 */
  public static String appendTo(String prompt) {
    String base = prompt == null ? "" : prompt;
    if (base.contains(INSTRUCTIONS)) return base;
    return base.isBlank() ? INSTRUCTIONS : base.stripTrailing() + "\n\n" + INSTRUCTIONS;
  }
}
