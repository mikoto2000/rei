package dev.mikoto2000.rei.interest;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;
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
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      matchType = SuppressMatchType.EXACT,
      justification = "Reads the application-bound InterestProperties shared by consumers of these settings.")
  private final InterestProperties properties;

  /** 指定された値や利用先を使って、InterestPropertiesLoggerを初期化する。 */
  public InterestPropertiesLogger(InterestProperties properties) {
    this.properties = properties;
  }

  /** 関心の管理の操作を実行する。 */
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
