package dev.mikoto2000.rei.workcontext;

import java.time.Instant;
import java.text.Normalizer;
import java.util.*;
import dev.mikoto2000.rei.workcontext.WorkContext.*;

/** Pure merge: omission never deletes; only explicit grounded actions modify existing items. */
public class WorkContextMerger {
  public WorkContext merge(String project,WorkContext old,String run,List<WorkContextCandidate> changes,
      Map<String,Evidence> sources,GitState git,Instant now) {
    if(old!=null&&!old.projectId().equals(project)) throw new IllegalArgumentException("Wrong project");
    if(old!=null&&run!=null&&old.processedRuns().contains(run)) return old;
    var items=new ArrayList<>(old==null?List.<Item>of():old.items());
    var targeted=new HashSet<String>();
    for(var c:changes) {
      WorkContextRepository.checkCancellation();
      var evidence=c.sourceIds().stream().map(id->{
        var e=sources.get(id); if(e==null) throw new IllegalArgumentException("Unknown evidence: "+id); return e;
      }).toList();
      Origin certainty=c.certainty()==null?evidence.stream().map(Evidence::origin).min(Comparator.naturalOrder()).orElse(Origin.INFERENCE):c.certainty();
      if(certainty!=Origin.INFERENCE&&evidence.stream().noneMatch(e->e.origin()==certainty)) throw new IllegalArgumentException("Certainty has no matching evidence");
      boolean user=certainty==Origin.USER;
      boolean verified=certainty==Origin.TOOL||certainty==Origin.USER;
      Status status=c.status()==Status.COMPLETED&&(certainty==Origin.INFERENCE||c.kind()==Kind.VERIFICATION&&!verified)?Status.UNCONFIRMED:c.status();
      int index=-1;
      if(!c.action().equals("ADD")) {
        if(c.targetId()==null||!targeted.add(c.targetId())) throw new IllegalArgumentException("Missing or repeated target");
        for(int i=0;i<items.size();i++) if(items.get(i).id().equals(c.targetId())) index=i;
        if(index<0) throw new IllegalArgumentException("Unknown item: "+c.targetId());
        var previous=items.get(index);
        if(previous.userCorrected()&&!user) {
          if(c.action().equals("CONFLICT")) add(items,c,Status.UNCONFIRMED,evidence,now,false,certainty);
          continue;
        }
        var incoming=evidence.stream().map(Evidence::observedAt).filter(Objects::nonNull).max(Comparator.naturalOrder());
        var established=previous.evidence().stream().map(Evidence::observedAt).filter(Objects::nonNull).max(Comparator.naturalOrder());
        if(incoming.isPresent()&&established.isPresent()&&incoming.get().isBefore(established.get())) continue;
        if(!user&&!c.action().equals("CONFLICT")) {
          // Assistant/inferred changes cannot silently reverse an established decision or correction.
          if(previous.kind()==Kind.DECISION||c.action().equals("CORRECT")||c.action().equals("SUPERSEDE")) {
            items.set(index,copy(previous,previous.text(),previous.reason(),Status.UNCONFIRMED,evidence,now,previous.userCorrected(),previous.supersededBy()));
            add(items,c,Status.UNCONFIRMED,evidence,now,false,certainty); continue;
          }
        }
        if(c.action().equals("CONFLICT")) {
          items.set(index,copy(previous,previous.text(),previous.reason(),Status.UNCONFIRMED,evidence,now,previous.userCorrected(),previous.supersededBy()));
          add(items,c,Status.UNCONFIRMED,evidence,now,false,certainty);
        } else if(c.action().equals("SUPERSEDE")) {
          var replacement=add(items,c,status,evidence,now,true,certainty);
          items.set(index,copy(previous,previous.text(),previous.reason(),Status.SUPERSEDED,evidence,now,true,replacement.id()));
        } else {
          if(c.kind()!=previous.kind()) throw new IllegalArgumentException("Cannot change item kind");
          items.set(index,copy(previous,c.action().equals("STATUS")?previous.text():c.text(),c.reason(),status,evidence,now,
              previous.userCorrected()||user,null,certainty));
        }
      } else {
        for(int i=0;i<items.size();i++) if(items.get(i).kind()==c.kind()&&key(items.get(i).text()).equals(key(c.text()))) { index=i; break; }
        if(index>=0) {
          var previous=items.get(index);
          // Duplicate evidence enriches without reopening or overwriting explicit status/corrections.
          items.set(index,copy(previous,previous.text(),previous.reason(),previous.status(),evidence,now,previous.userCorrected(),previous.supersededBy()));
        } else add(items,c,status,evidence,now,false,certainty);
      }
    }
    var processed=new HashSet<>(old==null?Set.<String>of():old.processedRuns()); if(run!=null) processed.add(run);
    return new WorkContext(project,old==null?1:old.revision()+1,old==null?now:old.createdAt(),now,
        git==null&&old!=null?old.git():git,items,processed);
  }
  private Item add(List<Item> items,WorkContextCandidate c,Status status,List<Evidence> evidence,Instant now,boolean corrected,Origin certainty) {
    var item=new Item("work_"+UUID.randomUUID(),c.kind(),c.text(),c.reason(),status,evidence,now,now,corrected,null,certainty);
    items.add(item); return item;
  }
  private Item copy(Item old,String text,String reason,Status status,List<Evidence> added,Instant now,boolean corrected,String replaced) {
    return copy(old,text,reason,status,added,now,corrected,replaced,old.certainty());
  }
  private Item copy(Item old,String text,String reason,Status status,List<Evidence> added,Instant now,boolean corrected,String replaced,Origin certainty) {
    var evidence=new LinkedHashMap<String,Evidence>(); old.evidence().forEach(e->evidence.put(e.id(),e)); added.forEach(e->evidence.putIfAbsent(e.id(),e));
    return new Item(old.id(),old.kind(),text,reason,status,List.copyOf(evidence.values()),old.createdAt(),now,corrected,replaced,
        corrected?Origin.USER:certainty);
  }
  private String key(String text) { return Normalizer.normalize(text,Normalizer.Form.NFKC).strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT); }
}
