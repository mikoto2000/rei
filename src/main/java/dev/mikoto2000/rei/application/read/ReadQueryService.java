package dev.mikoto2000.rei.application.read;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.briefing.BriefingService;
import dev.mikoto2000.rei.briefing.DailyBriefing;
import dev.mikoto2000.rei.event.ProfileEventLogStore;
import dev.mikoto2000.rei.feed.Feed;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.search.SearchKnowledgeResult;
import dev.mikoto2000.rei.search.SearchKnowledgeService;
import dev.mikoto2000.rei.skills.AgentSkill;
import dev.mikoto2000.rei.skills.AgentSkillRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 情報の照会で受け渡す読み取り検索条件サービスを表す。
 *
 * <p>Read adapter over the same services and repository ports used by the shell. All data here is
 * global.
 *
 * @param feeds フィード一覧
 * @param skills スキル一覧
 * @param profiles 利用者情報一覧
 * @param searches 検索の記録
 * @param briefings 日次案内の記録
 */
@Service
public record ReadQueryService(
    FeedService feeds,
    AgentSkillRepository skills,
    ProfileEventLogStore profiles,
    SearchKnowledgeService searches,
    BriefingService briefings) {
  /** 情報の照会で用いるフィード一覧一覧を返す。 */
  public List<Feed> feedsList() {
    return feeds.list();
  }

  /** 情報の照会で用いるフィードを返す。 */
  public Feed feed(long id) {
    return feeds.list().stream()
        .filter(f -> f.id() == id)
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("Feed"));
  }

  /** 情報の照会で用いるスキル一覧一覧を返す。 */
  public List<AgentSkill> skillsList() {
    return new SkillQueryService(skills).list();
  }

  /** 情報の照会で用いるスキルを返す。 */
  public AgentSkill skill(String name) {
    return new SkillQueryService(skills)
        .find(name)
        .orElseThrow(() -> new ResourceNotFoundException("Skill"));
  }

  /** 情報の照会で用いる利用者情報を返す。 */
  public ProfileEventLogStore.ProfileSummary profile() {
    return new ProfileQueryService(profiles).summary();
  }

  /** 情報の照会で用いる日次案内を返す。 */
  public DailyBriefing briefing() throws Exception {
    return briefings.today();
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public SearchKnowledgeResult search(
      String query, Integer vectorTopK, Integer webTopK, Double threshold) throws Exception {
    if (query == null
        || query.isBlank()
        || query.length() > 2000
        || vectorTopK != null && (vectorTopK < 1 || vectorTopK > 100)
        || webTopK != null && (webTopK < 1 || webTopK > 100)
        || threshold != null && (!Double.isFinite(threshold) || threshold < 0 || threshold > 1))
      throw new IllegalArgumentException("Invalid search");
    return searches.search(
        query, vectorTopK == null ? 3 : vectorTopK, webTopK == null ? 5 : webTopK, threshold, null);
  }
}
