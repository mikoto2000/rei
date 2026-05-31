package dev.mikoto2000.rei.memory.command;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.ConsolidationReport;
import dev.mikoto2000.rei.memory.service.MemoryConsolidatorService;
import dev.mikoto2000.rei.memory.service.MemoryConflictResolver;
import dev.mikoto2000.rei.memory.service.MemoryExporter;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "memory", description = "記憶管理コマンド", subcommands = {
    MemoryCommand.ListCommand.class,
    MemoryCommand.SearchCommand.class,
    MemoryCommand.ForgetCommand.class,
    MemoryCommand.ExportCommand.class,
    MemoryCommand.SummarizeCommand.class,
    MemoryCommand.ConsolidateCommand.class
})
public class MemoryCommand {

  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "保存済みメモリを表示します")
  public static class ListCommand implements Runnable {
    private final MemoryService memoryService;

    @Override
    public void run() {
      List<Memory> memories = memoryService.listActiveWithExpiryCheck();
      if (memories.isEmpty()) {
        System.out.println("保存済みの記憶はありません");
        return;
      }
      for (Memory memory : memories) {
        System.out.println(memory.id() + " | " + memory.type() + " | " + preview(memory.content()));
      }
    }

    private String preview(String content) {
      return contentPreview(content);
    }
  }

  static String contentPreview(String content) {
    if (content == null) {
      return "";
    }
    return content.length() <= 100 ? content : content.substring(0, 100) + "…";
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "search", description = "記憶を検索します")
  public static class SearchCommand implements Runnable {
    private final MemoryService memoryService;

    @Parameters(index = "0", description = "検索クエリ")
    String query;

    @Option(names = "--limit", defaultValue = "10")
    int limit;

    @Override
    public void run() {
      if (query == null || query.isBlank()) {
        System.out.println("検索クエリを入力してください");
        return;
      }
      if (query.length() > 200) {
        System.out.println("検索クエリは 200 文字以内で入力してください");
        return;
      }
      List<Memory> memories = memoryService.search(query, limit);
      if (memories.isEmpty()) {
        System.out.println("該当する記憶が見つかりませんでした");
        return;
      }
      memories.forEach(m -> System.out.println(m.id() + " | " + m.type() + " | " + m.content()));
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "forget", description = "記憶を論理削除します")
  public static class ForgetCommand implements Runnable {
    private final MemoryService memoryService;

    @Parameters(index = "0", description = "記憶ID")
    String id;

    @Override
    public void run() {
      if (id == null || id.isBlank()) {
        System.out.println("有効な記憶 ID を入力してください");
        return;
      }
      var existing = memoryService.findById(id);
      if (existing.isEmpty()) {
        System.out.println("指定された ID の記憶が見つかりません");
        return;
      }
      if (existing.get().status() == MemoryStatus.DELETED) {
        System.out.println("指定された記憶はすでに削除済みです");
        return;
      }
      memoryService.updateStatus(id, MemoryStatus.DELETED);
      System.out.println("記憶 " + id + " を削除しました");
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "export", description = "記憶をエクスポートします")
  public static class ExportCommand implements Runnable {
    private final MemoryExporter memoryExporter;

    @Option(names = "--dir", defaultValue = ".rei/memory-export")
    String exportDir;

    @Override
    public void run() {
      MemoryExporter.ExportResult result = memoryExporter.export(Path.of(exportDir));
      if (result.count() == 0) {
        System.out.println("エクスポート対象の記憶がありません");
        return;
      }
      System.out.println("エクスポート完了: " + result.latestMd());
      System.out.println("件数: " + result.count());
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "summarize", description = "会話を要約して記憶候補を作ります")
  public static class SummarizeCommand implements Runnable {
    private final MemoryConsolidatorService consolidatorService;
    private final MemoryService memoryService;

    @Option(names = {"--approve", "--save"}, defaultValue = "false", description = "要約の保存を承認します")
    boolean approve;

    @Override
    public void run() {
      try {
        List<Memory> candidates = consolidatorService.extractCandidates();
        if (candidates.isEmpty()) {
          System.out.println("要約対象の会話履歴がありません");
          return;
        }
        String summary = consolidatorService.summarize(candidates.stream().map(Memory::content).toList());
        System.out.println(summary);
        if (!approve) {
          System.out.println("要約は未保存です。保存するには --approve を指定してください");
          return;
        }
        Memory memory = new Memory(null, summary, MemoryType.EPISODE_SUMMARY, MemoryScope.SHORT_TERM,
            MemoryStatus.CANDIDATE, 0.8d, null, OffsetDateTime.now(), OffsetDateTime.now());
        memoryService.save(memory);
        System.out.println("要約を記憶として保存しました");
      } catch (IllegalStateException e) {
        System.out.println("[error] " + e.getMessage());
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "consolidate", description = "会話履歴から記憶候補を抽出して保存します")
  public static class ConsolidateCommand implements Runnable {
    private final MemoryConsolidatorService consolidatorService;
    private final MemoryService memoryService;
    private final SensitiveInfoDetector sensitiveInfoDetector;
    private final MemoryConflictResolver conflictResolver;

    @Option(names = {"--approve", "--save"}, defaultValue = "false", description = "抽出候補の保存を承認します")
    boolean approve;

    @Override
    public void run() {
      try {
        List<Memory> candidates = consolidatorService.extractCandidates();
        if (candidates.isEmpty()) {
          System.out.println("保存対象の会話履歴がありません");
          return;
        }

        List<Memory> active = memoryService.listActiveWithExpiryCheck();
        int saved = 0;
        int skipped = 0;
        if (!approve) {
          System.out.println("候補は未保存です。保存するには --approve を指定してください");
        }
        for (Memory candidate : candidates) {
          if (sensitiveInfoDetector.containsSensitiveInfo(candidate.content())) {
            System.out.println("[warn] 機微情報を含むためスキップ: " + candidate.content());
            skipped++;
            continue;
          }
          var conflict = waitConflictCheck(candidate, active);
          if (conflict == null) {
            skipped++;
            continue;
          }
          if (conflict.type() == MemoryConflictResolver.ConflictType.DUPLICATE) {
            skipped++;
            continue;
          }
          if (!approve) {
            System.out.println("[candidate] " + candidate.type() + " | " + candidate.content());
            continue;
          }
          memoryService.save(candidate);
          saved++;
        }
        ConsolidationReport report = ConsolidationReport.of(candidates.size(), saved, skipped);
        System.out.println(
            "統合結果 保存=" + report.savedCount() + " / スキップ=" + report.skippedCount() + " / 候補=" + report.totalCandidates());
      } catch (IllegalStateException e) {
        System.out.println("[error] " + e.getMessage());
      }
    }

    private MemoryConflictResolver.ConflictResult waitConflictCheck(Memory candidate, List<Memory> active) {
      int timeoutSec = Math.max(1, consolidatorService.conflictTimeoutSeconds());
      try {
        return CompletableFuture
            .supplyAsync(() -> conflictResolver.check(candidate, active))
            .orTimeout(timeoutSec, TimeUnit.SECONDS)
            .join();
      } catch (CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof TimeoutException) {
          System.out.println("[warn] 競合判定がタイムアウトしたためスキップしました");
          return null;
        }
        throw new IllegalStateException("競合判定に失敗しました", cause == null ? e : cause);
      }
    }
  }
}
