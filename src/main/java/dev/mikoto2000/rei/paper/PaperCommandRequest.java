package dev.mikoto2000.rei.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** 論文の調査と整理に必要な論文コマンド要求を扱う。 */
@Command(name = "paper")
public class PaperCommandRequest {
  /** 操作。 */
  @Parameters(index = "0", arity = "1")
  String action;

  /** 引数。 */
  @Parameters(index = "1..*", arity = "0..*")
  List<String> args = new ArrayList<>();

  /** 開始日時。 */
  @Option(names = "--since")
  Integer since;

  /** 終了日時。 */
  @Option(names = "--until")
  Integer until;

  /** 上限。 */
  @Option(names = "--limit")
  Integer limit;

  /** 公開アクセス可能な資料を優先する指定。 */
  @Option(names = "--oa")
  boolean oa;

  /** 並び順。 */
  @Option(names = "--sort", defaultValue = "relevance")
  String sort;

  /** 簡潔な要約の指定。 */
  @Option(names = "--quick")
  boolean quick;

  /** 詳細な要約の指定。 */
  @Option(names = "--detailed")
  boolean detailed;

  /** 再確認。 */
  @Option(names = "--refresh")
  boolean refresh;

  /** そのままの値。 */
  @Option(names = "--literal")
  boolean literal;

  /** 自然な文章での翻訳指定。 */
  @Option(names = "--natural")
  boolean natural;

  /** 技術用語を重視する翻訳指定。 */
  @Option(names = "--technical")
  boolean technical;

  /** 節。 */
  @Option(names = "--section")
  String section;

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public static PaperCommandRequest parse(String[] args) {
    var request = new PaperCommandRequest();
    try {
      new CommandLine(request).parseArgs(args);
      request.validate();
      return request;
    } catch (IllegalArgumentException | CommandLine.ParameterException e) {
      throw new PaperException(
          PaperException.Code.INVALID_QUERY, "/paper の引数が不正です: " + e.getMessage());
    }
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  private void validate() {
    if (!Set.of("search", "show", "summarize", "translate", "library", "import").contains(action))
      throw new IllegalArgumentException("unknown action");
    if (action.equals("search")) query(10);
    if (Set.of("show", "summarize", "translate").contains(action) && args.size() != 1)
      throw new IllegalArgumentException("paper-ref required");
    if (action.equals("import") && args.size() != 2)
      throw new IllegalArgumentException("import <paper-ref> <PDF path>");
    if (quick && detailed || (literal ? 1 : 0) + (natural ? 1 : 0) + (technical ? 1 : 0) > 1)
      throw new IllegalArgumentException("conflicting modes");
    if (action.equals("library") && !args.isEmpty()) {
      if (!Set.of("search", "show", "remove", "purge").contains(args.getFirst()) || args.size() < 2)
        throw new IllegalArgumentException("library search/show/remove/purge requires argument");
      if (!args.getFirst().equals("search") && args.size() != 2)
        throw new IllegalArgumentException("one paper-id required");
    }
    if (limit != null && (limit < 1 || limit > 100))
      throw new IllegalArgumentException("limit 1..100");
  }

  /** 論文の調査と整理で用いる検索条件を返す。 */
  PaperSearchQuery query(int defaultLimit) {
    return new PaperSearchQuery(
        String.join(" ", args),
        since,
        until,
        List.of(),
        List.of(),
        oa,
        switch (sort) {
          case "relevance" -> PaperSearchQuery.Sort.RELEVANCE;
          case "newest" -> PaperSearchQuery.Sort.NEWEST;
          case "citations" -> PaperSearchQuery.Sort.CITATION_COUNT;
          default -> throw new IllegalArgumentException("sort");
        },
        limit == null ? defaultLimit : limit);
  }
}
