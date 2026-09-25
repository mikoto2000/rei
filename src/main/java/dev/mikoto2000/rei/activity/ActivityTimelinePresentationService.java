package dev.mikoto2000.rei.activity;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import dev.mikoto2000.rei.activity.behavior.*;

/** Bulk reads followed by a stable timestamp merge. Does not evaluate, notify or re-extract. */
public record ActivityTimelinePresentationService(ActivityTimeline timeline,BehaviorStateStore behavior) {
  public List<ActivityTimelineEntry> entries(String day,boolean verbose) {
    var date=timeline.date(day);var zone=timeline.clock().getZone();
    var entries=new ArrayList<ActivityTimelineEntry>();
    timeline.timelineSegments(date).forEach(s->entries.add(new ActivityTimelineEntry.ActivityEntry(s)));
    if(behavior!=null)behavior.findEventsBetween(date.atStartOfDay(zone).toInstant(),date.plusDays(1).atStartOfDay(zone).toInstant()).stream()
        .filter(e->e.severity()!=BehaviorSeverity.NONE && (verbose || e.outcome()==BehaviorTimelineEvent.Outcome.EMITTED))
        .forEach(e->entries.add(new ActivityTimelineEntry.BehaviorEntry(e)));
    entries.sort(Comparator.comparing(ActivityTimelineEntry::timestamp));
    return List.copyOf(entries);
  }
  public String format(String day,boolean verbose) {
    var entries=entries(day,verbose);
    if(entries.isEmpty())return "この期間の Activity 記録はありません。";
    var zone=timeline.clock().getZone();var time=DateTimeFormatter.ofPattern("HH:mm").withZone(zone);
    var exact=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone);
    var activity=new ActivitySummaryFormatter(zone);var evidence=new ActivityEvidenceDisplayFormatter();
    var out=new StringBuilder("画面の観測に基づく振り返りです。\n表示内容からの推定を含み、実際の操作・集中を断定するものではありません。\n");
    for(var entry:entries) {
      switch(entry) {
        case ActivityTimelineEntry.ActivityEntry a -> {
          out.append('\n').append(activity.formatEntry(a.segment()));
          var descriptions=a.segment().evidence().stream().map(evidence::evidence).distinct().toList();
          out.append("  evidence: ").append(String.join(" / ",descriptions));
          if(descriptions.size()>1)out.append("（区間内で判定根拠が変化）");
          out.append('\n');
          if(verbose)for(var r:a.segment().evidence())out.append("  Observation ").append(exact.format(r.capturedAt())).append('\n').append(evidence.verbose(r));
        }
        case ActivityTimelineEntry.BehaviorEntry b -> {
          var e=b.event();out.append('\n').append(time.format(e.timestamp())).append(" Behavior ").append(e.severity()).append(" — notification: ").append(e.outcome()).append('\n');
          if(verbose) {
            out.append("  trigger: ").append(e.trigger()).append("; reason: ").append(ActivityEvidenceDisplayFormatter.clean(e.reason())).append('\n');
            out.append("  continuous entertainment (observed): ").append(String.format(Locale.ROOT,"%.1f min",e.continuousEntertainmentSeconds()/60.0)).append('\n');
            for(var window:e.windows())out.append(String.format(Locale.ROOT,"  %d min window entertainment ratio: %.1f%%%n",window.durationMinutes(),100*window.entertainmentRatio()));
          }
        }
      }
    }
    return out.toString();
  }
}
