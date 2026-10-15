package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LargeReadProgressTest {
  @TempDir Path root;

  @Test
  void newRangeStillCountsAfter1024Lines() throws Exception {
    var evaluator = new ProgressEvaluator(root);
    var json = new ObjectMapper();
    var before = evaluator.beforeTool("readMultiFile", "{}");
    var first =
        json.createObjectNode().put("path", "docs/A.md").put("version", "v1").put("startLine", 1);
    first.set(
        "content",
        json.valueToTree(IntStream.rangeClosed(1, 1024).mapToObj(i -> "line " + i).toList()));
    assertThat(evaluator.afterTool("readMultiFile", "{}", first.toString(), before))
        .extracting(ProgressEvidence::kind)
        .contains(ProgressEvent.NEW_INFORMATION);
    var second =
        json.createObjectNode()
            .put("path", "docs/A.md")
            .put("version", "v1")
            .put("startLine", 1025);
    second.set("content", json.valueToTree(java.util.List.of("new line")));
    assertThat(evaluator.afterTool("readMultiFile", "{}", second.toString(), before))
        .extracting(ProgressEvidence::kind)
        .contains(ProgressEvent.NEW_INFORMATION);
  }

  @Test
  void manyLargeMarkdownFilesUseOneRangePerVersionAndCountOnlyReturnedLines() throws Exception {
    var e = new ProgressEvaluator(root);
    var json = new ObjectMapper();
    for (int file = 0; file < 120; file++) {
      for (int start = 1; start <= 10000; start += 400) {
        var result =
            json.createObjectNode()
                .put("path", "docs/chapter" + file + ".md")
                .put("version", "v1")
                .put("startLine", start);
        result.set("content", json.valueToTree(java.util.Collections.nCopies(400, "line")));
        assertThat(
                e.afterTool(
                    "readMultiFile", "{}", result.toString(), e.beforeTool("readMultiFile", "{}")))
            .extracting(ProgressEvidence::kind)
            .containsExactly(ProgressEvent.NEW_INFORMATION);
      }
    }
    assertThat(e.informationSnapshot().files()).hasSize(120);
    assertThat(e.informationSnapshot().files())
        .allSatisfy(
            f -> assertThat(f.ranges()).containsExactly(new InformationLedger.LineRange(1, 10001)));
  }

  @Test
  void byteTruncatedLineAndContinuationSuffixDoNotClaimWholeLine() {
    var e = new ProgressEvaluator(root);
    var before = e.beforeTool("readMultiFile", "{}");
    String prefix =
        "{\"path\":\"A\",\"version\":\"v1\",\"startLine\":1,\"content\":[\"first\",\"pre\"],\"truncated\":true,\"nextRead\":{\"startLine\":2,\"offset\":3}}";
    e.afterTool("readMultiFile", "{}", prefix, before);
    assertThat(e.informationSnapshot().files().getFirst().ranges())
        .containsExactly(new InformationLedger.LineRange(1, 2));
    e.afterTool(
        "readMultiFile",
        "{\"files\":[{\"path\":\"A\",\"startLine\":2,\"offset\":3}]}",
        "{\"path\":\"A\",\"version\":\"v1\",\"startLine\":2,\"content\":[\"suffix\",\"third\"]}",
        before);
    assertThat(e.informationSnapshot().files().getFirst().ranges())
        .containsExactly(
            new InformationLedger.LineRange(1, 2), new InformationLedger.LineRange(3, 4));
    assertThat(
            e.afterTool(
                "readMultiFile",
                "{}",
                "{\"path\":\"./A\",\"version\":\"v1\",\"startLine\":2,\"content\":[\"prefixsuffix\"]}",
                before))
        .extracting(ProgressEvidence::kind)
        .contains(ProgressEvent.NEW_INFORMATION);
  }

  @Test
  void emptyOutsideRangeAndFailedResultsDoNotRecordRanges() {
    var e = new ProgressEvaluator(root);
    var before = e.beforeTool("readMultiFile", "{}");
    assertThat(
            e.afterTool(
                "readMultiFile",
                "{}",
                "{\"path\":\"A\",\"version\":\"v1\",\"startLine\":1000,\"content\":[]}",
                before))
        .isEmpty();
    assertThat(
            e.afterTool(
                "readMultiFile",
                "{}",
                "{\"path\":\"A\",\"error\":\"missing\",\"content\":[]}",
                before))
        .isEmpty();
    assertThat(e.informationSnapshot().files()).isEmpty();
  }

  @Test
  void fragmentOfFullyObservedLineDoesNotCreateNewInformation() {
    var e = new ProgressEvaluator(root);
    var before = e.beforeTool("readMultiFile", "{}");
    e.afterTool(
        "readMultiFile",
        "{}",
        "{\"path\":\"A\",\"version\":\"v1\",\"startLine\":1,\"content\":[\"whole line\"]}",
        before);
    assertThat(
            e.afterTool(
                "readMultiFile",
                "{}",
                "{\"path\":\"A\",\"version\":\"v1\",\"startLine\":1,\"content\":[\"whole\"],\"truncated\":true,\"nextRead\":{\"startLine\":1,\"offset\":5}}",
                before))
        .isEmpty();
  }

  @Test
  void identicalCharactersAtDifferentContinuationOffsetsAreNewObservedFragments() {
    var e = new ProgressEvaluator(root);
    var before = e.beforeTool("readMultiFile", "{}");
    String result =
        "{\"path\":\"A\",\"version\":\"v1\",\"startLine\":1,\"content\":[\"aaa\"],\"truncated\":true,\"nextRead\":{\"startLine\":1,\"offset\":3}}";
    e.afterTool("readMultiFile", "{}", result, before);
    String args = "{\"files\":[{\"path\":\"A\",\"startLine\":1,\"offset\":3}]}";
    String next = result.replace("\"offset\":3", "\"offset\":6");
    assertThat(e.afterTool("readMultiFile", args, next, before))
        .extracting(ProgressEvidence::kind)
        .contains(ProgressEvent.NEW_INFORMATION);
    assertThat(e.afterTool("readMultiFile", args, next, before)).isEmpty();
  }

  @Test
  void batchRequestsForSameFileAssociateTheirOwnContinuationOffsets() {
    var e = new ProgressEvaluator(root);
    String args =
        "{\"files\":[{\"path\":\"A\",\"startLine\":1},{\"path\":\"A\",\"startLine\":1,\"endLine\":1,\"offset\":3}]}";
    String result =
        "[{\"path\":\"A\",\"version\":\"v1\",\"startLine\":1,\"content\":[\"whole line\",\"second\"]},{\"path\":\"A\",\"version\":\"v1\",\"startLine\":1,\"content\":[\"le line\"]}]";
    e.afterTool("readMultiFile", args, result, e.beforeTool("readMultiFile", args));
    assertThat(e.informationSnapshot().files().getFirst().ranges())
        .containsExactly(new InformationLedger.LineRange(1, 3));
  }
}
