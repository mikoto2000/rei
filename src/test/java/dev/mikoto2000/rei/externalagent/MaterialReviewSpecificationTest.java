package dev.mikoto2000.rei.externalagent;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.subagent.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialReviewSpecificationTest {
  static String fixture() throws Exception {
    try (var stream = MaterialReviewSpecificationTest.class.getResourceAsStream("/external-agent/material-review-valid.json")) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
  @Test void promptDefinesCompleteTeachingContract() throws Exception {
    try (var stream = getClass().getResourceAsStream("/external-agent/material-review.md")) {
      assertNotNull(stream, "Versioned material task specification must exist");
      String prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      for (String criterion : new String[]{"Technical Accuracy", "Instructional Design", "Presentation / Teaching Usability", "Reader Comprehension", "Code Samples", "Markdown / Documentation Site Quality", "Cross-page Consistency", "Practical Applicability", "Content Volume and Time Allocation", "Critical", "High", "Medium", "Low", "Positive Findings", "Recommended Fix Order", "全ページ読了後", "全面書き換え", "受講者"})
        assertTrue(prompt.contains(criterion), criterion);
    }
  }
  @Test void schemaRejectsInvalidSeverityMissingFieldsAndScores() throws Exception {
    var schema = SubAgentResultSchema.load(Path.of("."), "classpath:/subagents/schemas/material-review.schema.json");
    var parser = new SubAgentResultParser();
    String valid = fixture();
    assertTrue(schema.validate(parser.parse(valid)).isEmpty());
    for (String invalid : new String[]{valid.replace("\"HIGH\"", "\"UNKNOWN\""), valid.replace("\"summary\"", "\"missing\""), valid.replace("\"score\": 4", "\"score\": 6"), valid.replace("\"whyItMatters\"", "\"missingReason\""), valid.replace("\"score\": 4", "\"score\": -1"), valid.replace("\"line\": 3", "\"line\": 0")})
      assertFalse(schema.validate(parser.parse(invalid)).isEmpty());
    assertThrows(SubAgentValidationException.class, () -> parser.parse("{broken"));
    assertThrows(SubAgentValidationException.class, () -> parser.parse(valid + " {}"));
  }
}
