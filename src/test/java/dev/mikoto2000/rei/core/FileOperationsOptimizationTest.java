package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ファイル操作一覧Optimizationの結果と状態の契約を検証するテスト。 */
class FileOperationsOptimizationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** {@code tools} の条件を再現するための補助情報を返す。 */
  Tools tools() {
    return new Tools();
  }

  /** 検索条件一覧および節一覧について、各対象ファイル一度だけを読み取ることを確認する。 */
  @Test
  void queriesAndSectionsReadEachFileOnce() throws Exception {
    Files.writeString(root.resolve("a.txt"), "first\nsecond\n");
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var tools =
        new Tools() {
          /** {@code listFile} の条件を再現するための補助情報を返す。 */
          @Override
          List<String> listFile(String base, Path directory) {
            return List.of("a.txt");
          }

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
    var result =
        tools.searchAndRead(
            new Tools.SearchAndReadRequest(List.of(query("first"), query("second")), 2, 1), root);
    assertEquals(1, reads.get());
    assertEquals(2, result.getFirst().totalLines());
    assertNotNull(result.getFirst().version());
  }

  /** 変更済み形式のバージョンを受け付けないことを確認する。 */
  @Test
  void enormousUtf8LineHasSafeContinuationAndRejectsChangedVersion() throws Exception {
    String value = "日本語😀".repeat(20000);
    Files.writeString(root.resolve("a.txt"), value);
    var tools = tools();
    var result =
        tools
            .readMultiFile(List.of(new Tools.ReadFileRequest("a.txt", null, null)), root)
            .getFirst();
    assertTrue(result.truncated());
    assertTrue(result.returnedBytes() <= TextReadBudget.MAX_BYTES);
    var body = new StringBuilder(String.join("", result.content()));
    while (result.nextRead() != null) {
      result = tools.readMultiFile(List.of(result.nextRead()), root).getFirst();
      assertNull(result.error());
      body.append(String.join("", result.content()));
    }
    assertEquals(value, body.toString());
    var first =
        tools
            .readMultiFile(List.of(new Tools.ReadFileRequest("a.txt", null, null)), root)
            .getFirst();
    Files.writeString(root.resolve("a.txt"), "changed");
    assertTrue(
        tools
            .readMultiFile(List.of(first.nextRead()), root)
            .getFirst()
            .error()
            .contains("version mismatch"));
  }

  /** 状態の記録一覧について、状態の変更許容件数削除バイナリを受け付けないことを確認する。 */
  @Test
  void snapshotsRejectMutationCapacityDeletionBinaryAndTraversal() throws Exception {
    var file = root.resolve("a.txt");
    Files.writeString(file, "before");
    var racing =
        new FileSnapshots(
            path -> {
              var bytes = Files.readAllBytes(path);
              Files.writeString(path, "different size");
              return bytes;
            });
    assertThrows(java.io.IOException.class, () -> racing.get(root, file));
    Files.writeString(file, "x".repeat(FileSnapshots.MAX_FILE_BYTES + 1));
    assertThrows(java.io.IOException.class, () -> new FileSnapshots().get(root, file));
    Files.delete(file);
    assertThrows(NoSuchFileException.class, () -> new FileSnapshots().get(root, file));
    assertThrows(
        java.io.IOException.class, () -> new FileSnapshots().get(root, root.resolve("../outside")));
    Files.write(file, new byte[] {0, 1});
    assertNotNull(
        tools()
            .readMultiFile(List.of(new Tools.ReadFileRequest("a.txt", null, null)), root)
            .getFirst()
            .error());
  }

  /** 共通の実行基盤の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void repeatedRangesShareSnapshotAndCancellationPropagates() throws Exception {
    Files.writeString(root.resolve("a.txt"), "a\r\nb\r\n");
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
    var results =
        tools.readMultiFile(
            List.of(
                new Tools.ReadFileRequest("a.txt", 1, 1), new Tools.ReadFileRequest("a.txt", 2, 2)),
            root);
    assertEquals(1, reads.get());
    assertEquals(List.of("b"), results.getLast().content());
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> tools.readMultiFile(List.of(new Tools.ReadFileRequest("a.txt", 1, 1)), root));
    } finally {
      Thread.interrupted();
    }
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void byteBudgetIsSharedAcrossLargeFilesAndAllNativeContinuationsRecoverExactSource()
      throws Exception {
    String source = "日本語😀".repeat(10000);
    var requests = new ArrayList<Tools.ReadFileRequest>();
    var recovered = new LinkedHashMap<String, StringBuilder>();
    for (int index = 0; index < 3; index++) {
      String path = "lesson" + index + ".md";
      Files.writeString(root.resolve(path), source);
      requests.add(new Tools.ReadFileRequest(path, 1, 1));
      recovered.put(path, new StringBuilder());
    }
    var tools = tools();
    int pages = 0;
    while (!requests.isEmpty()) {
      var results = tools.readMultiFile(requests, root);
      assertTrue(
          results.stream().mapToInt(Tools.ReadFileResult::returnedBytes).sum()
              <= TextReadBudget.MAX_BYTES);
      var next = new ArrayList<Tools.ReadFileRequest>();
      for (var result : results) {
        assertNull(result.error());
        assertFalse(result.content().isEmpty());
        recovered.get(result.path()).append(String.join("", result.content()));
        if (result.nextRead() != null) {
          assertEquals("SOURCE_BYTE_BUDGET", result.truncationReason());
          assertEquals(result.version(), result.nextRead().expectedVersion());
          assertFalse(Character.isLowSurrogate(source.charAt(result.nextRead().offset())));
          next.add(result.nextRead());
        }
      }
      requests = next;
      assertTrue(++pages < 20, "continuations must make progress");
    }
    recovered.values().forEach(value -> assertEquals(source, value.toString()));
  }

  /** 継続入力原因を伝えることを確認する。 */
  @Test
  void totalLineBudgetLeavesAUsefulPageForEveryFileAndReportsItsContinuationCause()
      throws Exception {
    var requests = new ArrayList<Tools.ReadFileRequest>();
    for (int index = 0; index < 6; index++) {
      String path = "lines" + index + ".txt";
      Files.writeString(root.resolve(path), "line\n".repeat(1200));
      requests.add(new Tools.ReadFileRequest(path, null, null));
    }
    var results = tools().readMultiFile(requests, root);
    assertEquals(
        Tools.MAX_READ_TOTAL_LINES,
        results.stream().mapToInt(result -> result.content().size()).sum());
    for (var result : results) {
      assertFalse(result.content().isEmpty());
      assertTrue(result.content().size() <= Tools.MAX_READ_LINES_PER_FILE);
      assertEquals("SOURCE_LINE_BUDGET", result.truncationReason());
      assertEquals(result.content().size() + 1, result.nextRead().startLine());
    }
    Files.writeString(root.resolve("empty.txt"), "");
    var empty =
        tools()
            .readMultiFile(List.of(new Tools.ReadFileRequest("empty.txt", null, null)), root)
            .getFirst();
    assertFalse(empty.truncated());
    assertNull(empty.nextRead());
    assertNull(empty.truncationReason());
    assertNull(empty.continuationInstruction());
  }

  /** 共通の実行基盤で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void tokenAndSourcePaginationTogetherRecoverEveryBatchedFileWithoutGaps() throws Exception {
    String source = "日本語😀\\\"".repeat(1000);
    var requests = new ArrayList<Tools.ReadFileRequest>();
    var recovered = new LinkedHashMap<String, StringBuilder>();
    for (int index = 0; index < 3; index++) {
      String path = "paged" + index + ".md";
      Files.writeString(root.resolve(path), source);
      requests.add(new Tools.ReadFileRequest(path, 1, 1));
      recovered.put(path, new StringBuilder());
    }
    var tools = tools();
    var pager =
        new FileResultTokenPager(
            dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative());
    int pages = 0;
    while (!requests.isEmpty()) {
      var results = pager.page(tools.readMultiFile(requests, root), requests, 4096);
      var next = new ArrayList<Tools.ReadFileRequest>();
      for (var result : results) {
        assertNull(result.error());
        assertFalse(result.content().isEmpty());
        recovered.get(result.path()).append(String.join("", result.content()));
        if (result.nextRead() != null) next.add(result.nextRead());
      }
      requests = next;
      assertTrue(++pages < 30, "each batched page must advance every readable file");
    }
    recovered.values().forEach(value -> assertEquals(source, value.toString()));
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void cacheCapacityIsBoundedAndParallelReadsAreCoalesced() throws Exception {
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var snapshots =
        new FileSnapshots(
            path -> {
              reads.incrementAndGet();
              return Files.readAllBytes(path);
            });
    Files.writeString(root.resolve("same.txt"), "same");
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<java.util.concurrent.Future<String>>();
      for (int i = 0; i < 20; i++)
        futures.add(executor.submit(() -> snapshots.get(root, root.resolve("same.txt")).version()));
      for (var future : futures) assertEquals(futures.getFirst().get(), future.get());
    }
    assertEquals(1, reads.get());
    byte[] large = new byte[FileSnapshots.MAX_FILE_BYTES];
    for (int i = 0; i < 8; i++) {
      var path = root.resolve("large" + i);
      Files.write(path, large);
      if (i < 7) snapshots.get(root, path);
      else assertThrows(java.io.IOException.class, () -> snapshots.get(root, path));
    }
    assertEquals(8, reads.get());
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void searchGiantLineAndExplicitCharsetStayBounded() throws Exception {
    Files.writeString(root.resolve("a.txt"), "hit" + "日本語".repeat(20000));
    var tools =
        new Tools() {
          /** {@code listFile} の条件を再現するための補助情報を返す。 */
          @Override
          List<String> listFile(String base, Path directory) {
            return List.of("a.txt");
          }
        };
    var grep = tools.grepMultiQuery(List.of(query("hit")), root).getFirst();
    assertTrue(grep.truncated());
    assertNotNull(grep.nextRead());
    assertTrue(
        grep.matches().getFirst().content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            < TextReadBudget.MAX_BYTES);
    var result =
        tools
            .searchAndRead(new Tools.SearchAndReadRequest(List.of(query("hit")), 0, 1), root)
            .getFirst();
    assertTrue(result.truncated());
    assertNotNull(result.nextRead());
    Files.write(
        root.resolve("a.txt"),
        "\ufeff日本語\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
    var q =
        new Tools.GrepQuery(
            "日本語", ".", false, true, false, false, 0, 0, 10, true, null, null, "UTF-16LE");
    assertEquals(
        "日本語", tools.grepMultiQuery(List.of(q), root).getFirst().matches().getFirst().content());
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void largeResultsAndFileErrorsStayWithinWireLimitAndExposeOmissions() throws Exception {
    Files.writeString(root.resolve("a.txt"), "hit\t\"\\\n".repeat(1000));
    var paths = new ArrayList<String>();
    paths.add("a.txt");
    for (int i = 0; i < 1000; i++) paths.add("missing" + i);
    var tools =
        new Tools() {
          /** {@code listFile} の条件を再現するための補助情報を返す。 */
          @Override
          List<String> listFile(String base, Path directory) {
            return paths;
          }
        };
    var queries = new ArrayList<Tools.GrepQuery>();
    for (int i = 0; i < 20; i++) queries.add(query("hit"));
    var results = tools.grepMultiQuery(queries, root);
    assertTrue(
        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(results).length
            <= 1024 * 1024);
    assertTrue(results.stream().anyMatch(Tools.GrepQueryResult::truncated));
    // Separate missing-file request reaches the error reporting boundary rather than a hit cap.
    var errors = tools.grepMultiQuery(List.of(query("no match")), root).getFirst();
    assertEquals(32, errors.fileErrors().size());
    assertEquals(968, errors.omittedErrors());
    assertTrue(errors.truncated());
  }

  /** 共通の実行基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void tooManyCandidatesFailExplicitlyAndEmptyFileStillHasVersion() throws Exception {
    Files.writeString(root.resolve("a.txt"), "");
    assertNotNull(
        tools()
            .readMultiFile(List.of(new Tools.ReadFileRequest("a.txt", null, null)), root)
            .getFirst()
            .version());
    var tools =
        new Tools() {
          /** {@code listFile} の条件を再現するための補助情報を返す。 */
          @Override
          List<String> listFile(String base, Path directory) {
            return java.util.stream.IntStream.range(0, 1025).mapToObj(i -> "file" + i).toList();
          }
        };
    assertTrue(
        tools
            .grepMultiQuery(List.of(query("x")), root)
            .getFirst()
            .error()
            .contains("narrow baseDir/globs"));
    var result =
        tools.searchAndRead(new Tools.SearchAndReadRequest(List.of(query("x")), 0, 1), root);
    assertTrue(result.getFirst().error().contains("narrow baseDir/globs"));
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void globsNarrowInventoryBeforeLimitAndQueriesKeepSeparateInventories() throws Exception {
    Files.writeString(root.resolve("a.txt"), "hit");
    Files.writeString(root.resolve("b.java"), "hit");
    var paths = new ArrayList<String>();
    paths.add("a.txt");
    paths.add("b.java");
    for (int i = 0; i < 1024; i++) paths.add("other" + i + ".log");
    var tools =
        new Tools() {
          /** {@code listFile} の条件を再現するための補助情報を返す。 */
          @Override
          List<String> listFile(String base, Path directory) {
            return paths;
          }
        };
    var txt =
        new Tools.GrepQuery("hit", ".", false, true, false, false, 0, 0, null, true, "*.txt", null);
    var java =
        new Tools.GrepQuery("hit", ".", false, true, false, false, 0, 0, null, true, null, "*.log");
    var results = tools.grepMultiQuery(List.of(query("hit"), txt, java), root);
    assertNotNull(results.getFirst().error());
    assertNull(results.get(1).error());
    assertEquals(
        List.of("a.txt"), results.get(1).matches().stream().map(Tools.GrepMatch::path).toList());
    assertNull(results.get(2).error());
    assertEquals(2, results.get(2).matches().size());
    var search =
        tools.searchAndRead(new Tools.SearchAndReadRequest(List.of(txt, java), 0, 2), root);
    assertEquals(2, search.size());
    assertTrue(search.stream().allMatch(r -> r.error() == null));
  }

  /** 共通の実行基盤で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void grepAndSearchGlobsAreRelativeToBaseDirAndIncludeDirectChildren() throws Exception {
    Files.createDirectories(root.resolve("src/foo/deep"));
    var paths = List.of("src/A.java", "src/foo/B.java", "src/foo/deep/C.java", "src/pom.xml");
    for (String path : paths) Files.writeString(root.resolve(path), "hit");
    var tools =
        new Tools() {
          /** {@code listFile} の条件を再現するための補助情報を返す。 */
          @Override
          List<String> listFile(String base, Path directory) {
            return paths;
          }
        };
    var queries =
        List.of(
            globQuery("src", "*.java", null),
            globQuery("src", "**/*.java", null),
            globQuery("src", "foo/*.java", null),
            globQuery("src", "**/*.{java,xml}", "foo/**"),
            globQuery(root.resolve("src").toString(), "**\\*.java", "**/B.java"));
    var expected =
        List.of(
            List.of("src/A.java", "src/foo/B.java", "src/foo/deep/C.java"),
            List.of("src/A.java", "src/foo/B.java", "src/foo/deep/C.java"),
            List.of("src/foo/B.java"),
            List.of("src/A.java", "src/pom.xml"),
            List.of("src/A.java", "src/foo/deep/C.java"));
    var results = tools.grepMultiQuery(queries, root);
    for (int i = 0; i < queries.size(); i++) {
      assertNull(results.get(i).error());
      assertEquals(
          expected.get(i), results.get(i).matches().stream().map(Tools.GrepMatch::path).toList());
      var search =
          tools.searchAndRead(new Tools.SearchAndReadRequest(List.of(queries.get(i)), 0, 8), root);
      assertTrue(search.stream().allMatch(r -> r.error() == null));
      assertEquals(expected.get(i), search.stream().map(Tools.SearchAndReadResult::path).toList());
    }
  }

  /** 共通の実行基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidGlobFailsOnlyItsQuery() throws Exception {
    Files.writeString(root.resolve("a.java"), "hit");
    var tools =
        new Tools() {
          /** {@code listFile} の条件を再現するための補助情報を返す。 */
          @Override
          List<String> listFile(String base, Path directory) {
            return List.of("a.java");
          }
        };
    var results =
        tools.grepMultiQuery(
            List.of(globQuery(".", "[", null), globQuery(".", "*.java", null)), root);
    assertNotNull(results.getFirst().error());
    assertNull(results.get(1).error());
    assertEquals(1, results.get(1).matches().size());
  }

  /** {@code globQuery} の条件を再現するための補助情報を返す。 */
  private static Tools.GrepQuery globQuery(String base, String include, String exclude) {
    return new Tools.GrepQuery(
        "hit", base, false, true, false, false, 0, 0, null, true, include, exclude);
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void directoryLinksCannotAliasTheReadingBoundary() throws Exception {
    var directory = Files.createDirectory(root.resolve("real"));
    Files.writeString(directory.resolve("a.txt"), "secret");
    var link = root.resolve("alias");
    if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
      var process =
          new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), directory.toString())
              .redirectErrorStream(true)
              .start();
      assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    } else Files.createSymbolicLink(link, directory);
    try {
      assertNotNull(
          tools()
              .readMultiFile(List.of(new Tools.ReadFileRequest("alias/a.txt", null, null)), root)
              .getFirst()
              .error());
    } finally {
      Files.delete(link);
    }
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void totalSearchLineLimitCutsActualBody() throws Exception {
    var paths = new ArrayList<String>();
    for (int i = 0; i < 8; i++) {
      String name = "f" + i + ".txt";
      Files.writeString(
          root.resolve(name), "context\n".repeat(499) + "hit\n" + "context\n".repeat(500));
      paths.add(name);
    }
    var tools =
        new Tools() {
          /** {@code listFile} の条件を再現するための補助情報を返す。 */
          @Override
          List<String> listFile(String base, Path directory) {
            return paths;
          }
        };
    var result =
        tools.searchAndRead(new Tools.SearchAndReadRequest(List.of(query("hit")), 1000, 8), root);
    assertTrue(result.stream().mapToInt(Tools.SearchAndReadResult::totalLines).sum() <= 5000);
    assertTrue(result.stream().anyMatch(Tools.SearchAndReadResult::truncated));
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void externalSameLengthChangeCannotReuseStaleSearch() throws Exception {
    var path = root.resolve("a.txt");
    Files.writeString(path, "hit\n");
    var stamp = Files.getLastModifiedTime(path);
    var tools =
        new Tools() {
          /** {@code listFile} の条件を再現するための補助情報を返す。 */
          @Override
          List<String> listFile(String base, Path directory) {
            return List.of("a.txt");
          }
        };
    assertEquals(1, tools.grepMultiQuery(List.of(query("hit")), root).getFirst().matches().size());
    Files.writeString(path, "new\n");
    Files.setLastModifiedTime(path, stamp);
    assertTrue(tools.grepMultiQuery(List.of(query("hit")), root).getFirst().matches().isEmpty());
  }

  /** {@code query} の条件を再現するための補助情報を返す。 */
  static Tools.GrepQuery query(String pattern) {
    return new Tools.GrepQuery(
        pattern, ".", false, true, false, false, 0, 0, null, true, null, null);
  }
}
