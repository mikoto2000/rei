package dev.mikoto2000.rei.temporal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class SchedulerTools {
  private static final Pattern SIMPLE_DURATION = Pattern.compile("^(\\d+)(s|m|h|d)$", Pattern.CASE_INSENSITIVE);

  private final AgentScheduler scheduler;

  public SchedulerTools(AgentScheduler scheduler) {
    this.scheduler = scheduler;
  }

  @Tool(name = "scheduleAfter", description = """
      指定時間後に再開するための continuation を登録します。Thread.sleep で長時間待たず、この Tool を使用してください。
      duration は 10s, 10m, 2h, 1d または ISO-8601 Duration で指定します。
      action には再開時に行う内容、conversationId には現在の会話/タスク識別子を指定します。
      """)
  public ScheduledAgentTask scheduleAfter(String duration, String action, String conversationId) {
    return scheduler.scheduleAfter(parseDuration(duration), action, conversationId);
  }

  @Tool(name = "scheduleAt", description = """
      指定日時に再開するための continuation を登録します。Thread.sleep で長時間待たず、この Tool を使用してください。
      timestamp は ISO-8601 形式で指定します。
      action には再開時に行う内容、conversationId には現在の会話/タスク識別子を指定します。
      """)
  public ScheduledAgentTask scheduleAt(String timestamp, String action, String conversationId) {
    return scheduler.scheduleAt(OffsetDateTime.parse(timestamp).toInstant(), action, conversationId);
  }

  @Tool(name = "listScheduledActions", description = "登録済み continuation の一覧を取得します。")
  public List<ScheduledAgentTask> listScheduledActions() {
    return scheduler.list();
  }

  Duration parseDuration(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("duration は空にできません");
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    Matcher matcher = SIMPLE_DURATION.matcher(normalized);
    if (matcher.matches()) {
      long amount = Long.parseLong(matcher.group(1));
      return switch (matcher.group(2)) {
        case "s" -> Duration.ofSeconds(amount);
        case "m" -> Duration.ofMinutes(amount);
        case "h" -> Duration.ofHours(amount);
        case "d" -> Duration.ofDays(amount);
        default -> throw new IllegalArgumentException("unsupported duration: " + value);
      };
    }
    return Duration.parse(value);
  }
}
