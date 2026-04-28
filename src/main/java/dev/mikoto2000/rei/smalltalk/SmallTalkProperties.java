package dev.mikoto2000.rei.smalltalk;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "rei.small-talk")
public class SmallTalkProperties {

  private boolean enabled = true;

  private String cron = "0 0 12 * * *";
}
