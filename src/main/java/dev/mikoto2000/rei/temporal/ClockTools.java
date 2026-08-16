package dev.mikoto2000.rei.temporal;

import java.time.Clock;
import java.time.ZonedDateTime;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class ClockTools {
  private final Clock clock;

  public ClockTools(Clock clock) {
    this.clock = clock;
  }

  @Tool(name = "getCurrentTime", description = """
      現在日時を取得します。現在日時を推測せず、この Tool の結果を使用してください。
      戻り値は ISO-8601 timestamp と timezone を含みます。
      """)
  CurrentTime getCurrentTime() {
    ZonedDateTime now = ZonedDateTime.now(clock);
    return new CurrentTime(now.toOffsetDateTime().toString(), now.getZone().getId());
  }
}
