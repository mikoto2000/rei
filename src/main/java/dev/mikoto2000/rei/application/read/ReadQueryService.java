package dev.mikoto2000.rei.application.read;

import dev.mikoto2000.rei.feed.*;
import dev.mikoto2000.rei.skills.*;
import dev.mikoto2000.rei.search.*;
import dev.mikoto2000.rei.briefing.*;
import dev.mikoto2000.rei.event.ProfileEventLogStore;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import java.util.List;

/** Read adapter over the same services and repository ports used by the shell. All data here is global. */
@Service
public record ReadQueryService(FeedService feeds, AgentSkillRepository skills, ProfileEventLogStore profiles,
    SearchKnowledgeService searches, BriefingService briefings) {
  public List<Feed> feedsList() { return feeds.list(); }
  public Feed feed(long id) {
    return feeds.list().stream().filter(f -> f.id() == id).findFirst().orElseThrow(() -> new ResourceNotFoundException("Feed"));
  }
  public List<AgentSkill> skillsList() { return new SkillQueryService(skills).list(); }
  public AgentSkill skill(String name) {
    return new SkillQueryService(skills).find(name).orElseThrow(() -> new ResourceNotFoundException("Skill"));
  }
  public ProfileEventLogStore.ProfileSummary profile() { return new ProfileQueryService(profiles).summary(); }
  public DailyBriefing briefing() throws Exception { return briefings.today(); }
  public SearchKnowledgeResult search(String query, Integer vectorTopK, Integer webTopK, Double threshold) throws Exception {
    if (query == null || query.isBlank() || query.length() > 2000
        || vectorTopK != null && (vectorTopK < 1 || vectorTopK > 100)
        || webTopK != null && (webTopK < 1 || webTopK > 100)
        || threshold != null && (!Double.isFinite(threshold) || threshold < 0 || threshold > 1))
      throw new IllegalArgumentException("Invalid search");
    return searches.search(query, vectorTopK == null ? 3 : vectorTopK, webTopK == null ? 5 : webTopK, threshold, null);
  }
}
