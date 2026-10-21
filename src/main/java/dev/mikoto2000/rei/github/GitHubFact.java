package dev.mikoto2000.rei.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * GitHubとの連携で受け渡すGit連携先事実を表す。
 *
 * <p>Only fixed facts from authenticated payloads. No title, body, command or URL is retained.
 *
 * @param type 種別
 * @param event イベント
 * @param action 操作
 * @param repository 保存と検索の窓口
 * @param branch ブランチ
 * @param pullRequest 取得要求
 * @param headSha 先頭SHA
 * @param conclusion 結論
 * @param workflowRunId workflow実行の識別子
 * @param occurredAt occurred日時
 */
public record GitHubFact(
    String type,
    String event,
    String action,
    String repository,
    String branch,
    Integer pullRequest,
    String headSha,
    String conclusion,
    Long workflowRunId,
    Instant occurredAt) {
  /** GitHubとの連携で用いる取得元識別子を返す。 */
  public String sourceId() {
    return sourceId(repository, pullRequest, branch);
  }

  /** GitHubとの連携で用いる取得元識別子を返す。 */
  public static String sourceId(String repository, Integer pullRequest, String branch) {
    String key =
        repository.toLowerCase(Locale.ROOT)
            + "\0"
            + (pullRequest == null ? "branch:" + branch : "pr:" + pullRequest);
    try {
      return "github:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
