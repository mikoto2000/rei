package dev.mikoto2000.rei.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 論文提供元の結果と状態の契約を検証するテスト。 */
class PaperProviderTest {
  /** 論文の調査と整理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void normalizesOpenAlexAndCrossref() {
    PaperHttpClient http =
        (uri, type, max, op) ->
            ("{\"results\":[{\"id\":\"https://openalex.org/W1\",\"title\":\"GUI\",\"publication_year\":2025,\"doi\":\"https://doi.org/10.1/x\",\"authorships\":[{\"author\":{\"display_name\":\"Alice\"}}],\"abstract_inverted_index\":{\"Hello\":[0],\"world\":[1]}}]}")
                .getBytes();
    var result =
        new OpenAlexSearchProvider(http, new PaperProperties())
            .search(query(), PaperOperation.local("s"));
    assertEquals("Hello world", result.getFirst().abstractText());
    assertEquals(List.of("Alice"), result.getFirst().authors());
    PaperHttpClient cross =
        (uri, type, max, op) ->
            "{\"message\":{\"items\":[{\"title\":[\"GUI\"],\"DOI\":\"10.1/x\",\"published\":{\"date-parts\":[[2025]]}}]}}"
                .getBytes();
    assertEquals(
        2025,
        new CrossrefSearchProvider(cross, new PaperProperties())
            .search(query(), PaperOperation.local("s"))
            .getFirst()
            .publicationYear());
  }

  /** 形式が不正な応答を受け付けないことを確認する。 */
  @Test
  void rejectsMalformedResponse() {
    var provider =
        new OpenAlexSearchProvider((u, t, m, o) -> "{}".getBytes(), new PaperProperties());
    assertThrows(PaperException.class, () -> provider.search(query(), PaperOperation.local("s")));
  }

  /** 論文の調査と整理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectsUnsafeDestinations() throws Exception {
    for (String host :
        List.of(
            "127.0.0.1",
            "10.0.0.1",
            "169.254.169.254",
            "192.168.1.1",
            "::1",
            "fc00::1",
            "100.64.0.1"))
      assertFalse(SafePaperHttpClient.isPublic(InetAddress.getByName(host)), host);
    assertTrue(SafePaperHttpClient.isPublic(InetAddress.getByName("8.8.8.8")));
    assertThrows(
        PaperException.class, () -> SafePaperHttpClient.validateUri(URI.create("file:///test")));
  }

  /** {@code query} の条件を再現するための補助情報を返す。 */
  static PaperSearchQuery query() {
    return new PaperSearchQuery(
        "GUI", 2025, null, List.of(), List.of(), false, PaperSearchQuery.Sort.RELEVANCE, 10);
  }
}
