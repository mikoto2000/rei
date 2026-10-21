package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Javaシンボル読み取りの結果と状態の契約を検証するテスト。 */
class JavaSymbolReadTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  Tools.ReadFileRequest request(String symbol, boolean body, boolean docs) {
    return new Tools.ReadFileRequest(
        "C.java", null, null, null, null, null, symbol, body, docs, 0, true, null);
  }

  /** 選択内容を必要とすることを確認する。 */
  @Test
  void overloadsRequireSelectionAndExactMethodPreservesDocsAndSignature() throws Exception {
    Files.writeString(
        root.resolve("C.java"),
        "package p;\nclass C {\n/** 日本語 docs */\n@Deprecated\n<T> T work(\n T value) { return value; }\nint work(int value) { return value; }\nC() {}\nint field;\nclass Nested {}\n}\n");
    var tools = new Tools();
    var ambiguous = tools.readMultiFile(List.of(request("p.C#work", true, true)), root).getFirst();
    assertTrue(ambiguous.error().contains("Ambiguous"));
    assertEquals(2, ambiguous.candidates().size());
    var read = tools.readMultiFile(List.of(request("p.C#work(T)", false, true)), root).getFirst();
    assertNull(read.error());
    assertTrue(String.join("\n", read.content()).contains("日本語 docs"));
    assertTrue(String.join("\n", read.content()).contains("@Deprecated"));
    assertFalse(String.join("\n", read.content()).contains("return value"));
    assertEquals("p.C#work(T)", read.symbol().symbolId());
    assertTrue(read.symbol().endOffset() > read.symbol().startOffset());
    assertTrue(read.symbol().endLine() >= 6);
    assertFalse(read.ownerOverview().isEmpty());
    for (String id : List.of("p.C#C()", "p.C#field", "p.C.Nested", "p"))
      assertNull(
          tools.readMultiFile(List.of(request(id, true, false)), root).getFirst().error(), id);
  }

  /** 共通の実行基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void sourceEditsInvalidateOffsetsAndContinuationIsVersionBound() throws Exception {
    Files.writeString(
        root.resolve("C.java"), "class C { void work() { /*" + "日本語".repeat(12000) + "*/ } }");
    var tools = new Tools();
    var first = tools.readMultiFile(List.of(request("C#work()", true, false)), root).getFirst();
    assertNull(first.error());
    assertTrue(first.truncated());
    assertNotNull(first.nextRead());
    assertTrue(
        dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative()
                .text(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(first))
            <= 8000);
    var next = tools.readMultiFile(List.of(first.nextRead()), root).getFirst();
    assertNull(next.error());
    assertEquals(first.version(), next.version());
    Files.writeString(root.resolve("C.java"), "\n\nclass C { void work() { } }");
    assertTrue(
        tools
            .readMultiFile(List.of(first.nextRead()), root)
            .getFirst()
            .error()
            .contains("version"));
    var fresh = tools.readMultiFile(List.of(request("C#work()", true, false)), root).getFirst();
    assertEquals(3, fresh.symbol().line());
    assertNotEquals(first.version(), fresh.version());
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void syntaxAndMissingSymbolAreExplicitAndCrLfBodyIsReadable() throws Exception {
    Files.writeString(
        root.resolve("C.java"),
        "\ufeffclass C {\r\n void work(String... values) {\r\n // body\r\n }\r\n}\r\n");
    var tools = new Tools();
    var read =
        tools.readMultiFile(List.of(request("C#work(String[])", true, false)), root).getFirst();
    assertNull(read.error());
    assertTrue(String.join("\n", read.content()).contains("body"));
    assertTrue(
        tools
            .readMultiFile(List.of(request("C#absent", true, false)), root)
            .getFirst()
            .error()
            .contains("not found"));
    Files.writeString(root.resolve("C.java"), "class C { void broken(");
    assertTrue(
        tools
            .readMultiFile(List.of(request("C", true, false)), root)
            .getFirst()
            .error()
            .contains("SYNTAX_ERROR"));
  }

  /** 単一状態の記録ごとのファイルを利用することを確認する。 */
  @Test
  void projectWideAmbiguityAndRepeatedDeclarationsUseOneSnapshotPerFile() throws Exception {
    Files.writeString(root.resolve("C.java"), "class C { void work() {} int field; }");
    Files.writeString(root.resolve("Other.java"), "class C { void work() {} }");
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var tools =
        new Tools() {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          @Override
          FileSnapshots newSnapshots() {
            return new FileSnapshots(
                path -> {
                  reads.incrementAndGet();
                  return Files.readAllBytes(path);
                });
          }
        };
    tools.setRepositoryMaps(new RepositoryMapService(path -> List.of("C.java", "Other.java")));
    var global =
        new Tools.ReadFileRequest(
            null, null, null, null, null, null, "C#work()", true, false, 0, false, null);
    assertEquals(2, tools.readMultiFile(List.of(global), root).getFirst().candidates().size());
    reads.set(0);
    var result =
        tools.readMultiFile(
            List.of(request("C#work()", true, false), request("C#field", true, false)), root);
    assertTrue(result.stream().allMatch(item -> item.error() == null));
    assertEquals(1, reads.get());
  }

  /** 構文情報境界条件を利用することを確認する。 */
  @Test
  void interfacesRecordsEnumsAndAnnotationLiteralsUseAstBoundaries() throws Exception {
    Files.writeString(
        root.resolve("C.java"),
        "@SuppressWarnings(\"{ }\") class C { }\ninterface I { void work(); }\nrecord R(int value) {}\nenum E { VALUE; }\n@interface A { String value(); }\n");
    var tools = new Tools();
    for (String id : List.of("C", "I#work()", "R", "E", "A#value()"))
      assertNull(
          tools.readMultiFile(List.of(request(id, false, false)), root).getFirst().error(), id);
    var read = tools.readMultiFile(List.of(request("C", false, false)), root).getFirst();
    assertEquals("@SuppressWarnings(\"{ }\") class C", String.join("\n", read.content()).strip());
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void compilerUnavailableOversizeAndOutsidePathsAreExplicit() throws Exception {
    Files.writeString(root.resolve("C.java"), "class C {}");
    var tools = new Tools();
    tools.setRepositoryMaps(new RepositoryMapService(path -> List.of("C.java"), null));
    assertTrue(
        tools
            .readMultiFile(List.of(request("C", true, false)), root)
            .getFirst()
            .error()
            .contains("COMPILER_UNAVAILABLE"));
    tools = new Tools();
    Files.writeString(root.resolve("C.java"), "class C { /*" + "x".repeat(131072) + "*/ }");
    assertTrue(
        tools
            .readMultiFile(List.of(request("C", true, false)), root)
            .getFirst()
            .error()
            .contains("TOO_LARGE"));
    assertTrue(
        tools
            .readMultiFile(
                List.of(
                    new Tools.ReadFileRequest(
                        "../C.java",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "C",
                        true,
                        false,
                        0,
                        false,
                        null)),
                root)
            .getFirst()
            .error()
            .contains("outside"));
  }

  /** 既存のツール完了時の処理について、シンボルを受け付けられることを確認する。 */
  @Test
  void existingToolCallbackAcceptsSymbolOnlyJsonWithoutLineFields() throws Exception {
    Files.writeString(root.resolve("C.java"), "class C { void work() {} }");
    var tools = new Tools();
    var callback =
        Arrays.stream(
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                    .toolObjects(tools)
                    .build()
                    .getToolCallbacks())
            .filter(tool -> tool.getToolDefinition().name().equals("readMultiFile"))
            .findFirst()
            .orElseThrow();
    try (var scope =
        dev.mikoto2000.rei.core.chat.AgentRunScope.open(
            new dev.mikoto2000.rei.core.chat.AgentRunContext(
                "symbol", "session", root, UUID.randomUUID().toString()))) {
      var value =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(
                  callback.call(
                      "{\"files\":[{\"path\":\"C.java\",\"symbol\":\"C#work()\",\"includeBody\":false}]}"));
      assertTrue(value.get(0).get("error").isNull());
      assertEquals("C#work()", value.get(0).get("symbol").get("symbolId").asText());
    }
  }

  /** 共通の実行基盤の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationDoesNotTurnIntoAnEmptySearchResult() throws Exception {
    Files.writeString(root.resolve("C.java"), "class C {}");
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> new Tools().readMultiFile(List.of(request("C", true, false)), root));
    } finally {
      Thread.interrupted();
    }
  }

  /** 共通の実行基盤で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void unicodeEscapedClassBracesNeverExposeBodiesAsSignatures() throws Exception {
    String source =
        "class C [OPEN] String value=\"PRIVATE_BODY_MARKER\"; void work() {} [CLOSE]"
            .replace("[OPEN]", Character.toString(92) + "u007b")
            .replace("[CLOSE]", Character.toString(92) + "u007d");
    Files.writeString(root.resolve("C.java"), source);
    var maps = new RepositoryMapService(path -> List.of("C.java"));
    assertFalse(maps.map(root, "", 10).toString().contains("PRIVATE_BODY_MARKER"));
    assertNotNull(
        new Tools().readMultiFile(List.of(request("C", false, false)), root).getFirst().error());
    assertNull(
        new Tools().readMultiFile(List.of(request("C", true, false)), root).getFirst().error());
  }
}
