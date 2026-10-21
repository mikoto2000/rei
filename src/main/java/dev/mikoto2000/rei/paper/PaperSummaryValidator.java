package dev.mikoto2000.rei.paper;

import dev.mikoto2000.rei.subagent.SubAgentResultParser;
import dev.mikoto2000.rei.subagent.SubAgentResultSchema;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** 論文の調査と整理に必要な論文要約検証の窓口を扱う。 */
@Component
public class PaperSummaryValidator {
  /** 入力の解釈。 */
  private final SubAgentResultParser parser = new SubAgentResultParser();

  /** データ形式。 */
  private final SubAgentResultSchema schema =
      SubAgentResultSchema.load(
          Path.of("."), "classpath:/subagents/schemas/paper-summary.schema.json");

  /**
   * 論文の調査と整理で受け渡す検証済みを表す。
   *
   * @param content 内容
   * @param evidence 根拠
   */
  public record Validated(PaperSummary.Content content, java.util.List<PaperEvidence> evidence) {}

  /** 論文の調査と整理で用いるデータ形式を返す。 */
  public String schema() {
    return schema.json();
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public Validated validate(String raw, StructuredPaper source) {
    try {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
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
