package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.workcontext.WorkContext;

/** Read-only identifier join. Co-occurrence with an agent event is not evidence of user engagement. */
public final class ActivityWorkContextService {
  @FunctionalInterface public interface SavedContexts {List<WorkContext> history(String project);}
  public record GitReference(String branch,String commit,Instant capturedAt) {}
  public record Link(String recordId,Instant capturedAt,String eventId,Instant eventAt,String kind,String sessionId,
      String turnId,String runId,long revision,List<String> itemIds,String basis,GitReference git) {
    public Link{itemIds=List.copyOf(itemIds);}
  }
  private final ActivityStore store;
  private final SavedContexts contexts;
  private final Clock clock;
  public ActivityWorkContextService(ActivityStore store,SavedContexts contexts,Clock clock){this.store=store;this.contexts=contexts;this.clock=clock;}
  public List<Link> links(String project,String day) {
    if(project==null || project.isBlank())throw new IllegalArgumentException("Select a project");
    var snapshot=Clock.fixed(clock.instant(),clock.getZone());
    var range=ActivityQueryRange.forDate(new ActivityDateArgumentResolver(snapshot).resolve(day),snapshot);
    if(range.fromInclusive().equals(range.toExclusive()))return List.of();
    var history=contexts.history(project).stream().filter(c->project.equals(c.projectId())).sorted(Comparator.comparingLong(WorkContext::revision)).toList();
    var result=new ArrayList<Link>();var seen=new HashSet<String>();
    for(var record:store.findRecordsBetween(range.fromInclusive(),range.toExclusive())) {
      var detection=record.detection();if(detection==null || detection.evidence()==null)continue;
      for(var event:detection.evidence().events()) {
        if(!project.equals(event.projectId()) || event.eventId()==null || event.sessionId()==null || event.runId()==null
            || event.sessionId().isBlank() || event.runId().isBlank() || !Set.of("SHELL","FILE_EDIT").contains(event.kind())
            || event.at()==null || event.at().isAfter(record.capturedAt()) || event.at().isBefore(record.capturedAt().minusSeconds(120)))continue;
        if(!seen.add(record.id()+"\u0000"+event.eventId()))continue;
        for(var context:history) {
          var items=context.items().stream().filter(item->item.evidence().stream().anyMatch(e->event.eventId().equals(e.id())
              && e.origin()==WorkContext.Origin.TOOL && event.at().equals(e.observedAt())
              && event.sessionId().equals(e.sessionId()) && event.runId().equals(e.runId()))).map(WorkContext.Item::id).distinct().limit(20).toList();
          if(items.isEmpty())continue;
          var git=context.git();var reference=git==null?null:new GitReference(git.branch(),git.commit(),git.capturedAt());
          result.add(new Link(record.id(),record.capturedAt(),event.eventId(),event.at(),event.kind(),event.sessionId(),event.turnId(),event.runId(),context.revision(),items,"EVENT_REFERENCE",reference));
          if(result.size()>=128)return List.copyOf(result);
          break;
        }
      }
    }
    return List.copyOf(result);
  }
  public String format(List<Link> links) {
    var out=new StringBuilder("Activity / Work Context 保存参照（最大128件、直近100 revisionsから照合）\n");
    if(links.isEmpty())out.append("一致する保存参照なし。旧記録や未生成のWork Contextからは推測しません。\n");
    for(var link:links) {
      out.append("observation=").append(clean(link.recordId())).append(" at=").append(link.capturedAt())
          .append(" event=").append(clean(link.eventId())).append(" eventAt=").append(link.eventAt()).append(" kind=").append(link.kind())
          .append(" session=").append(clean(link.sessionId())).append(" turn=").append(clean(link.turnId())).append(" run=").append(clean(link.runId()))
          .append(" workRevision=").append(link.revision()).append(" items=").append(link.itemIds().stream().map(ActivityWorkContextService::clean).toList()).append('\n');
      var git=link.git();if(git!=null)out.append("  Git snapshot at=").append(git.capturedAt()).append(" branch=").append(clean(git.branch())).append(" commit=").append(clean(git.commit())).append('\n');
    }
    return out.append("Agent実行との近接参照です。画面操作・タスクへの従事・成果の証明ではありません。Gitは保存snapshotの時刻の情報で、観測時点のbranchを保証しません。\n").toString();
  }
  private static String clean(String text){return ActivityEvidenceDisplayFormatter.clean(text);}
}
