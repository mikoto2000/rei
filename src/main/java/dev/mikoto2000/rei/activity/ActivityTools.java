package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public record ActivityTools(ActivityTimeline timeline) {
  @Tool(description="Read the user's observed desktop activity timeline. Use for 今日何してた, 昨日何してた, daily work summary. Date is today, yesterday, or YYYY-MM-DD in the journal timezone. Evidence of visible content does not prove engagement; gaps mean unknown.")
  public List<ActivitySession> activityTimeline(@ToolParam(description="today, yesterday or YYYY-MM-DD") String date) { return timeline.query(date); }
  @Tool(description="Query activity for a specific time range, e.g. yesterday evening. Supply ISO-8601 timestamps with UTC offsets, using runtime date/time context. Maximum 31 days. Returns observed sessions, never productivity judgments.")
  public List<ActivitySession> activityBetween(String startInclusive,String endExclusive) {return timeline.findBetween(OffsetDateTime.parse(startInclusive).toInstant(),OffsetDateTime.parse(endExclusive).toInstant());}
}
