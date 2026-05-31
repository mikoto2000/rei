package dev.mikoto2000.rei.memory.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

class ContentPreviewPropertyTest {

  // Feature: ai-memory-consolidation, Property 7: コンテンツプレビューの長さ制約
  @Property(tries = 100)
  void previewLengthIsAtMost100(@ForAll String content) {
    String preview = MemoryCommand.contentPreview(content);
    assertTrue(preview.length() <= 101); // 100 + ellipsis
  }
}
