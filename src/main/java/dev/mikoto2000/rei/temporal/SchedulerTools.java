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
      指定時間後の一回限り continuation を PENDING として永続登録します。登録だけでは実行しません。
      ユーザーが /timer show ID で確認し /timer activate ID で有効化する必要があります。
      自動 dispatch は rei.agent-scheduler.enabled と Tool permission が有効な場合だけです。
      duration は 10s, 10m, 2h, 1d または ISO-8601 Duration で指定します。
      action には再開時に行う内容、conversationId には現在の会話/タスク識別子を指定します。
      """)
  public ScheduledAgentTask scheduleAfter(String duration, String action, String conversationId) {
    return scheduler.scheduleAfter(parseDuration(duration), action, conversationId);
  }

  @Tool(name = "scheduleAt", description = """
      指定日時の一回限り continuation を PENDING として永続登録します。登録だけでは実行しません。
      ユーザーが /timer show ID で確認し /timer activate ID で有効化する必要があります。
      timestamp は ISO-8601 形式で指定します。
      action には再開時に行う内容、conversationId には現在の会話/タスク識別子を指定します。
      """)
  public ScheduledAgentTask scheduleAt(String timestamp, String action, String conversationId) {
    return scheduler.scheduleAt(OffsetDateTime.parse(timestamp).toInstant(), action, conversationId);
  }

  @Tool(name = "listScheduledActions", description = "現在の Project/Session の未終了 continuation を取得します。状態は /timer show ID で確認します。")
  public List<ScheduledAgentTask> listScheduledActions() {
    return scheduler.list();
  }

  @Tool(name="scheduleInterval",description="Register a bounded interval continuation as PENDING. Interval 1m..366d, occurrences 2..100. Review /timer show ID and explicitly activate. Missed times coalesce; failure stops repetitions.")
  public ScheduledAgentTask scheduleInterval(String interval,int occurrences,String action,String conversationId) {
    if(!(scheduler instanceof PersistentAgentScheduler persistent))throw new IllegalStateException("Persistent scheduler required");
    return persistent.scheduleInterval(parseDuration(interval),occurrences,action,conversationId);
  }

  @Tool(name="scheduleCron",description="Register a bounded cron continuation as PENDING. Six cron fields, seconds fixed to 0, explicit IANA time zone, occurrences 2..100. Review /timer show ID and activate explicitly. Missed times coalesce; failure stops repetitions.")
  public ScheduledAgentTask scheduleCron(String expression,String zone,int occurrences,String action,String conversationId) {
    if(!(scheduler instanceof PersistentAgentScheduler persistent))throw new IllegalStateException("Persistent scheduler required");
    return persistent.scheduleCron(expression,zone,occurrences,action,conversationId);
  }

  @Tool(name="scheduleOnEvent",description="Register a PENDING one-shot continuation for an exact source in the current Project/Session. eventType is AGENT_RUN_COMPLETED/FAILED/CANCELLED, EXECUTION_COMPLETED/FAILED/CANCELLED, DEPENDENCY_COMPLETED/FAILED/CANCELLED, or GITHUB_PR_UPDATED/PR_MERGED/REVIEW_SUBMITTED/CI_FAILED/WORKFLOW_COMPLETED. For dependency events, sourceRunId is the exact dependency ID; for GitHub use the sourceId from the owned GitHub mapping API. Never use webhook text as action. expiresAfter 1s..366d. Review /timer show ID and activate before the event; only events after activation match. No automatic retry after uncertain execution.")
  public ScheduledAgentTask scheduleOnEvent(String sourceRunId,String eventType,String expiresAfter,String action,String conversationId) {
    if(!(scheduler instanceof PersistentAgentScheduler persistent))throw new IllegalStateException("Persistent scheduler required");
    return persistent.scheduleOnEvent(sourceRunId,dev.mikoto2000.rei.event.AgentEventType.valueOf(eventType),parseDuration(expiresAfter),action,conversationId);
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
