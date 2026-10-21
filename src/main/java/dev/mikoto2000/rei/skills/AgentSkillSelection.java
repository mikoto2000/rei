package dev.mikoto2000.rei.skills;

import java.util.List;

/**
 * スキルの利用で受け渡すエージェントスキル選択内容を表す。
 *
 * @param explicitSkills 明示的なスキル一覧
 * @param implicitSkills 暗黙のスキル一覧
 * @param warnings 注意事項
 * @param sanitizedPrompt 利用可能な形に整えたプロンプト
 * @param selectorDurationMs 対象の選択条件継続時間ミリ秒
 * @param candidates 候補一覧
 * @param candidateDurationMs 候補継続時間ミリ秒
 */
public record AgentSkillSelection(
    List<AgentSkill> explicitSkills,
    List<AgentSkill> implicitSkills,
    List<String> warnings,
    String sanitizedPrompt,
    Long selectorDurationMs,
    List<SkillCandidate> candidates,
    Long candidateDurationMs) {

  /** 受け取った情報からAgentSkillSelectionを作成する。 */
  public AgentSkillSelection {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
  }

  /** スキルの利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AgentSkillSelection(
      List<AgentSkill> explicitSkills,
      List<AgentSkill> implicitSkills,
      List<String> warnings,
      String sanitizedPrompt) {
    this(explicitSkills, implicitSkills, warnings, sanitizedPrompt, null, List.of(), null);
  }

  /** スキルの利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AgentSkillSelection(
      List<AgentSkill> explicitSkills,
      List<AgentSkill> implicitSkills,
      List<String> warnings,
      String sanitizedPrompt,
      Long selectorDurationMs) {
    this(
        explicitSkills,
        implicitSkills,
        warnings,
        sanitizedPrompt,
        selectorDurationMs,
        List.of(),
        null);
  }

  /** スキルの利用で用いる選択中スキル一覧を返す。 */
  public List<AgentSkill> selectedSkills() {
    return java.util.stream.Stream.concat(explicitSkills.stream(), implicitSkills.stream())
        .toList();
  }

  /** スキルの利用で用いる候補スキル一覧を返す。 */
  public List<AgentSkill> candidateSkills() {
    return candidates.stream().map(SkillCandidate::skill).toList();
  }
}
