package dev.mikoto2000.rei.memory.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.mikoto2000.rei.memory.model.Memory;

@Component
public class MemoryExporter {

  public record ExportResult(Path latestMd, Path datedMd, Path datedJsonl, int count) {}

  private final MemoryService memoryService;
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  public MemoryExporter(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  public ExportResult export(Path exportDirectory) {
    try {
      Files.createDirectories(exportDirectory);
      List<Memory> memories = memoryService.listActiveWithExpiryCheck();
      String ts = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
      Path latestMd = exportDirectory.resolve("latest.md");
      Path datedMd = exportDirectory.resolve("memory-" + ts + ".md");
      Path datedJsonl = exportDirectory.resolve("memory-" + ts + ".jsonl");

      String markdown = toMarkdown(memories);
      Files.writeString(latestMd, markdown);
      Files.writeString(datedMd, markdown);
      Files.writeString(datedJsonl, toJsonl(memories));
      return new ExportResult(latestMd, datedMd, datedJsonl, memories.size());
    } catch (IOException e) {
      throw new IllegalStateException("記憶エクスポートに失敗しました", e);
    }
  }

  private String toMarkdown(List<Memory> memories) {
    StringBuilder b = new StringBuilder("# Memories\n\n");
    for (Memory memory : memories) {
      b.append("- ").append(memory.id()).append(" [").append(memory.type()).append("] ").append(memory.content()).append("\n");
    }
    return b.toString();
  }

  private String toJsonl(List<Memory> memories) throws IOException {
    StringBuilder b = new StringBuilder();
    for (Memory memory : memories) {
      b.append(objectMapper.writeValueAsString(memory)).append(System.lineSeparator());
    }
    return b.toString();
  }
}
