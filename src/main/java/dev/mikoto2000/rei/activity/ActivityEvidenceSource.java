package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.List;

/** Optional app context ports must be cheap and must never invoke an LLM. */
@FunctionalInterface
public interface ActivityEvidenceSource {
  Contribution collect(Instant at) throws Exception;
  record Contribution(String projectName,String projectId,List<ActivityEvidence.RecentEvent> events) {
    public Contribution {events=List.copyOf(events);}
  }
}
