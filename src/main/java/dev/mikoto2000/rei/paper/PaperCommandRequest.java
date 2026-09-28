package dev.mikoto2000.rei.paper;

import java.util.*;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(name = "paper")
public class PaperCommandRequest {
  @Parameters(index = "0", arity = "1")
  String action;

  @Parameters(index = "1..*", arity = "0..*")
  List<String> args = new ArrayList<>();

  @Option(names = "--since")
  Integer since;

  @Option(names = "--until")
  Integer until;

  @Option(names = "--limit")
  Integer limit;

  @Option(names = "--oa")
  boolean oa;

  @Option(names = "--sort", defaultValue = "relevance")
  String sort;

  @Option(names = "--quick")
  boolean quick;

  @Option(names = "--detailed")
  boolean detailed;

  @Option(names = "--refresh")
  boolean refresh;

  @Option(names = "--literal")
  boolean literal;

  @Option(names = "--natural")
  boolean natural;

  @Option(names = "--technical")
  boolean technical;

  @Option(names = "--section")
  String section;

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
