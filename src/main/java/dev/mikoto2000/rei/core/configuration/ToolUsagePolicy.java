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
      Read file contents with readMultiFile, including a single file.
      Choose files[].purpose autonomously from the user's objective, without asking about batching:
      FULL for exhaustive reviews and cross-file comparisons (batch whole paths, omit artificial
      line chunks); OVERVIEW for orientation; TARGETED for a local question or fix (use ranges or
      selectors). AUTO defaults to bulk for whole files and paging for ranges. The server fits bulk
      source to the remaining context budget and returns nextRead when it cannot all fit. Group files
      with the same purpose. Never treat a partial page or overview as an exhaustive review.
      Do not use runCommand with
      Get-Content, gc, cat, type or other shell file readers. Shell is for builds, tests, Git and
      development commands without a dedicated tool. A truncated result is a page, not a failed read:
      pass the returned nextRead unchanged (including expectedVersion and offset) in the next files array.
      Do not switch to shell to bypass paging or output limits. New delivered file ranges count as
      information acquisition, not completed work; repeated pages do not establish progress.
      """;

  /** 指示一覧。 */
  public static final String HTTP_APIS =
      """
      For HTTP APIs prefer a service-specific tool, then httpRequestRead (GET/HEAD) or
      httpRequestWrite (POST/PUT/PATCH/DELETE). Use fetchUrlContent for public HTML and
      webSearchAndRead for research. Use runCommand only when dedicated tools cannot perform
      an authorized operation. Never bypass HTTP permission, origin, authentication or size
      refusals using shell, Invoke-RestMethod, curl, scripts or another tool. A write timeout
      may have committed: inspect resource state before retrying; never blindly repeat a write.
      """;

  public static final String INSTRUCTIONS =
      "Common tool usage policy:\n" + BATCHING + FILE_READS + HTTP_APIS;

  /** 既存の記録へ経緯を残すため、終点を追記する。 */
  public static String appendTo(String prompt) {
    String base = prompt == null ? "" : prompt;
    if (base.contains(INSTRUCTIONS)) return base;
    return base.isBlank() ? INSTRUCTIONS : base.stripTrailing() + "\n\n" + INSTRUCTIONS;
  }
}
