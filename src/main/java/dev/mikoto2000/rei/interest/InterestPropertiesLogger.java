package dev.mikoto2000.rei.interest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 関心の管理に必要な関心設定ログの出力先を扱う。 */
@Component
public class InterestPropertiesLogger implements ApplicationRunner {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(InterestPropertiesLogger.class);

  /** この機能の動作設定。 */
  private final java.util.function.Supplier<String> configurationSummary;

  /** 指定された値や利用先を使って、InterestPropertiesLoggerを初期化する。 */
  public InterestPropertiesLogger(InterestProperties properties) {
    this.configurationSummary = () -> summarize(properties);
  }

  /** 関心の管理の操作を実行する。 */
  @Override
  public void run(ApplicationArguments args) {
    log.info(configurationSummary.get());
  }

  private static String summarize(InterestProperties properties) {
    return "Interest config: enabled=%s, lookbackDays=%s, messageLimit=%s, maxTopics=%s, minScore=%s, recentHours=%s, vectorTopK=%s, webTopK=%s, topicUpdateIntervalHours=%s, pastQueryLookbackDays=%s"
        .formatted(
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
