package dev.mikoto2000.rei.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 論文保管資料の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class PaperLibraryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証で使用する保存と検索の窓口を、指定された条件に合わせて用意する。 */
  PaperRepository repository() {
    return new SqlitePaperRepository(
        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db")));
  }

  /** {@code paper} の条件を再現するための補助情報を返す。 */
  Paper paper(String title, String doi) {
    return new Paper(
        null,
        title,
        List.of("Alice"),
        "GUI abstract",
        2025,
        "Venue",
        doi,
        null,
        null,
        10L,
        true,
        null,
        null,
        List.of("agent"));
  }

  /** 論文の調査と整理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void persistsAcrossRestartAndMergesNormalizedDoi() {
    var repo = repository();
    var first = repo.save(paper("Title", "https://doi.org/10.1234/ABC"), PaperOperation.local("s"));
    var second = repository().save(paper("Updated", "10.1234/abc"), PaperOperation.local("s"));
    assertEquals(first.id(), second.id());
    assertEquals("Updated", repo.find(first.id()).orElseThrow().title());
    assertEquals(1, repo.search("", 50).size());
  }

  /** 論文の調査と整理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void deduplicatesUnicodeTitleButNotDifferentAuthorOrYear() {
    var repo = repository();
    var op = PaperOperation.local("s");
    var a = repo.save(paper("  ＧＵＩ   Agent  ", null), op);
    assertEquals(a.id(), repo.save(paper("gui agent", null), op).id());
    assertNotEquals(
        a.id(),
        repo.save(
                new Paper(
                    null,
                    "gui agent",
                    List.of("Bob"),
                    null,
                    2025,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of()),
                op)
            .id());
  }

  /** 論文の調査と整理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void searchAllFieldsAndLimitAndRemove() {
    var repo = repository();
    var p = repo.save(paper("ShowUI", "10.1234/test"), PaperOperation.local("s"));
    for (String q : List.of("ShowUI", "Alice", "GUI", "2025", "10.1234/test", "Venue", "agent"))
      assertEquals(1, repo.search(q, 10).size(), q);
    assertTrue(repo.search("absent", 10).isEmpty());
    repo.remove(p.id(), PaperOperation.local("s"));
    assertTrue(repo.search("", 10).isEmpty());
    assertTrue(repo.find(p.id()).isEmpty());
  }

  /** 論文の調査と整理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void artifactsAreAtomicValidatedAndSurviveRestart() {
    var store = new PaperArtifactStore(dir, new PaperProperties());
    var id = UUID.randomUUID().toString();
    var op = PaperOperation.local("s");
    store.write(id, "extracted", "json", "hello".getBytes(), op);
    assertEquals(
        "hello",
        new String(
            new PaperArtifactStore(dir, new PaperProperties())
                .read(id, "extracted", "json")
                .orElseThrow()));
    assertThrows(PaperException.class, () -> store.read("../../outside", "extracted", "json"));
    assertThrows(PaperException.class, () -> store.read(id, "../outside", "json"));
    assertTrue(store.read(UUID.randomUUID().toString(), "extracted", "json").isEmpty());
  }

  /** 論文の調査と整理で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void validatesQueryAndSessionReferences() {
    assertThrows(
        PaperException.class,
        () ->
            new PaperSearchQuery(
                "gui",
                null,
                null,
                List.of(),
                List.of(),
                false,
                PaperSearchQuery.Sort.RELEVANCE,
                101));
    assertThrows(
        PaperException.class,
        () ->
            new PaperSearchQuery(
                "gui", 2026, 2025, List.of(), List.of(), false, PaperSearchQuery.Sort.NEWEST, 10));
    var refs = new PaperSessionReferences();
    var id = UUID.randomUUID().toString();
    refs.replace("a", List.of(id));
    assertEquals(id, refs.resolve("a", "1"));
    assertThrows(PaperException.class, () -> refs.resolve("b", "1"));
  }
}
