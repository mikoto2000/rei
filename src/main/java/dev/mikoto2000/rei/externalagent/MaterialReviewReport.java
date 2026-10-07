package dev.mikoto2000.rei.externalagent;

import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.subagent.SubAgentResultParser;
import dev.mikoto2000.rei.subagent.SubAgentResultSchema;

/** Strict shared schema validation followed by deterministic human-readable rendering. */
final class MaterialReviewReport {
  static final int MAX_REPORT_CHARS = 120000;
  record Parsed(String summary, List<ExternalAgentFinding> findings, List<String> warnings, String report) {}
  private static final class Schema {
    static final SubAgentResultSchema INSTANCE = SubAgentResultSchema.load(Path.of("."),
        "classpath:/subagents/schemas/material-review.schema.json");
  }
  static Parsed parse(String json) throws java.io.IOException {
    var strict = new SubAgentResultParser().parse(json);
    if (!Schema.INSTANCE.validate(strict).isEmpty()) throw new IllegalArgumentException("Invalid material review schema");
    JsonNode data = new ObjectMapper().readTree(strict.toString());
    var findings = new ArrayList<JsonNode>();
    data.path("findings").forEach(findings::add);
    data.path("crossPageFindings").forEach(cross -> findings.add(cross.path("finding")));
    String report = render(data, findings);
    if (report.length() > MAX_REPORT_CHARS) throw new IllegalArgumentException("Material report exceeds limit");
    var normalized = findings.stream().map(f -> new ExternalAgentFinding(
        ExternalAgentFinding.Severity.valueOf(f.path("severity").asText().toLowerCase(Locale.ROOT)),
        safe(f.path("issue").asText()), safe(f.path("whyItMatters").asText()),
        safe(f.path("suggestedImprovement").asText()), safe(location(f)))).toList();
    var warnings = new ArrayList<String>();
    data.path("warnings").forEach(w -> warnings.add(safe(w.asText())));
    return new Parsed(safe(data.path("summary").asText()), normalized, List.copyOf(warnings), safe(report));
  }
  private static String render(JsonNode data, List<JsonNode> findings) {
    var out = new StringBuilder("# 勉強会資料レビュー\n");
    section(out, "1. Executive Summary"); out.append(data.path("summary").asText()).append('\n');
    section(out, "2. 対象範囲");
    out.append("Target: ").append(data.path("scope").path("target").asText()).append('\n');
    out.append("\n### レビュー済みファイル\n"); list(out, data.path("scope").path("reviewedFiles"));
    out.append("\n### 除外対象\n"); list(out, data.path("scope").path("excludedFiles"));
    out.append("\n### 推定・前提\n"); list(out, data.path("scope").path("assumptions"));
    if (!data.path("warnings").isEmpty()) { out.append("\n### 制約・警告\n"); list(out, data.path("warnings")); }
    section(out, "3. 総合評価");
    String[][] scores = {{"technicalAccuracy","技術的正確性"},{"instructionalDesign","教育設計"},
        {"explanationQuality","文章・説明品質"},{"crossPageConsistency","サイト全体の一貫性"},{"practicalApplicability","実務への適合性"}};
    for (var score : scores) {
      JsonNode value = data.path("scores").path(score[0]);
      out.append("\n### ").append(score[1]).append("\n\n").append(value.path("score").asText())
          .append(" / 5\n\n").append(value.path("reason").asText()).append('\n');
    }
    String[][] severities = {{"CRITICAL","4. Critical Issues"},{"HIGH","5. High Priority Issues"},
        {"MEDIUM","6. Medium Priority Issues"},{"LOW","7. Low Priority Issues"}};
    for (var severity : severities) {
      section(out, severity[1]);
      appendFindings(out, findings.stream().filter(f -> f.path("severity").asText().equals(severity[0])).toList());
    }
    section(out, "8. ファイル別レビュー");
    var files = new LinkedHashSet<String>(); findings.forEach(f -> files.add(f.path("file").asText()));
    if (files.isEmpty()) out.append("該当なし\n");
    for (String file : files) {
      out.append("\n### ").append(file).append('\n');
      appendFindings(out, findings.stream().filter(f -> f.path("file").asText().equals(file)).toList());
    }
    section(out, "9. ページ横断の問題");
    for (var aspect : new String[][]{{"TERMS","用語・表記"},{"DUPLICATION","重複"},{"CONTRADICTION","矛盾"},
        {"PREREQUISITES","前提知識"},{"ORDER","ページ順序"}}) {
      out.append("\n### ").append(aspect[1]).append('\n');
      var selected = new ArrayList<JsonNode>();
      data.path("crossPageFindings").forEach(c -> { if (c.path("aspect").asText().equals(aspect[0])) selected.add(c.path("finding")); });
      appendFindings(out, selected);
    }
    for (var item : new String[][]{{"missingExplanations","10. 不足している説明"},
        {"recommendedAdditions","11. 図・コード例・デモを追加するとよい箇所"},
        {"appendixCandidates","12. 削減または Appendix 化を推奨する内容"},
        {"recommendedFixOrder","13. 推奨する修正順序"}}) { section(out, item[1]); list(out, data.path(item[0])); }
    section(out, "14. Build / Link / Markdown 検証結果");
    if (data.path("validationResults").isEmpty()) out.append("検証結果なし（未検証）\n");
    data.path("validationResults").forEach(v -> out.append("- ").append(v.path("command").asText()).append(": ")
        .append(v.path("status").asText()).append(" — ").append(v.path("details").asText()).append('\n'));
    section(out, "15. 良い点"); list(out, data.path("positiveFindings"));
    return out.toString();
  }
  private static void appendFindings(StringBuilder out, List<JsonNode> findings) {
    if (findings.isEmpty()) out.append("該当なし\n");
    for (JsonNode finding : findings) {
      out.append("\nSeverity: ").append(finding.path("severity").asText()).append("\n\nFile: ")
          .append(finding.path("file").asText()).append("\n\nSection / Heading: ")
          .append(finding.path("heading").isNull() ? "不明" : finding.path("heading").asText())
          .append("\n\nLine: ").append(finding.path("line").isNull() ? "不明" : finding.path("line").asText())
          .append("\n\nCategory: ").append(finding.path("category").asText())
          .append("\n\nIssue: ").append(finding.path("issue").asText())
          .append("\n\nWhy it matters: ").append(finding.path("whyItMatters").asText())
          .append("\n\nSuggested improvement: ").append(finding.path("suggestedImprovement").asText()).append('\n');
    }
  }
  private static String location(JsonNode finding) {
    return finding.path("file").asText() + (finding.path("line").isNull() ? "" : ":" + finding.path("line").asText());
  }
  private static void section(StringBuilder out, String heading) { out.append("\n## ").append(heading).append("\n\n"); }
  private static void list(StringBuilder out, JsonNode values) {
    if (values.isEmpty()) out.append("該当なし\n");
    values.forEach(value -> out.append("- ").append(value.asText()).append('\n'));
  }
  private static String safe(String text) { return dev.mikoto2000.rei.event.CredentialRedactor.redact(text); }
}
