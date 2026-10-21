package dev.mikoto2000.rei.skills;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;

/** エージェントスキル選択内容のユースケースを実現する。 */
@Service
public class AgentSkillSelectionService {

  /** この機能の動作設定。 */
  private final AgentSkillsProperties properties;

  /** 明示的な対象の選択条件。 */
  private final AgentSkillExplicitSelector explicitSelector;

  /** 暗黙の対象の選択条件。 */
  private final AgentSkillImplicitSelection implicitSelector;

  /** 保存と検索の窓口。 */
  private final AgentSkillRepository repository;

  /** 候補対象の選択条件。 */
  private final SkillCandidateSelector candidateSelector;

  /** nano時刻。 */
  private final LongSupplier nanoTime;

  /** スキルの利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @org.springframework.beans.factory.annotation.Autowired
  public AgentSkillSelectionService(
      AgentSkillsProperties properties,
      AgentSkillExplicitSelector explicitSelector,
      AgentSkillImplicitSelection implicitSelector,
      AgentSkillRepository repository,
      SkillCandidateSelector candidateSelector) {
    this(
        properties,
        explicitSelector,
        implicitSelector,
        repository,
        candidateSelector,
        System::nanoTime);
  }

  /** スキルの利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AgentSkillSelectionService(
      AgentSkillsProperties properties,
      AgentSkillExplicitSelector explicitSelector,
      AgentSkillImplicitSelection implicitSelector) {
    this(properties, explicitSelector, implicitSelector, null, null, System::nanoTime);
  }

  /** スキルの利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  AgentSkillSelectionService(
      AgentSkillsProperties properties,
      AgentSkillExplicitSelector explicitSelector,
      AgentSkillImplicitSelection implicitSelector,
      LongSupplier nanoTime) {
    this(properties, explicitSelector, implicitSelector, null, null, nanoTime);
  }

  /** 指定された値や利用先を使って、AgentSkillSelectionServiceを初期化する。 */
  AgentSkillSelectionService(
      AgentSkillsProperties properties,
      AgentSkillExplicitSelector explicitSelector,
      AgentSkillImplicitSelection implicitSelector,
      AgentSkillRepository repository,
      SkillCandidateSelector candidateSelector,
      LongSupplier nanoTime) {
    this.properties = properties;
    this.explicitSelector = explicitSelector;
    this.implicitSelector = implicitSelector;
    this.repository = repository;
    this.candidateSelector = candidateSelector;
    this.nanoTime = nanoTime;
  }

  /** 指定された条件に応じて、利用する対象を選ぶ。 */
  public AgentSkillSelection select(String prompt) {
    return select(prompt, null);
  }

  /** 指定された条件に応じて、利用する対象を選ぶ。 */
  public AgentSkillSelection select(String prompt, Runnable beforeModelCall) {
    var budget =
        beforeModelCall instanceof dev.mikoto2000.rei.llm.ModelCallBudget accounting
            ? accounting
            : dev.mikoto2000.rei.llm.ModelCallBudgetScope.current();
    try (var scope = dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(budget)) {
      return selectBudgeted(prompt, beforeModelCall);
    }
  }

  /** 条件に合う対象を利用するため、Budgetedを選択する。 */
  private AgentSkillSelection selectBudgeted(String prompt, Runnable beforeModelCall) {
    if (!properties.isEnabled()) {
      return new AgentSkillSelection(
          List.of(), List.of(), List.of(), prompt == null ? "" : prompt, null, List.of(), null);
    }

    AgentSkillExplicitSelector.ExplicitSelection explicit = explicitSelector.select(prompt);
    List<AgentSkill> explicitSkills =
        distinctAndLimit(explicit.skills(), properties.getMaxSelected());
    int remaining = properties.getMaxSelected() - explicitSkills.size();
    List<AgentSkill> implicitSkills = List.of();
    List<SkillCandidate> candidates = List.of();
    Long candidateDurationMs = null;
    Long selectorDurationMs = null;
    if (remaining > 0) {
      Set<String> explicitNames =
          explicitSkills.stream()
              .map(AgentSkill::name)
              .collect(java.util.stream.Collectors.toSet());
      if (repository != null && candidateSelector != null) {
        List<AgentSkill> eligibleSkills =
            repository.findEnabled().stream()
                .filter(skill -> !explicitNames.contains(skill.name()))
                .toList();
        long candidateStartedAtNanos = nanoTime.getAsLong();
        candidates =
            candidateSelector.selectCandidates(explicit.sanitizedPrompt(), eligibleSkills, 5);
        candidateDurationMs =
            Math.max(0L, (nanoTime.getAsLong() - candidateStartedAtNanos) / 1_000_000L);
      }
      long selectorStartedAtNanos = nanoTime.getAsLong();
      List<AgentSkill> providedCandidates =
          repository == null || candidateSelector == null
              ? null
              : candidates.stream().map(SkillCandidate::skill).toList();
      implicitSkills =
          distinctAndLimit(
              beforeModelCall == null
                  ? implicitSelector.select(
                      explicit.sanitizedPrompt(), explicitNames, providedCandidates)
                  : implicitSelector.select(
                      explicit.sanitizedPrompt(),
                      explicitNames,
                      providedCandidates,
                      beforeModelCall),
              remaining);
      selectorDurationMs =
          Math.max(0L, (nanoTime.getAsLong() - selectorStartedAtNanos) / 1_000_000L);
    }

    return new AgentSkillSelection(
        explicitSkills,
        implicitSkills,
        explicit.warnings(),
        explicit.sanitizedPrompt(),
        selectorDurationMs,
        candidates,
        candidateDurationMs);
  }

  /** スキルの利用で用いるdistinctおよび上限を返す。 */
  private List<AgentSkill> distinctAndLimit(List<AgentSkill> skills, int limit) {
    List<AgentSkill> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (AgentSkill skill : skills) {
      if (skill == null || !seen.add(skill.name())) {
        continue;
      }
      result.add(skill);
      if (result.size() >= limit) {
        break;
      }
    }
    return List.copyOf(result);
  }
}
