package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import dev.mikoto2000.rei.vectordocument.VectorDocumentEntry;
import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.vectordocument.VectorDocumentService;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** 文書のベクトルストア操作を行いますを行うための利用者コマンド。 */
@Component
@RequiredArgsConstructor
@Command(
    name = "embed",
    description = "文書のベクトルストア操作を行います",
    subcommands = {
      EmbedCommand.AddCommand.class,
      EmbedCommand.SearchCommand.class,
      EmbedCommand.ListCommand.class,
      EmbedCommand.DeleteCommand.class
    })
public class EmbedCommand implements Runnable {
  /** 共通の実行基盤のうち、レポートの操作を利用者コマンドとして受け付ける。 */
  private abstract static class ReportCommand implements Runnable {
    /** レポート出力。 */
    private java.io.PrintWriter reportOutput;

    /** 共通の実行基盤で用いる出力先を返す。 */
    java.io.PrintWriter out() {
      return reportOutput == null ? new java.io.PrintWriter(System.out, true) : reportOutput;
    }
  }

  /** 共通の実行基盤で用いるレポートを返す。 */
  private static String report(ReportCommand command) {
    var text = new java.io.StringWriter();
    command.reportOutput = new java.io.PrintWriter(text, true);
    command.run();
    return text.toString().replaceFirst("[\\r\\n]+$", "");
  }

  /** 共通の実行基盤で用いるレポート検索を返す。 */
  public static String reportSearch(
      VectorDocumentService service, String query, Integer topK, Double threshold, String source) {
    var command = new SearchCommand(service);
    command.queryParts = new String[] {query};
    command.topK = topK;
    command.threshold = threshold;
    command.source = source;
    return report(command);
  }

  /** 共通の実行基盤で用いるレポート一覧を返す。 */
  public static String reportList(VectorDocumentService service) {
    return report(new ListCommand(service));
  }

  /** 共通の実行基盤で用いるレポート削除を返す。 */
  public static String reportDelete(VectorDocumentService service, String id, String source) {
    var command = new DeleteCommand(service);
    command.docId = id;
    command.source = source;
    return report(command);
  }

  /** ベクトル文書サービス。 */
  private final VectorDocumentService vectorDocumentService;

  /** 非同期ベクトル文書サービス。 */
  private final AsyncVectorDocumentService asyncVectorDocumentService;

  /** 保存済み文書。 */
  @Parameters(
      arity = "0..*",
      paramLabel = "DOCUMENTS...",
      description = "埋め込み対象ドキュメントのパス",
      completionCandidates = dev.mikoto2000.rei.core.completion.PathCompletionCandidates.Any.class)
  String[] documents;

  /** 文書のベクトルストア操作を行いますを行う。 */
  @Override
  public void run() {
    if (documents == null || documents.length == 0) {
      throw new IllegalArgumentException("`embed add <files...>` を使うか、埋め込み対象ファイルを指定してください");
    }

    List<String> resolvedDocuments = resolveDocuments(List.of(documents));
    asyncVectorDocumentService.addAsync(resolvedDocuments);
    printQueued(List.of(documents));
  }

  /** 文書をベクトルストアへ追加しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "add", description = "文書をベクトルストアへ追加します")
  public static class AddCommand implements Runnable {

    /** 非同期ベクトル文書サービス。 */
    private final AsyncVectorDocumentService asyncVectorDocumentService;

    /** 保存済み文書。 */
    @Parameters(
        arity = "1..*",
        paramLabel = "DOCUMENTS...",
        description = "埋め込み対象ドキュメントのパス",
        completionCandidates =
            dev.mikoto2000.rei.core.completion.PathCompletionCandidates.Any.class)
    String[] documents;

    /** 文書をベクトルストアへ追加しますを行う。 */
    @Override
    public void run() {
      List<String> resolvedDocuments = resolveDocuments(List.of(documents));
      asyncVectorDocumentService.addAsync(resolvedDocuments);
      printQueued(List.of(documents));
    }
  }

  /** ベクトルストア内の文書を検索しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "search", description = "ベクトルストア内の文書を検索します")
  public static class SearchCommand extends ReportCommand {

    /** ベクトル文書サービス。 */
    private final VectorDocumentService vectorDocumentService;

    /** 上位件数。 */
    @Option(names = "--top-k", description = "返却件数")
    Integer topK;

    /** 判定の境界値。 */
    @Option(names = "--threshold", description = "類似度しきい値")
    Double threshold;

    /** 取得元。 */
    @Option(names = "--source", description = "source で絞り込みます")
    String source;

    /** 検索条件部分一覧。 */
    @Parameters(arity = "1..*", paramLabel = "QUERY", description = "検索クエリ")
    String[] queryParts;

    /** ベクトルストア内の文書を検索しますを行う。 */
    @Override
    public void run() {
      List<VectorDocumentSearchResult> results =
          vectorDocumentService.search(String.join(" ", queryParts), topK, threshold, source);
      if (results.isEmpty()) {
        out().println("一致する文書はありません");
        return;
      }
      for (VectorDocumentSearchResult result : results) {
        out()
            .println(
                result.docId()
                    + " | "
                    + result.source()
                    + " | chunk="
                    + result.chunkIndex()
                    + " | score="
                    + formatScore(result.score())
                    + " | "
                    + result.snippet());
      }
    }
  }

  /** 登録済み文書を一覧しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "登録済み文書を一覧します")
  public static class ListCommand extends ReportCommand {

    /** ベクトル文書サービス。 */
    private final VectorDocumentService vectorDocumentService;

    /** 登録済み文書を一覧しますを行う。 */
    @Override
    public void run() {
      List<VectorDocumentEntry> entries = vectorDocumentService.list();
      if (entries.isEmpty()) {
        out().println("登録済み文書はありません");
        return;
      }

      LinkedHashMap<String, SourceSummary> grouped = new LinkedHashMap<>();
      for (VectorDocumentEntry entry : entries) {
        grouped.compute(
            entry.source(),
            (source, current) ->
                current == null
                    ? new SourceSummary(1, entry.chunkCount(), entry.ingestedAt())
                    : current.add(entry.chunkCount(), entry.ingestedAt()));
      }

      for (var groupedEntry : grouped.entrySet()) {
        SourceSummary summary = groupedEntry.getValue();
        out()
            .println(
                groupedEntry.getKey()
                    + " | docs="
                    + summary.docCount()
                    + " | chunks="
                    + summary.chunkCount()
                    + " | latest="
                    + summary.latestIngestedAt());
      }
    }
  }

  /** 登録済み文書を削除しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "delete", description = "登録済み文書を削除します")
  public static class DeleteCommand extends ReportCommand {

    /** ベクトル文書サービス。 */
    private final VectorDocumentService vectorDocumentService;

    /** 文書の識別子。 */
    @Option(names = "--doc-id", description = "削除対象 docId")
    String docId;

    /** 取得元。 */
    @Option(names = "--source", description = "削除対象 source")
    String source;

    /** 登録済み文書を削除しますを行う。 */
    @Override
    public void run() {
      if (docId != null && !docId.isBlank() && (source == null || source.isBlank())) {
        boolean deleted = vectorDocumentService.deleteByDocId(docId);
        out().println(deleted ? "削除: " + docId : "削除対象が見つかりません: " + docId);
        return;
      }
      if ((docId == null || docId.isBlank()) && source != null && !source.isBlank()) {
        int deleted = vectorDocumentService.deleteBySource(source);
        out().println("削除: " + deleted + " documents | " + source);
        return;
      }
      throw new IllegalArgumentException("--doc-id か --source のどちらか一方を指定してください");
    }
  }

  /** 利用者に結果を伝えるため、追加済みを出力する。 */
  private static void printAdded(List<VectorDocumentEntry> entries) {
    for (VectorDocumentEntry entry : entries) {
      System.out.println(
          "追加: " + entry.docId() + " | " + entry.source() + " | chunks=" + entry.chunkCount());
    }
  }

  /** 利用者に結果を伝えるため、待機中を出力する。 */
  private static void printQueued(List<String> sources) {
    System.out.println("追加処理を開始: " + String.join(", ", sources));
  }

  /** 指定や設定に応じた対象を利用するため、保存済み文書を確定する。 */
  static List<String> resolveDocuments(List<String> documents) {
    return dev.mikoto2000.rei.core.completion.DocumentPathResolver.resolve(
        documents, Path.of(System.getProperty("user.dir")));
  }

  /** 内容を表示・参照しやすくするため、評価値を整形する。 */
  private static String formatScore(Double score) {
    return score == null ? "n/a" : String.format(Locale.ROOT, "%.3f", score);
  }

  /**
   * 共通の実行基盤で受け渡す取得元要約を表す。
   *
   * @param docCount 文書件数
   * @param chunkCount 文書の分割単位件数
   * @param latestIngestedAt 最新Ingested日時
   */
  private record SourceSummary(int docCount, int chunkCount, String latestIngestedAt) {

    /** 新たな対象を登録し、後から利用できるようにする。 */
    SourceSummary add(int additionalChunks, String candidateLatest) {
      String resolvedLatest =
          Comparator.nullsLast(String::compareTo).compare(candidateLatest, latestIngestedAt) > 0
              ? candidateLatest
              : latestIngestedAt;
      return new SourceSummary(docCount + 1, chunkCount + additionalChunks, resolvedLatest);
    }
  }
}
