package dev.mikoto2000.rei.activity;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 作業状況の把握で受け渡す作業状況ツール一覧を表す。
 *
 * @param timeline 時系列の記録
 */
public record ActivityTools(ActivityTimeline timeline) {
  /** 作業状況の把握で用いる作業状況作業推定一覧を返す。 */
  @Tool(
      description =
          "Read saved supplemental temporal work inferences with original observation IDs and REI execution references. Date today/yesterday/YYYY-MM-DD; optional projectId filters exact observation-time project identity. Inference is not user operation, task completion, observed duration, or productivity. Does not run a model.")
  public List<WorkActivityInference> activityWorkInferences(String date, String projectId) {
    return timeline.workInferences(date, projectId);
  }

  /** 作業状況の把握で用いる作業状況要約を返す。 */
  @Tool(
      description =
          "Summarize observed desktop activity for 今日何してた, 昨日何してた, or a daily review. Returns compact semantic blocks with a single uncertainty disclaimer. Date is today, yesterday, or YYYY-MM-DD. Does not prove engagement or productivity.")
  public String activitySummary(
      @ToolParam(description = "today, yesterday or YYYY-MM-DD") String date) {
    return timeline.summary(date);
  }

  /** 作業状況の把握で用いる作業状況時系列の記録を返す。 */
  @Tool(
      description =
          "Read detailed fine-grained desktop observation sessions. Prefer activitySummary for a compact daily review. Date is today, yesterday, or YYYY-MM-DD in the journal timezone. Visible content does not prove engagement; gaps mean unknown.")
  public List<ActivitySession> activityTimeline(
      @ToolParam(description = "today, yesterday or YYYY-MM-DD") String date) {
    return timeline.query(date);
  }

  /** 作業状況の把握で用いる作業状況期間を返す。 */
  @Tool(
      description =
          "Query activity for a specific time range, e.g. yesterday evening. Supply ISO-8601 timestamps with UTC offsets, using runtime date/time context. Maximum 31 days. Returns observed sessions, never productivity judgments.")
  public List<ActivitySession> activityBetween(String startInclusive, String endExclusive) {
    return timeline.findBetween(
        OffsetDateTime.parse(startInclusive).toInstant(),
        OffsetDateTime.parse(endExclusive).toInstant());
  }
}
