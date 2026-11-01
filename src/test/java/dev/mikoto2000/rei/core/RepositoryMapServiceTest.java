package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 保存と検索の窓口対応表サービスの結果と状態の契約を検証するテスト。 */
class RepositoryMapServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証用に、指定された内容を書き込み先へ反映する。 */
  void write(String path, String content) throws Exception {
    var target = root.resolve(path);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content);
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void mapsAstSymbolsImportsEntrypointAndExplicitTestCandidatesWithoutCommentFalsePositives()
      throws Exception {
    write(
        "src/main/java/p/App.java",
        "package p; import p.Helper; public class App { /* class Invented {} */ String text=\"class Fake {}\"; public static void main(String[] args) {} }");
    write("src/main/java/p/Helper.java", "package p; public class Helper {}");
    write("src/test/java/p/AppTest.java", "package p; public class AppTest {}");
    var service =
        new RepositoryMapService(
            path ->
                List.of(
                    "src/main/java/p/App.java",
                    "src/main/java/p/Helper.java",
                    "src/test/java/p/AppTest.java"));
    var map = service.map(root, "", 50);
    assertEquals(3, map.filesScanned());
    assertFalse(map.partial());
    var app =
        map.items().stream().filter(f -> f.path().endsWith("App.java")).findFirst().orElseThrow();
    assertEquals("p", app.packageName());
    assertTrue(
        app.symbols().stream().anyMatch(s -> s.name().equals("p.App.main") && s.entryPoint()));
    assertTrue(
        app.symbols().stream()
            .noneMatch(s -> s.name().contains("Fake") || s.name().contains("Invented")));
    assertTrue(
        map.relations().stream()
            .anyMatch(r -> r.kind().equals("IMPORT") && r.target().endsWith("Helper.java")));
    assertTrue(
        map.relations().stream()
            .anyMatch(
                r -> r.kind().equals("TEST_NAME_CANDIDATE") && r.target().endsWith("App.java")));
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void changesDeletionAndProjectSwitchRefreshTheIndex() throws Exception {
    write("App.java", "class App {}");
    var files = new AtomicReference<>(List.of("App.java"));
    var service = new RepositoryMapService(path -> files.get());
    var first = service.map(root, "App", 10);
    write("App.java", "class Changed {}");
    var second = service.map(root, "Changed", 10);
    assertNotEquals(first.version(), second.version());
    assertTrue(
        second.items().getFirst().symbols().stream().anyMatch(s -> s.name().equals("Changed")));
    files.set(List.of());
    assertTrue(service.map(root, "", 10).items().isEmpty());
    var other = Files.createDirectory(root.resolve("other"));
    files.set(List.of("App.java"));
    assertTrue(service.map(other, "", 10).items().isEmpty());
  }

  /** 共通の実行基盤で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void summaryCoversTheScannedRepositoryEvenWhenDisplayedFilesAreFiltered() throws Exception {
    write(
        "app/src/main/java/p/App.java",
        "package p; import q.Helper; public class App { public static void main(String[] args) {} }");
    write("lib/src/main/java/q/Helper.java", "package q; public class Helper {}");
    write("app/src/test/java/p/AppTest.java", "package p; public class AppTest {}");
    write("README.md", "PRIVATE_SOURCE_BODY");
    var service =
        new RepositoryMapService(
            path ->
                List.of(
                    "app/src/main/java/p/App.java",
                    "lib/src/main/java/q/Helper.java",
                    "app/src/test/java/p/AppTest.java",
                    "README.md"));
    var view = service.map(root, "Helper", 1);
    var summary = view.summary();
    assertEquals(1, view.items().size());
    assertEquals(4, summary.files());
    assertEquals(3, summary.parsedJavaFiles());
    assertEquals(1, summary.inventoryOnlyFiles());
    assertEquals(1, summary.importRelations());
    assertEquals(1, summary.testNameCandidates());
    assertTrue(
        summary.modules().stream()
            .anyMatch(
                module ->
                    module.name().equals("app") && module.files() == 2 && module.testFiles() == 1));
    assertTrue(
        summary.packages().stream().anyMatch(pkg -> pkg.name().equals("p") && pkg.files() == 2));
    assertEquals("p.App.main", summary.entryPoints().getFirst().symbol());
    assertEquals("app/src/main/java/p/App.java", summary.entryPoints().getFirst().path());
    assertFalse(summary.partial());
    assertFalse(summary.toString().contains("PRIVATE_SOURCE_BODY"));
    assertEquals(summary, service.map(root, "does-not-match", 1).summary());
  }

  /** 共通の実行基盤で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void summaryRefreshesAndMarksUnavailableJavaInformationPartial() throws Exception {
    write("App.java", "class App { public static void main(String[] args) {} }");
    var paths = new AtomicReference<>(List.of("App.java"));
    var service = new RepositoryMapService(path -> paths.get());
    assertEquals(1, service.map(root, "", 1).summary().entryPoints().size());
    write("App.java", "class App {}");
    assertTrue(service.map(root, "", 1).summary().entryPoints().isEmpty());
    write("Broken.java", "class Broken { ???");
    paths.set(List.of("App.java", "Broken.java"));
    var summary = service.map(root, "", 1).summary();
    assertTrue(summary.partial());
    assertEquals(1, summary.unparsedJavaFiles());
    paths.set(List.of());
    assertEquals(0, service.map(root, "", 1).summary().files());
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void summaryBoundsModuleOutputWithoutMisreportingTheScannedFileCount() throws Exception {
    var paths = new ArrayList<String>();
    for (int index = 0; index < 101; index++) {
      String path = String.format("module%03d/src/resource.txt", index);
      write(path, "resource");
      paths.add(path);
    }
    var summary = new RepositoryMapService(path -> paths).map(root, "", 1).summary();
    assertEquals(101, summary.files());
    assertEquals(100, summary.modules().size());
    assertTrue(summary.partial());
    assertEquals("module000", summary.modules().getFirst().name());
    assertEquals(101, summary.inventoryOnlyFiles());
  }

  /** 共通の実行基盤で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void rejectsEscapesSecretsAndOversizedSourceAndDoesNotInventSymbolsOnSyntaxError()
      throws Exception {
    write("credentials.java", "class Secret {}");
    write("Large.java", "x".repeat(140000));
    write("Bad.java", "class Broken { invalid ???");
    var service =
        new RepositoryMapService(
            path ->
                List.of(
                    "../escape.java",
                    root.resolve("credentials.java").toString(),
                    "credentials.java",
                    "Large.java",
                    "Bad.java"));
    var map = service.map(root, "", 10);
    assertEquals(2, map.items().size());
    assertTrue(map.partial());
    assertTrue(map.items().stream().allMatch(f -> f.symbols().isEmpty()));
    assertThrows(IllegalArgumentException.class, () -> service.map(root, "", 101));
  }

  /** 共通の実行基盤の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationPropagates() throws Exception {
    var service = new RepositoryMapService(path -> List.of());
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          java.util.concurrent.CancellationException.class, () -> service.map(root, "", 10));
    } finally {
      Thread.interrupted();
    }
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  @org.junit.jupiter.api.Tag("integration")
  void gitInventoryRespectsIgnoreAndDoesNotReturnSourceBodies() throws Exception {
    var process = new ProcessBuilder("git", "init", "--quiet", root.toString()).start();
    assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());
    write(".gitignore", "Ignored.java\n");
    write("Ignored.java", "class Ignored {}");
    write("App.java", "class App { String value=\"PRIVATE_BODY_MARKER\"; }");
    var view = new RepositoryMapService().map(root, "App", 5);
    assertEquals(1, view.items().size());
    assertEquals("App.java", view.items().getFirst().path());
    assertFalse(view.toString().contains("PRIVATE_BODY_MARKER"));
    assertTrue(new RepositoryMapService().map(root, "Ignored", 5).items().isEmpty());
  }

  /** 共通の実行基盤の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void toolIsRegisteredWithOptionalArgumentsAndReadCapability() {
    var callbacks =
        org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
            .toolObjects(new Tools())
            .build()
            .getToolCallbacks();
    var callback =
        Arrays.stream(callbacks)
            .filter(c -> c.getToolDefinition().name().equals("repositoryMap"))
            .findFirst()
            .orElseThrow();
    assertTrue(callback.getToolDefinition().inputSchema().contains("query"));
    assertEquals(
        Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ),
        new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
                new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(
                    false, null, null, null))
            .capabilities("repositoryMap"));
  }
}
