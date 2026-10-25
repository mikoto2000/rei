package dev.mikoto2000.rei.secretary;

import java.util.List;
import java.util.Locale;

/** Conservative, local semantic hints. No model, network, permissions or task mutations. */
final class SecretaryRelatedness {
  private static final List<List<String>> CONCEPTS =
      List.of(
          List.of("旅行", "旅の", "travel", "trip"),
          List.of("調査", "情報収集", "リサーチ", "research", "investigate"),
          List.of("会議", "打合せ", "打ち合わせ", "meeting"),
          List.of("要約", "まとめ", "summary", "summarize"),
          List.of("返信", "返事", "reply", "respond"),
          List.of("試験", "テスト", "test", "testing"),
          List.of("公開", "リリース", "release", "publish"),
          List.of("レビュー", "査読", "review"));

  private SecretaryRelatedness() {}

  static boolean related(String left, List<String> leftTags, String right, List<String> rightTags) {
    var tags = rightTags.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
    if (leftTags.stream().map(t -> t.toLowerCase(Locale.ROOT)).anyMatch(tags::contains))
      return true;
    String a = left.toLowerCase(Locale.ROOT), b = right.toLowerCase(Locale.ROOT);
    return CONCEPTS.stream()
        .anyMatch(
            group ->
                group.stream().anyMatch(term -> matches(a, term))
                    && group.stream().anyMatch(term -> matches(b, term)));
  }

  private static boolean matches(String text, String term) {
    return term.chars().allMatch(c -> c < 128)
        ? java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(term) + "\\b")
            .matcher(text)
            .find()
        : text.contains(term);
  }
}
