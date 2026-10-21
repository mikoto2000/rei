package dev.mikoto2000.rei.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.memory.model.Memory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

/** 長期記憶の保存と検索に必要な記憶Exporterを扱う。 */
@Component
public class MemoryExporter {

  /**
   * 長期記憶の保存と検索で受け渡す書き出し結果を表す。
   *
   * @param latestMd 最新Markdown
   * @param datedMd 日付別のMarkdown
   * @param datedJsonl 日付別のJSONL
   * @param count 件数
   */
  public record ExportResult(Path latestMd, Path datedMd, Path datedJsonl, int count) {}

  /** 記憶サービス。 */
  private final MemoryService memoryService;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  /** 指定された値や利用先を使って、MemoryExporterを初期化する。 */
  public MemoryExporter(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  /** 長期記憶の保存と検索で用いる書き出しを返す。 */
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

  /** 利用先へ渡せるよう、Markdownの形式に変換する。 */
  private String toMarkdown(List<Memory> memories) {
    StringBuilder b = new StringBuilder("# Memories\n\n");
    for (Memory memory : memories) {
      b.append("- ")
          .append(memory.id())
          .append(" [")
          .append(memory.type())
          .append("] ")
          .append(memory.content())
          .append("\n");
    }
    return b.toString();
  }

  /** 利用先へ渡せるよう、JSONLの形式に変換する。 */
  private String toJsonl(List<Memory> memories) throws IOException {
    StringBuilder b = new StringBuilder();
    for (Memory memory : memories) {
      b.append(objectMapper.writeValueAsString(memory)).append(System.lineSeparator());
    }
    return b.toString();
  }
}
