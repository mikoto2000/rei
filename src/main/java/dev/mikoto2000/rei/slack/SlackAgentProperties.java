package dev.mikoto2000.rei.slack;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Slackエージェントの設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Getter
@Setter
@ConfigurationProperties("rei.slack.agent-collaboration")
public class SlackAgentProperties {
  /** 有効状態。 */
  private boolean enabled;

  /** 許可作業領域識別子一覧／許可チャネル識別子一覧。 */
  private Set<String> allowedWorkspaceIds = Set.of(), allowedChannelIds = Set.of();

  /** エージェント一覧。 */
  private Map<String, Agent> agents = Map.of();

  /** 最大要求一覧ごとの実行／最大後続の入力やり取り一覧／最大委譲深さ／タイムアウトの秒数。 */
  private int maxRequestsPerRun = 3,
      maxFollowUpTurns = 2,
      maxDelegationDepth = 1,
      timeoutSeconds = 300;

  /**
   * Slackとの連携で受け渡すエージェントを表す。
   *
   * @param workspaceId 作業領域識別子
   * @param botUserId 応答担当利用者識別子
   * @param botId 応答担当識別子
   * @param channelId チャネル識別子
   * @param mentionUserId 宛先の指定利用者識別子
   * @param finalPrefix 最終接頭辞
   */
  public record Agent(
      String workspaceId,
      String botUserId,
      String botId,
      String channelId,
      String mentionUserId,
      String finalPrefix) {}

  /** Slackとの連携で用いるエージェントを返す。 */
  public Agent agent(String name) {
    var a = agents == null ? null : agents.get(name);
    if (!enabled
        || a == null
        || allowedWorkspaceIds == null
        || allowedChannelIds == null
        || !allowedWorkspaceIds.contains(a.workspaceId())
        || !allowedChannelIds.contains(a.channelId())
        || !id(a.workspaceId(), "T")
        || !id(a.channelId(), "CG")
        || !id(a.botUserId(), "UW")
        || !id(a.botId(), "B")
        || !Objects.equals(a.botUserId(), a.mentionUserId())
        || a.finalPrefix() != null && a.finalPrefix().length() > 128)
      throw new IllegalArgumentException("External agent is disabled or not explicitly configured");
    if (maxRequestsPerRun < 1
        || maxRequestsPerRun > 16
        || maxFollowUpTurns < 0
        || maxFollowUpTurns > 2
        || maxDelegationDepth != 1
        || timeoutSeconds < 1
        || timeoutSeconds > 3600)
      throw new IllegalArgumentException("Invalid external delegation limits");
    return a;
  }

  /** Slackとの連携において、識別子に該当するか判定する。 */
  private static boolean id(String value, String prefixes) {
    return value != null && value.matches("[" + prefixes + "][A-Z0-9]{1,31}");
  }
}
