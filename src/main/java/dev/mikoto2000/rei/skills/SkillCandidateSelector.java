package dev.mikoto2000.rei.skills;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** スキルの利用に必要なスキル候補対象の選択条件を扱う。 */
@Component
public class SkillCandidateSelector {

  /** 名前評価値。 */
  static final int NAME_SCORE = 10;

  /** 検索語句完全一致評価値。 */
  static final int KEYWORD_EXACT_SCORE = 5;

  /** 検索語句部分的な結果評価値。 */
  static final int KEYWORD_PARTIAL_SCORE = 2;

  /** 説明評価値。 */
  static final int DESCRIPTION_SCORE = 1;

  /** 意味による。 */
  private SemanticSkillSearch semantic;

  /** スキルの利用に関する意味による検索を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void semanticSearch(SemanticSkillSearch semantic) {
    this.semantic = semantic;
  }

  /** 条件に合う対象を利用するため、候補一覧を選択する。 */
  public List<SkillCandidate> selectCandidates(
      String userRequest, List<AgentSkill> skills, int limit) {
    if (limit <= 0 || skills == null || skills.isEmpty()) return List.of();
    String request = normalize(userRequest);
    if (request.isEmpty()) return List.of();

    var eligible =
        skills.stream().filter(java.util.Objects::nonNull).filter(AgentSkill::enabled).toList();
    var lexical =
        eligible.stream()
            .map(skill -> score(request, skill))
            .filter(candidate -> candidate.score() > 0)
            .sorted(
                Comparator.comparingInt(SkillCandidate::score)
                    .reversed()
                    .thenComparing(candidate -> normalize(candidate.skill().name())))
            .limit(
                semantic != null && semantic.enabled() ? Math.min(256, Math.max(10, limit)) : limit)
            .toList();
    return semantic != null && semantic.enabled()
        ? semantic.select(request, eligible, lexical, limit)
        : lexical;
  }

  /** スキルの利用で用いる評価値を返す。 */
  private SkillCandidate score(String request, AgentSkill skill) {
    int score = 0;
    Set<String> fields = new LinkedHashSet<>();
    List<String> matchedKeywords = new ArrayList<>();

    String name = normalize(skill.name());
    if (!name.isEmpty() && request.contains(name)) {
      score += NAME_SCORE;
      fields.add("name");
    }

    for (String rawKeyword : skill.keywords()) {
      String keyword = normalize(rawKeyword);
      if (keyword.isEmpty()) continue;
      if (request.contains(keyword)) {
        score += KEYWORD_EXACT_SCORE;
        fields.add("keywords");
        matchedKeywords.add(rawKeyword);
      } else if (lexicallyOverlaps(request, keyword)) {
        score += KEYWORD_PARTIAL_SCORE;
        fields.add("keywords");
        matchedKeywords.add(rawKeyword);
      }
    }

    String description = normalize(skill.description());
    if (!description.isEmpty() && lexicallyOverlaps(request, description)) {
      score += DESCRIPTION_SCORE;
      fields.add("description");
    }
    return new SkillCandidate(skill, score, List.copyOf(fields), matchedKeywords);
  }

  /** スキルの利用において、lexicallyOverlapsに該当するか判定する。 */
  private boolean lexicallyOverlaps(String left, String right) {
    if (left.contains(right) || right.contains(left)) return true;
    for (String term : terms(right)) {
      if (term.length() >= 2 && left.contains(term)) return true;
    }
    return false;
  }

  /** スキルの利用で用いる検索語句一覧を返す。 */
  private List<String> terms(String value) {
    return java.util.Arrays.stream(value.split("[^\\p{L}\\p{N}]+"))
        .filter(term -> !term.isBlank())
        .toList();
  }

  /** 同じ意味の指定を共通に扱えるよう、表記や形式をそろえる。 */
  static String normalize(String value) {
    if (value == null) return "";
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .strip()
        .replaceAll("\\s+", " ");
  }
}
