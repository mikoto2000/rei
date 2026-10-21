package dev.mikoto2000.rei.skills;

import java.util.List;

/**
 * スキルの利用で受け渡すスキル候補を表す。
 *
 * @param skill スキル
 * @param score 評価値
 * @param matchedFields 一致した項目一覧
 * @param matchedKeywords 一致した検索語句一覧
 */
public record SkillCandidate(
    AgentSkill skill, int score, List<String> matchedFields, List<String> matchedKeywords) {

  /** 受け取った情報からSkillCandidateを作成する。 */
  public SkillCandidate {
    matchedFields = matchedFields == null ? List.of() : List.copyOf(matchedFields);
    matchedKeywords = matchedKeywords == null ? List.of() : List.copyOf(matchedKeywords);
  }
}
