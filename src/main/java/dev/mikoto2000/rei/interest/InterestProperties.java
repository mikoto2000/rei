package dev.mikoto2000.rei.interest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "rei.interest")
public class InterestProperties {

  private boolean enabled = false;

  private int lookbackDays = 7;

  private int messageLimit = 200;

  private double minScore = 0.6;

  private int maxTopics = 3;

  private int recentHours = 24;

  private int vectorTopK = 3;

  private int webTopK = 5;
}
