package dev.mikoto2000.rei.slack;

import java.util.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tokens are deliberately absent: transport reads environment secrets only. */
@Getter @Setter
@ConfigurationProperties("rei.slack")
public class SlackProperties {
  private boolean enabled;
  private Set<String> allowedWorkspaceIds=Set.of();
  private Set<String> allowedUserIds=Set.of();
  private Set<String> allowedChannelIds=Set.of();
  private Map<String,String> channelProjects=Map.of();
  private String dmProjectId;
}
