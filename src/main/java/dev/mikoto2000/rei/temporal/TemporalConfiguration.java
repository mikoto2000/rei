package dev.mikoto2000.rei.temporal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 時系列の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
public class TemporalConfiguration {

  /** 時刻と期間の解釈で用いる判定や記録に使う時刻の取得元を返す。 */
  @Bean
  Clock clock() {
    return Clock.systemDefaultZone();
  }

  /** 時刻と期間の解釈で用いるmonotonic時刻取得元を返す。 */
  @Bean
  MonotonicTimeSource monotonicTimeSource() {
    return MonotonicTimeSource.system();
  }
}
