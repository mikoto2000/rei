package dev.mikoto2000.rei.slack;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("rei.slack.agent-collaboration")
public class SlackAgentProperties {
  private boolean enabled;
  private Set<String> allowedWorkspaceIds = Set.of(), allowedChannelIds = Set.of();
  private Map<String, Agent> agents = Map.of();
  private int maxRequestsPerRun = 3,
      maxFollowUpTurns = 2,
      maxDelegationDepth = 1,
      timeoutSeconds = 300;

  public record Agent(
      String workspaceId,
      String botUserId,
      String botId,
      String channelId,
      String mentionUserId,
      String finalPrefix) {}

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

  private static boolean id(String value, String prefixes) {
    return value != null && value.matches("[" + prefixes + "][A-Z0-9]{1,31}");
  }
}
