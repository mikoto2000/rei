package dev.mikoto2000.rei.core.command;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.vectordocument.VectorDocumentEntry;
import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.vectordocument.VectorDocumentService;
import dev.mikoto2000.rei.vectordocument.VectorDocumentUsageService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@RequiredArgsConstructor
@Command(
    name = "embed",
    description = "文書のベクトルストア操作を行います",
    subcommands = {
      EmbedCommand.AddCommand.class,
      EmbedCommand.SearchCommand.class,
      EmbedCommand.ListCommand.class,
      EmbedCommand.DeleteCommand.class,
      EmbedCommand.UseCommand.class
    })
public class EmbedCommand implements Runnable {

  private final VectorDocumentService vectorDocumentService;

  @Parameters(arity = "0..*", paramLabel = "DOCUMENTS...", description = "埋め込み対象ドキュメントのパス")
  String[] documents;

  @Override
  public void run() {
    if (documents == null || documents.length == 0) {
      throw new IllegalArgumentException("`embed add <files...>` を使うか、埋め込み対象ファイルを指定してください");
    }

    try {
      printAdded(vectorDocumentService.add(List.of(documents)));
    } catch (IOException e) {
      throw new RuntimeException("ドキュメント埋め込みに失敗しました", e);
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "add", description = "文書をベクトルストアへ追加します")
  public static class AddCommand implements Runnable {

    private final VectorDocumentService vectorDocumentService;

    @Parameters(arity = "1..*", paramLabel = "DOCUMENTS...", description = "埋め込み対象ドキュメントのパス")
    String[] documents;

    @Override
    public void run() {
      try {
        printAdded(vectorDocumentService.add(List.of(documents)));
      } catch (IOException e) {
        throw new RuntimeException("ドキュメント埋め込みに失敗しました", e);
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "search", description = "ベクトルストア内の文書を検索します")
  public static class SearchCommand implements Runnable {

    private final VectorDocumentService vectorDocumentService;

    @Option(names = "--top-k", description = "返却件数")
    Integer topK;

    @Option(names = "--threshold", description = "類似度しきい値")
    Double threshold;

    @Option(names = "--source", description = "source で絞り込みます")
    String source;

    @Parameters(arity = "1..*", paramLabel = "QUERY", description = "検索クエリ")
    String[] queryParts;

    @Override
    public void run() {
      List<VectorDocumentSearchResult> results = vectorDocumentService.search(String.join(" ", queryParts), topK, threshold, source);
      if (results.isEmpty()) {
        System.out.println("一致する文書はありません");
        return;
      }
      for (VectorDocumentSearchResult result : results) {
        System.out.println(result.docId() + " | " + result.source() + " | chunk=" + result.chunkIndex()
            + " | score=" + formatScore(result.score()) + " | " + result.snippet());
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "登録済み文書を一覧します")
  public static class ListCommand implements Runnable {

    private final VectorDocumentService vectorDocumentService;

    @Override
    public void run() {
      List<VectorDocumentEntry> entries = vectorDocumentService.list();
      if (entries.isEmpty()) {
        System.out.println("登録済み文書はありません");
        return;
      }

      LinkedHashMap<String, SourceSummary> grouped = new LinkedHashMap<>();
      for (VectorDocumentEntry entry : entries) {
        grouped.compute(entry.source(), (source, current) -> current == null
            ? new SourceSummary(1, entry.chunkCount(), entry.ingestedAt())
            : current.add(entry.chunkCount(), entry.ingestedAt()));
      }

      for (var groupedEntry : grouped.entrySet()) {
        SourceSummary summary = groupedEntry.getValue();
        System.out.println(groupedEntry.getKey() + " | docs=" + summary.docCount()
            + " | chunks=" + summary.chunkCount() + " | latest=" + summary.latestIngestedAt());
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "delete", description = "登録済み文書を削除します")
  public static class DeleteCommand implements Runnable {

    private final VectorDocumentService vectorDocumentService;

    @Option(names = "--doc-id", description = "削除対象 docId")
    String docId;

    @Option(names = "--source", description = "削除対象 source")
    String source;

    @Override
    public void run() {
      if (docId != null && !docId.isBlank() && (source == null || source.isBlank())) {
        boolean deleted = vectorDocumentService.deleteByDocId(docId);
        System.out.println(deleted ? "削除: " + docId : "削除対象が見つかりません: " + docId);
        return;
      }
      if ((docId == null || docId.isBlank()) && source != null && !source.isBlank()) {
        int deleted = vectorDocumentService.deleteBySource(source);
        System.out.println("削除: " + deleted + " documents | " + source);
        return;
      }
      throw new IllegalArgumentException("--doc-id か --source のどちらか一方を指定してください");
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "use", description = "chat で埋め込み文書を参照するかを確認・切り替えます")
  public static class UseCommand implements Runnable {

    private final VectorDocumentUsageService vectorDocumentUsageService;

    @Parameters(arity = "0..1", paramLabel = "STATE", description = "on または off")
    Optional<String> state;

    @Override
    public void run() {
      if (state.isEmpty()) {
        System.out.println(vectorDocumentUsageService.isEnabled() ? "on" : "off");
        return;
      }

      String normalized = state.get().trim().toLowerCase(Locale.ROOT);
      if (!normalized.equals("on") && !normalized.equals("off")) {
        throw new IllegalArgumentException("STATE は on または off を指定してください");
      }

      boolean enabled = vectorDocumentUsageService.setEnabled(normalized.equals("on"));
      System.out.println(enabled ? "on" : "off");
    }
  }

  private static void printAdded(List<VectorDocumentEntry> entries) {
    for (VectorDocumentEntry entry : entries) {
      System.out.println("追加: " + entry.docId() + " | " + entry.source() + " | chunks=" + entry.chunkCount());
    }
  }

  private static String formatScore(Double score) {
    return score == null ? "n/a" : String.format(Locale.ROOT, "%.3f", score);
  }

  private record SourceSummary(int docCount, int chunkCount, String latestIngestedAt) {

    SourceSummary add(int additionalChunks, String candidateLatest) {
      String resolvedLatest = Comparator.nullsLast(String::compareTo)
          .compare(candidateLatest, latestIngestedAt) > 0 ? candidateLatest : latestIngestedAt;
      return new SourceSummary(docCount + 1, chunkCount + additionalChunks, resolvedLatest);
    }
  }
}
