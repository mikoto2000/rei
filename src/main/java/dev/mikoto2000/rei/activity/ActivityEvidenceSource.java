package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.List;

/** Optional app context ports must be bounded and must never invoke an LLM. */
@FunctionalInterface
public interface ActivityEvidenceSource {
  Contribution collect(Instant at) throws Exception;
  record Contribution(String projectName,String projectId,List<ActivityEvidence.RecentEvent> events,ActivityEvidence.WorkReference workContext) {
    public Contribution {events=List.copyOf(events);}
    public Contribution(String name,String id,List<ActivityEvidence.RecentEvent> events){this(name,id,events,null);}
  }
}
