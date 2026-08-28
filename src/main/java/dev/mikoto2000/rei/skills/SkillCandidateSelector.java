package dev.mikoto2000.rei.skills;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class SkillCandidateSelector {

  static final int NAME_SCORE = 10;
  static final int KEYWORD_EXACT_SCORE = 5;
  static final int KEYWORD_PARTIAL_SCORE = 2;
  static final int DESCRIPTION_SCORE = 1;

  public List<SkillCandidate> selectCandidates(String userRequest, List<AgentSkill> skills, int limit) {
    if (limit <= 0 || skills == null || skills.isEmpty()) return List.of();
    String request = normalize(userRequest);
    if (request.isEmpty()) return List.of();

    return skills.stream()
        .map(skill -> score(request, skill))
        .filter(candidate -> candidate.score() > 0)
        .sorted(Comparator.comparingInt(SkillCandidate::score).reversed()
            .thenComparing(candidate -> normalize(candidate.skill().name())))
        .limit(limit)
        .toList();
  }

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

  private boolean lexicallyOverlaps(String left, String right) {
    if (left.contains(right) || right.contains(left)) return true;
    for (String term : terms(right)) {
      if (term.length() >= 2 && left.contains(term)) return true;
    }
    return false;
  }

  private List<String> terms(String value) {
    return java.util.Arrays.stream(value.split("[^\\p{L}\\p{N}]+"))
        .filter(term -> !term.isBlank())
        .toList();
  }

  static String normalize(String value) {
    if (value == null) return "";
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .strip()
        .replaceAll("\\s+", " ");
  }
}
