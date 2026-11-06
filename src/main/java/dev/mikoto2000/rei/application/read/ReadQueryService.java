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
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 情報の照会で受け渡す読み取り検索条件サービスを表す。
 *
 * <p>Read adapter over the same services and repository ports used by the shell. All data here is
 * global.
 */
@Service
public final class ReadQueryService {
  /** フィード一覧を読み取る操作。 */
  private final Supplier<List<Feed>> feeds;

  /** スキルの保存先。 */
  private final AgentSkillRepository skills;

  /** 利用者情報の読み取り窓口。 */
  private final ProfileQueryService profiles;

  /** 検索の窓口。 */
  private final SearchKnowledgeService searches;

  /** 日次案内の窓口。 */
  private final BriefingService briefings;

  /** 照会で使用する読み取り先を受け取る。 */
  public ReadQueryService(
      FeedService feeds,
      AgentSkillRepository skills,
      ProfileEventLogStore profiles,
      SearchKnowledgeService searches,
      BriefingService briefings) {
    this.feeds = () -> feeds.list();
    this.skills = skills;
    this.profiles = new ProfileQueryService(profiles);
    this.searches = searches;
    this.briefings = briefings;
  }

  /** 情報の照会で用いるフィード一覧一覧を返す。 */
  public List<Feed> feedsList() {
    return feeds.get();
  }

  /** 情報の照会で用いるフィードを返す。 */
  public Feed feed(long id) {
    return feeds.get().stream()
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
    return profiles.summary();
  }

  /** 情報の照会で用いる日次案内を返す。 */
  public DailyBriefing briefing() throws Exception {
    return briefings.today();
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public SearchKnowledgeResult search(
      String query, Integer vectorTopK, Integer webTopK, Double threshold)
      throws IOException, InterruptedException {
    if (query == null
        || query.isBlank()
        || query.length() > 2000
        || vectorTopK != null && (vectorTopK < 1 || vectorTopK > 100)
        || webTopK != null && (webTopK < 1 || webTopK > 100)
        || threshold != null && (!Double.isFinite(threshold) || threshold < 0 || threshold > 1))
      throw new IllegalArgumentException("Invalid search");
    return searches.search(
        query,
        vectorTopK == null ? Integer.valueOf(3) : vectorTopK,
        webTopK == null ? Integer.valueOf(5) : webTopK,
        threshold,
        null);
  }
}
