package dev.mikoto2000.rei.github;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Git連携先Webhook通知の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@org.springframework.boot.context.properties.ConfigurationProperties("rei.github.webhook")
@lombok.Getter
@lombok.Setter
public class GitHubWebhookProperties {
  /** 有効状態。 */
  private boolean enabled;

  /** 秘密情報。 */
  private String secret;

  /** 最大通知内容バイト数／最大配信一覧。 */
  private int maxPayloadBytes = 262144, maxDeliveries = 10000;

  /** 最大事実一覧。 */
  private int maxFacts = 50000;

  /** 再確認必須。 */
  private boolean recheckRequired;

  /** 最大経過時間／完了待ちSkew。 */
  private Duration maxAge = Duration.ofDays(7), futureSkew = Duration.ofMinutes(5);

  /** イベント一覧。 */
  private Set<String> events = Set.of("pull_request", "pull_request_review", "workflow_run");

  /** 対応付け一覧。 */
  private List<Mapping> mappings = List.of();

  /**
   * GitHubとの連携で受け渡す対応付けを表す。
   *
   * @param repository 保存と検索の窓口
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param branch ブランチ
   * @param pullRequest 取得要求
   * @param notifyReview 通知レビュー
   */
  public record Mapping(
      String repository,
      String projectId,
      String sessionId,
      String branch,
      Integer pullRequest,
      boolean notifyReview) {}

  /** 利用条件に合わない指定を受け付けないよう、通信経路を検証する。 */
  public void validateTransport() {
    if (secret == null
        || secret.length() < 32
        || secret.length() > 4096
        || secret.chars().anyMatch(Character::isISOControl)
        || maxPayloadBytes < 1024
        || maxPayloadBytes > 1024 * 1024
        || maxDeliveries < 1
        || maxDeliveries > 100000
        || maxFacts < 1
        || maxFacts > 100000
        || maxAge == null
        || maxAge.compareTo(Duration.ofMinutes(1)) < 0
        || maxAge.compareTo(Duration.ofDays(30)) > 0
        || futureSkew == null
        || futureSkew.isNegative()
        || futureSkew.compareTo(Duration.ofMinutes(5)) > 0
        || events == null
        || events.isEmpty()
        || !Set.of("pull_request", "pull_request_review", "workflow_run").containsAll(events))
      throw new IllegalArgumentException("Invalid GitHub webhook configuration");
  }

  /** 利用条件に合わない指定を受け付けないよう、対応付け一覧を検証する。 */
  public void validateMappings() {
    validateTransport();
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (mappings == null || mappings.isEmpty() || mappings.size() > 64)
      throw new IllegalArgumentException("Explicit GitHub repository mappings required");
    var identities = new HashSet<String>();
    for (var mapping : mappings) {
      if (mapping == null
          || mapping.repository() == null
          || !mapping.repository().matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
          || mapping.projectId() == null
          || mapping.projectId().isBlank()
          || mapping.projectId().length() > 128
          || mapping.sessionId() == null
          || mapping.sessionId().isBlank()
          || mapping.sessionId().length() > 256
          || mapping.branch() != null
              && (mapping.branch().isBlank()
                  || mapping.branch().length() > 256
                  || mapping.branch().chars().anyMatch(Character::isISOControl))
          || mapping.pullRequest() != null && mapping.pullRequest() < 1
          || !identities.add(
              mapping.repository().toLowerCase(Locale.ROOT)
                  + "\0"
                  + mapping.projectId()
                  + "\0"
                  + mapping.sessionId()
                  + "\0"
                  + mapping.branch()
                  + "\0"
                  + mapping.pullRequest()))
        throw new IllegalArgumentException("Invalid or duplicate GitHub mapping");
    }
  }
}
