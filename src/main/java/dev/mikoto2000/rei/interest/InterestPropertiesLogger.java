package dev.mikoto2000.rei.interest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class InterestPropertiesLogger implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(InterestPropertiesLogger.class);

  private final InterestProperties properties;

  public InterestPropertiesLogger(InterestProperties properties) {
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    log.info(
        "Interest config: enabled={}, lookbackDays={}, messageLimit={}, maxTopics={}, minScore={}, recentHours={}, vectorTopK={}, webTopK={}, topicUpdateIntervalHours={}, pastQueryLookbackDays={}",
        properties.isEnabled(),
        properties.getLookbackDays(),
        properties.getMessageLimit(),
        properties.getMaxTopics(),
        properties.getMinScore(),
        properties.getRecentHours(),
        properties.getVectorTopK(),
        properties.getWebTopK(),
        properties.getTopicUpdateIntervalHours(),
        properties.getPastQueryLookbackDays());
  }
}
