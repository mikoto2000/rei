package dev.mikoto2000.rei.temporal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/** エージェントイベントの情報を共通の形式で生成する。 */
@Component("temporalAgentEventFactory")
public class AgentEventFactory {
  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、AgentEventFactoryを初期化する。 */
  public AgentEventFactory(Clock clock) {
    this.clock = clock;
  }

  /** 指定された内容から新しい対象を作成する。 */
  public AgentEvent create(String type, String subject, Map<String, Object> payload) {
    return new AgentEvent(
        Instant.now(clock), type, subject, payload == null ? Map.of() : Map.copyOf(payload));
  }
}
