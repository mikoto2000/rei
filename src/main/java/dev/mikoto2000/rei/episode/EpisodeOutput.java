package dev.mikoto2000.rei.episode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 過去の経験の活用に必要な経験の記録出力を扱う。 */
public final class EpisodeOutput {
  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public List<Episode> parse(
      String output, String session, String project, List<Episode> existing) {
    try {
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      var root = json.readTree(output);
      var array = root.get("episodes");
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (array == null || !array.isArray() || array.size() > 50)
        throw new IllegalArgumentException("Invalid episodes output");
      var result = new ArrayList<Episode>();
      for (var row : array) {
        String target = row.path("id").asText();
        if (!target.isEmpty() && existing.stream().noneMatch(e -> e.id().equals(target)))
          throw new IllegalArgumentException("Unknown episode continuation");
        String id = target.isEmpty() ? "ep_" + UUID.randomUUID() : target;
        var claims =
            json.readerForListOf(Episode.Claim.class)
                .<List<Episode.Claim>>readValue(row.get("claims"));
        result.add(
            new Episode(
                id,
                project,
                session,
                "rev_" + UUID.randomUUID(),
                row.path("occurredAt").asText(),
                row.path("endedAt").isTextual() ? row.path("endedAt").asText() : null,
                Episode.Status.valueOf(row.path("status").asText()),
                row.path("title").asText(),
                row.path("summary").asText(),
                claims,
                row.path("confidence").asDouble()));
      }
      return List.copyOf(result);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid episode output", e);
    }
  }
}
