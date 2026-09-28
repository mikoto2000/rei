package dev.mikoto2000.rei.memory.command;

import java.util.*;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.memory.model.*;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.core.project.ProjectService;

/** Read-only reporting and explicit archive; project ownership always comes from the selected client. */
@Component
public class MemoryCommandSupport {
  private final MemoryRepository repository;
  private final SleepService sleep;
  private final ProjectService projects;
  private final MemoryProperties properties;
  public MemoryCommandSupport(MemoryRepository repository,SleepService sleep,ProjectService projects,MemoryProperties properties) {
    this.repository=repository; this.sleep=sleep; this.projects=projects; this.properties=properties;
  }
  private String project() { return projects.currentContext().id(); }
  public void enabled() { if(!properties.enabled()) throw new IllegalStateException("Memory is disabled (rei.memory.enabled=false)"); }
  public String list(int limit,int offset) { enabled(); return render(repository.list(project(),limit,offset)); }
  public String search(String query,int limit) { enabled(); return render(repository.search(query,project(),limit)); }
  public String show(String id) {
    enabled(); var m=visible(id);
    return "id: "+m.id()+"\nscope: "+m.scope()+"\nprojectId: "+m.projectId()+"\ntype: "+m.type()+"\nstatus: "+m.status()
        +"\ncontent: "+m.content()+"\nsummary: "+m.summary()+"\nconfidence: "+m.confidence()+"\nimportance: "+m.importance()
        +"\ncreatedAt: "+m.createdAt()+"\nupdatedAt: "+m.updatedAt()+"\nlastAccessedAt: "+m.lastAccessedAt()
        +"\nvalidFrom: "+m.validFrom()+"\nvalidUntil: "+m.validUntil()+"\nsupersededBy: "+m.supersededBy()
        +"\nsource sessions/turns: "+m.sources()+"\ntags: "+m.tags()+"\nrelations: "+repository.relations(id);
  }
  public String forget(String id) { enabled(); visible(id); repository.archive(id); return "Archived: "+id; }
  private LongTermMemory visible(String id) {
    return repository.find(id).filter(m -> m.scope()==MemoryScope.GLOBAL || Objects.equals(m.projectId(),project()))
        .orElseThrow(() -> new IllegalArgumentException("Memory not found in GLOBAL/current project"));
  }
  private String render(List<LongTermMemory> memories) {
    if(memories.isEmpty()) return "No memories.";
    return String.join("\n\n",memories.stream().map(m -> m.id()+" ["+m.scope()+"] ["+m.type()+"] "+m.status()+"\n"+m.content()).toList());
  }
  public String status() {
    enabled(); var runs=repository.history(project(),1);
    StringBuilder text=new StringBuilder("Memory Status\n");
    repository.counts(project()).forEach((key,count) -> text.append(key).append(": ").append(count).append('\n'));
    text.append("Last sleep: ").append(runs.isEmpty()?"none":runs.getFirst()).append('\n');
    text.append("unslept turns: ").append(sleep.unsleptTurns(projects.currentSessionId()));
    return text.toString();
  }
  public String history() {
    enabled(); var runs=repository.history(project(),100);
    return "Sleep history\n"+(runs.isEmpty()?"none":String.join("\n",runs.stream().map(Object::toString).toList()));
  }
  public String report(SleepService.Report report) {
    var r=report.run();
    StringBuilder text=new StringBuilder(report.preview()?"Sleep Preview\n":"Sleep completed.\n");
    text.append("Processed turns: ").append(r.processedTurns()).append("\nMemory candidates: ").append(r.candidateCount())
        .append("\nAdded: ").append(r.added()).append("\nUpdated: ").append(r.updated()).append("\nMerged: ").append(r.merged())
        .append("\nSuperseded: ").append(r.superseded()).append("\nIgnored (including duplicates): ").append(r.ignored())
        .append("\nConflicts: ").append(r.conflicts()).append("\nFailed: ").append(r.failed()).append('\n');
    for(var plan:report.plans()) {
      var c=plan.candidate();
      text.append("\n[").append(c.scope()).append("] [").append(c.type()).append("]\nconfidence: ").append(c.confidence())
          .append(" importance: ").append(c.importance()).append('\n').append(c.content()).append("\nAction: ").append(plan.resolution().action()).append('\n');
      for(var target:plan.targets()) text.append("Existing: ").append(target.id()).append(" ").append(target.content()).append('\n');
    }
    return text.toString();
  }
}
