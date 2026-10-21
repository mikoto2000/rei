package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 実装仕様の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ImplementationSpecificationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** {@code spec} の条件を再現するための補助情報を返す。 */
  ImplementationSpecification spec(
      String target,
      List<String> paths,
      List<ImplementationSpecification.AcceptanceCriterion> criteria) {
    return new ImplementationSpecification(
        1,
        "Improve greeting",
        List.of("Replace before with after"),
        target,
        paths,
        List.of("No new files"),
        criteria,
        List.of(),
        "REPLACE_EXISTING_TEXT");
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void normalizesPathsAndHashesDeterministicallyWithoutLosingRequirements() throws Exception {
    Files.writeString(root.resolve("A.txt"), "before");
    var first =
        ImplementationSpecificationValidator.validate(
            root,
            spec(
                "./A.txt",
                List.of("./A.txt"),
                List.of(
                    new ImplementationSpecification.AcceptanceCriterion(
                        "greeting", "Greeting says after"))));
    var second =
        ImplementationSpecificationValidator.validate(
            root,
            spec(
                "A.txt",
                List.of("A.txt"),
                List.of(
                    new ImplementationSpecification.AcceptanceCriterion(
                        "greeting", "Greeting says after"))));
    assertEquals(first.sha256(), second.sha256());
    assertEquals("A.txt", first.specification().target());
    assertTrue(first.task().contains("Replace before with after"));
    assertTrue(first.task().contains("No new files"));
  }

  /** 未指定重複の有無未対応のを受け付けないことを確認する。 */
  @Test
  void rejectsMissingDuplicateUnsupportedAndOversizedRequirements() throws Exception {
    Files.writeString(root.resolve("A.txt"), "before");
    var criterion = new ImplementationSpecification.AcceptanceCriterion("one", "Works");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ImplementationSpecificationValidator.validate(
                root, spec("A.txt", List.of(), List.of(criterion))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ImplementationSpecificationValidator.validate(
                root, spec("A.txt", List.of("A.txt"), List.of(criterion, criterion))));
    var invalid =
        new ImplementationSpecification(
            1,
            "x",
            List.of("y"),
            "A.txt",
            List.of("A.txt"),
            List.of(),
            List.of(criterion),
            List.of(),
            "CREATE");
    assertThrows(
        IllegalArgumentException.class,
        () -> ImplementationSpecificationValidator.validate(root, invalid));
    var huge =
        new ImplementationSpecification(
            1,
            "x",
            Collections.nCopies(5, "a".repeat(1000)),
            "A.txt",
            List.of("A.txt"),
            List.of(),
            List.of(criterion),
            List.of(),
            "REPLACE_EXISTING_TEXT");
    assertThrows(
        IllegalArgumentException.class,
        () -> ImplementationSpecificationValidator.validate(root, huge));
    assertThrows(
        IllegalArgumentException.class,
        () -> ImplementationSpecificationValidator.validate(root, null));
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void junctionOrSymlinkCannotEscapeProject() throws Exception {
    Path outside = Files.createTempDirectory(root.getParent(), "outside-");
    Files.writeString(outside.resolve("A.txt"), "before");
    Path link = root.resolve("escape");
    if (System.getProperty("os.name").startsWith("Windows")) {
      var process =
          new ProcessBuilder(
                  "cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), outside.toString())
              .redirectErrorStream(true)
              .start();
      assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    } else {
      Files.createSymbolicLink(link, outside);
    }
    try {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ImplementationSpecificationValidator.validate(
                  root,
                  spec(
                      "escape",
                      List.of("escape/A.txt"),
                      List.of(
                          new ImplementationSpecification.AcceptanceCriterion("one", "Works")))));
    } finally {
      Files.delete(link);
      Files.delete(outside.resolve("A.txt"));
      Files.delete(outside);
    }
  }

  /** 範囲外未指定を受け付けないことを確認する。 */
  @Test
  void rejectsOutsideMissingAndAllowedPathsOutsideTarget() throws Exception {
    Files.writeString(root.resolve("A.txt"), "before");
    Files.writeString(root.resolve("B.txt"), "before");
    var criteria = List.of(new ImplementationSpecification.AcceptanceCriterion("one", "Works"));
    for (String target : List.of("../outside", "missing"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ImplementationSpecificationValidator.validate(
                  root, spec(target, List.of("A.txt"), criteria)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ImplementationSpecificationValidator.validate(
                root, spec("A.txt", List.of("B.txt"), criteria)));
  }
}
