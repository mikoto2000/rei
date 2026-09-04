package dev.mikoto2000.rei.summarize;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class SummarizeDependencyTest {

  @Test
  void summarizePipelineDoesNotDependOnAgentLoopOrToolAdapters() throws IOException {
    for (Path source : Files.walk(Path.of("src/main/java/dev/mikoto2000/rei/summarize"))
        .filter(path -> path.toString().endsWith(".java"))
        .toList()) {
      String text = Files.readString(source);
      assertFalse(text.contains("ChatExecutionService"), source.toString());
      assertFalse(text.contains("LlmChatClientProvider"), source.toString());
      assertFalse(text.contains("UrlContentFetchTools"), source.toString());
      assertFalse(text.contains("WebSearchTools"), source.toString());
      assertFalse(text.contains("System.out"), source.toString());
      assertFalse(text.contains("System.err"), source.toString());
    }
  }
}
