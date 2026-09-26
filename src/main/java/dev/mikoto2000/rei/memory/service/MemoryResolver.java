package dev.mikoto2000.rei.memory.service;

import java.util.*;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.memory.model.*;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;

@Service
public class MemoryResolver {
  private final MemoryResolutionModel model;
  private final MemoryProperties properties;
  public MemoryResolver(MemoryResolutionModel model, MemoryProperties properties) { this.model=model; this.properties=properties; }
  public MemoryResolution resolve(MemoryCandidate c, List<LongTermMemory> memories, String project) {
    if (c.confidence()<properties.sleep().minConfidence() || c.importance()<properties.sleep().minImportance())
      return new MemoryResolution(MemoryAction.IGNORE,List.of());
    var existing=memories.stream().filter(m -> m.status()==MemoryStatus.ACTIVE && m.scope()==c.scope()
        && (c.scope()==MemoryScope.GLOBAL || Objects.equals(project,m.projectId()))).toList();
    var exact=existing.stream().filter(m -> normalize(m.content()).equals(normalize(c.content()))).findFirst();
    if (exact.isPresent()) return new MemoryResolution(MemoryAction.DUPLICATE,List.of(exact.get().id()));
    if (existing.isEmpty()) return new MemoryResolution(MemoryAction.NEW,List.of());
    var result=model.resolve(c,existing);
    var ids=existing.stream().map(LongTermMemory::id).toList();
    int count=result.targetIds().size();
    if (!ids.containsAll(result.targetIds()) || new HashSet<>(result.targetIds()).size()!=count)
      throw new IllegalArgumentException("Resolution references unavailable memory");
    boolean valid=switch(result.action()) {
      case NEW,IGNORE -> count==0;
      case DUPLICATE,UPDATE,SUPERSEDE -> count==1;
      case MERGE -> count>=2;
      case CONFLICT -> count>=1;
    };
    if (!valid) throw new IllegalArgumentException("Resolution target count invalid");
    return result;
  }
  static String normalize(String text) { return text.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT); }
}
