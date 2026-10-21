package dev.mikoto2000.rei.vectordocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** 重なっているトークンテキストSplitterの結果と状態の契約を検証するテスト。 */
class OverlappingTokenTextSplitterTest {

  /** 分割について、重なっている文書の分割一覧を作成することを確認する。 */
  @Test
  void splitCreatesOverlappingChunks() {
    OverlappingTokenTextSplitter splitter = new OverlappingTokenTextSplitter(6, 2);

    List<Document> chunks =
        splitter.split(new Document("zero one two three four five six seven eight nine"));

    assertTrue(chunks.size() >= 2);

    List<String> firstTokens = tokens(chunks.get(0).getText());
    List<String> secondTokens = tokens(chunks.get(1).getText());

    assertEquals(
        firstTokens.subList(firstTokens.size() - 2, firstTokens.size()),
        secondTokens.subList(0, 2));
  }

  /** 検索対象文書の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void overlapGreaterThanOrEqualToChunkSizeIsRejected() {
    try {
      new OverlappingTokenTextSplitter(10, 10);
    } catch (IllegalArgumentException exception) {
      assertTrue(exception.getMessage().contains("chunkOverlap"));
      return;
    }
    throw new AssertionError("expected IllegalArgumentException");
  }

  /** {@code tokens} の条件を再現するための補助情報を返す。 */
  private List<String> tokens(String text) {
    return List.of(text.trim().split("\\s+"));
  }
}
