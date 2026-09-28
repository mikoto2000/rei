package dev.mikoto2000.rei.paper;

import dev.mikoto2000.rei.subagent.*;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class PaperSummaryValidator {
  private final SubAgentResultParser parser = new SubAgentResultParser();
  private final SubAgentResultSchema schema =
      SubAgentResultSchema.load(
          Path.of("."), "classpath:/subagents/schemas/paper-summary.schema.json");

  public record Validated(PaperSummary.Content content, java.util.List<PaperEvidence> evidence) {}

  public String schema() {
    return schema.json();
  }

  public Validated validate(String raw, StructuredPaper source) {
    try {
      if (raw == null || raw.length() > 16000)
        throw new IllegalArgumentException("summary output limit");
      var json = parser.parse(raw);
      if (!schema.validate(json).isEmpty()) throw new IllegalArgumentException();
      var result = PaperJson.MAPPER.treeToValue(json, Validated.class);
      for (var e : result.evidence())
        if (source.sections().stream()
            .noneMatch(
                s ->
                    s.heading().equals(e.section())
                        && e.page() >= s.startPage()
                        && e.page() <= s.endPage()
                        && s.text().contains(e.text())))
          throw new IllegalArgumentException("ungrounded");
      return result;
    } catch (RuntimeException e) {
      throw new PaperException(
          PaperException.Code.SUMMARY_FAILED, "要約の Schema / Evidence 検証に失敗しました", e);
    }
  }
}
