package dev.mikoto2000.rei.bluesky;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "rei.bluesky")
public class BlueskyProperties {

  private boolean enabled = false;
  private String handle = "";
  private String appPassword = "";
  private int maxPostLength = 300;
}
