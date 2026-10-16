package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileResultTokenPagerTest {
  @Test
  void longLineKeepsNativeReadCursorAndOriginalVersionWithoutSplittingSurrogates()
      throws Exception {
    String source = "教材🙂\\\"".repeat(1000);
    var request = new Tools.ReadFileRequest("lesson.md", 1, 1, "sha", 0, "UTF-8");
    var original =
        new Tools.ReadFileResult(
            "lesson.md", 1, 1, List.of(source), false, null, "sha", null, source.length());
    var result =
        new FileResultTokenPager(TokenEstimator.conservative())
            .page(List.of(original), List.of(request), 512)
            .getFirst();
    assertThat(result.truncated()).isTrue();
    assertThat(result.nextRead().expectedVersion()).isEqualTo("sha");
    assertThat(result.nextRead().startLine()).isEqualTo(1);
    assertThat(result.content().getFirst())
        .isEqualTo(source.substring(0, result.nextRead().offset()));
    assertThat(Character.isLowSurrogate(source.charAt(result.nextRead().offset()))).isFalse();
    assertThat(
            TokenEstimator.conservative()
                .text(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(List.of(result))))
        .isLessThanOrEqualTo(512);
  }

  @Test
  void continuationStartsAtActualRequestedOffsetAndPreservesCharset() throws Exception {
    var request = new Tools.ReadFileRequest("lesson.md", 3, 3, "sha", 17, "Shift_JIS");
    var original =
        new Tools.ReadFileResult(
            "lesson.md", 3, 3, List.of("本文".repeat(1000)), false, null, "sha", null, 6000);
    var result =
        new FileResultTokenPager(TokenEstimator.conservative())
            .page(List.of(original), List.of(request), 512)
            .getFirst();
    assertThat(result.nextRead().offset()).isEqualTo(17 + result.content().getFirst().length());
    assertThat(result.nextRead().charset()).isEqualTo("Shift_JIS");
  }

  @Test
  void largeBatchReturnsAContiguousPrefixForEveryFileWithinTheSharedTokenBudget() throws Exception {
    var requests = new ArrayList<Tools.ReadFileRequest>();
    var originals = new ArrayList<Tools.ReadFileResult>();
    String source = "教材🙂\\\"".repeat(1000);
    for (int index = 0; index < 3; index++) {
      String path = "lesson" + index + ".md";
      requests.add(new Tools.ReadFileRequest(path, 5, 5, "sha" + index, 11, "UTF-8"));
      originals.add(
          new Tools.ReadFileResult(
              path, 5, 5, List.of(source), false, null, "sha" + index, null, source.length()));
    }
    var results =
        new FileResultTokenPager(TokenEstimator.conservative()).page(originals, requests, 4096);
    assertThat(results)
        .extracting(Tools.ReadFileResult::path)
        .containsExactly("lesson0.md", "lesson1.md", "lesson2.md");
    for (int index = 0; index < results.size(); index++) {
      var result = results.get(index);
      assertThat(result.content()).hasSize(1);
      assertThat(result.content().getFirst())
          .isNotEmpty()
          .isEqualTo(source.substring(0, result.nextRead().offset() - 11));
      assertThat(result.nextRead().expectedVersion()).isEqualTo("sha" + index);
      assertThat(result.nextRead().startLine()).isEqualTo(5);
      assertThat(result.truncationReason()).isEqualTo("RESULT_TOKEN_BUDGET");
      assertThat(result.continuationInstruction())
          .contains("nextRead unchanged", "not an empty file");
    }
    var sizes =
        results.stream()
            .mapToInt(
                result ->
                    result
                        .content()
                        .getFirst()
                        .codePointCount(0, result.content().getFirst().length()))
            .summaryStatistics();
    assertThat(sizes.getMax() - sizes.getMin()).isLessThanOrEqualTo(1);
    assertThat(
            TokenEstimator.conservative()
                .text(
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(results)))
        .isLessThanOrEqualTo(4096);
  }

  @Test
  void shortFilesGiveUnusedTokenQuotaToLaterFilesAndKeepTheirNativeCursor() throws Exception {
    var requests =
        List.of(new Tools.ReadFileRequest("short", 1, 1), new Tools.ReadFileRequest("long", 1, 1));
    var shortFile =
        new Tools.ReadFileResult(
            "short", 1, 1, List.of("done"), false, null, "short-version", null, 5);
    var source = "日本語".repeat(1000);
    var longFile =
        new Tools.ReadFileResult(
            "long", 1, 1, List.of(source), false, null, "long-version", null, 9000);
    var results =
        new FileResultTokenPager(TokenEstimator.conservative())
            .page(List.of(shortFile, longFile), requests, 1024);
    assertThat(results.getFirst()).isEqualTo(shortFile);
    assertThat(results.getLast().content().getFirst())
        .isNotEmpty()
        .isEqualTo(source.substring(0, results.getLast().nextRead().offset()));
    assertThat(results.getLast().truncationReason()).isEqualTo("RESULT_TOKEN_BUDGET");
  }

  @Test
  void aPageTooSmallForTheBatchFailsExplicitlyRatherThanSilentlyStarvingLaterFiles() {
    var request = new Tools.ReadFileRequest("a".repeat(1024), 1, 1);
    var result =
        new Tools.ReadFileResult(
            request.path(), 1, 1, List.of("body"), false, null, "sha", null, 5);
    assertThatThrownBy(
            () ->
                new FileResultTokenPager(TokenEstimator.conservative())
                    .page(List.of(result), List.of(request), 32))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("CONTEXT_BUDGET_INSUFFICIENT")
        .hasMessageContaining("narrow the file batch");
  }
}
