package dev.mikoto2000.rei.vectordocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class OverlappingTokenTextSplitterTest {

  @Test
  void splitCreatesOverlappingChunks() {
    OverlappingTokenTextSplitter splitter = new OverlappingTokenTextSplitter(6, 2);

    List<Document> chunks = splitter.split(new Document("zero one two three four five six seven eight nine"));

    assertTrue(chunks.size() >= 2);

    List<String> firstTokens = tokens(chunks.get(0).getText());
    List<String> secondTokens = tokens(chunks.get(1).getText());

    assertEquals(firstTokens.subList(firstTokens.size() - 2, firstTokens.size()), secondTokens.subList(0, 2));
  }

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

  private List<String> tokens(String text) {
    return List.of(text.trim().split("\\s+"));
  }
}
