package dev.mikoto2000.rei.bluesky;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "rei.bluesky")
public class BlueskyProperties {

  private boolean enabled = false;
  private String handle = "";
  private String appPassword = "";
  private int maxPostLength = 300;

  private BlueskyReplyProperties reply = new BlueskyReplyProperties();

  @Getter
  @Setter
  public static class BlueskyReplyProperties {
    private boolean enabled = false;
    private boolean dryRun = false;
    private int checkIntervalSeconds = 300;
    private int fetchLimit = 30;
    private boolean excludeReplies = true;
    private boolean excludeReposts = true;
    private int maxPostAgeMinutes = 120;
    private List<ReplyUser> users = new ArrayList<>();
  }

  @Getter
  @Setter
  public static class ReplyUser {
    private String handle;
    private double probability;
    private int maxRepliesPerDay = 0;
  }
}
