package dev.mikoto2000.rei.slack;

import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Slackの設定値をまとめ、利用環境に応じた動作を指定できるようにする。
 *
 * <p>Tokens are deliberately absent: transport reads environment secrets only.
 */
@Getter
@Setter
@ConfigurationProperties("rei.slack")
public class SlackProperties {
  /** 有効状態。 */
  private boolean enabled;

  /** 許可作業領域識別子一覧。 */
  private Set<String> allowedWorkspaceIds = Set.of();

  /** 許可利用者識別子一覧。 */
  private Set<String> allowedUserIds = Set.of();

  /** 許可チャネル識別子一覧。 */
  private Set<String> allowedChannelIds = Set.of();

  /** チャネルプロジェクト一覧。 */
  private Map<String, String> channelProjects = Map.of();

  /** dm所属プロジェクトの識別子。 */
  private String dmProjectId;
}
