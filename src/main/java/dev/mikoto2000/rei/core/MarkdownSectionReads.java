package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.tooldelivery.ReadingProblem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** Source-coordinate section selection over CommonMark blocks, sharing native read budgets. */
final class MarkdownSectionReads {
  public record Section(
      String id,
      String title,
      List<String> path,
      int level,
      int startLine,
      int headingEndLine,
      int endLine,
      int ownEndLine) {
    public Section {
      path = List.copyOf(path);
    }
  }

  public record Selection(Section selected, List<Section> candidates, boolean candidatesTruncated) {
    public Selection {
      candidates = List.copyOf(candidates);
    }
  }

  static boolean selected(Tools.ReadFileRequest request) {
    return request.sectionPath() != null
        || request.section() != null
        || request.sectionId() != null
        || request.headingLevel() != null;
  }

  static Tools.ReadFileResult read(
      Tools tools,
      Tools.ReadFileRequest request,
      Path root,
      FileSnapshots snapshots,
      TextReadBudget budget,
      int lineBudget)
      throws IOException {
    if (request.path() == null || request.path().isBlank() || request.symbol() != null)
      throw ReadingProblem.fail("INVALID_ARGUMENT");
    if (request.headingLevel() != null
        && (request.headingLevel() < 1 || request.headingLevel() > 6))
      throw ReadingProblem.fail("INVALID_ARGUMENT");
    if (request.sectionPath() != null
        && (request.sectionPath().isEmpty()
            || request.sectionPath().size() > 64
            || request.sectionPath().stream()
                .anyMatch(part -> part == null || part.length() > 2048)))
      throw ReadingProblem.fail("INVALID_ARGUMENT");
    var snapshot = snapshots.get(root, Path.of(request.path()));
    if (request.expectedVersion() != null && !request.expectedVersion().equals(snapshot.version()))
      throw ReadingProblem.fail("FILE_CHANGED");
    var lines =
        snapshots.lines(
            snapshot, request.charset(), () -> tools.snapshotLines(snapshot, request.charset()));
    var sections = index(lines);
    var found =
        sections.stream()
            .filter(
                section -> request.section() == null || section.title().equals(request.section()))
            .filter(
                section ->
                    request.sectionPath() == null || section.path().equals(request.sectionPath()))
            .filter(
                section -> request.sectionId() == null || section.id().equals(request.sectionId()))
            .filter(
                section ->
                    request.headingLevel() == null || section.level() == request.headingLevel())
            .toList();
    if (found.size() != 1) {
      var result =
          new Tools.ReadFileResult(
                  request.path(),
                  null,
                  null,
                  List.of(),
                  false,
                  found.isEmpty() ? "SECTION_NOT_FOUND" : "AMBIGUOUS_SECTION",
                  snapshot.version(),
                  null,
                  0)
              .withSelection(
                  new Selection(null, found.stream().limit(20).toList(), found.size() > 20));
      return FileResultBudget.fit(
          result,
          Math.max(256, budget.remaining()),
          2000,
          value -> {
            var candidates = value.selection().candidates();
            return candidates.isEmpty()
                ? value
                : value.withSelection(
                    new Selection(null, candidates.subList(0, candidates.size() - 1), true));
          });
    }
    var section = found.getFirst();
    int context = request.contextLines() == null ? 0 : request.contextLines();
    if (context < 0 || context > 25) throw ReadingProblem.fail("INVALID_ARGUMENT");
    int start = Math.max(1, section.startLine() - context);
    int end =
        Math.min(
            lines.size(),
            (Boolean.FALSE.equals(request.includeChildren())
                    ? section.ownEndLine()
                    : section.endLine())
                + context);
    if (request.startLine() != null) {
      if (request.startLine() < start || request.startLine() > end)
        throw ReadingProblem.fail("INVALID_CURSOR");
      start = request.startLine();
    }
    if (request.endLine() != null) {
      if (request.endLine() > end || request.endLine() < start)
        throw ReadingProblem.fail("INVALID_CURSOR");
      end = request.endLine();
    }
    var translated =
        new Tools.ReadFileRequest(
            request.path(), start, end, snapshot.version(), request.offset(), request.charset());
    var result = tools.readSingleFile(translated, root, snapshots, budget, lineBudget);
    var next =
        result.nextRead() == null
            ? null
            : continueRequest(request, result.nextRead(), section.id());
    return new Tools.ReadFileResult(
        result.path(),
        result.startLine(),
        result.endLine(),
        result.content(),
        result.truncated(),
        result.error(),
        result.version(),
        next,
        result.returnedBytes(),
        null,
        List.of(),
        List.of(),
        result.truncationReason(),
        result.delivery(),
        new Selection(section, List.of(), false));
  }

  static Tools.ReadFileRequest continueRequest(
      Tools.ReadFileRequest original, Tools.ReadFileRequest next, String id) {
    return new Tools.ReadFileRequest(
        next.path(),
        next.startLine(),
        next.endLine(),
        next.expectedVersion(),
        next.offset(),
        next.charset(),
        null,
        null,
        null,
        original.contextLines(),
        null,
        null,
        original.unreadOnly(),
        original.sectionPath(),
        original.section(),
        id,
        original.headingLevel(),
        original.includeChildren());
  }

  static List<Section> index(List<String> lines) {
    Tools.checkReadingCancellation(null);
    var parseLines = new ArrayList<>(lines);
    if (!parseLines.isEmpty() && parseLines.getFirst().strip().equals("---")) {
      int end = 1;
      while (end < parseLines.size()
          && !List.of("---", "...").contains(parseLines.get(end).strip())) end++;
      if (end < parseLines.size()) for (int i = 0; i <= end; i++) parseLines.set(i, "");
    }
    var document =
        Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .maxOpenBlockParsers(128)
            .build()
            .parse(String.join("\n", parseLines));
    var headings = new ArrayList<Heading>();
    for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
      if (child instanceof Heading heading) headings.add(heading);
      if (headings.size() > 4096) throw ReadingProblem.fail("SECTION_INDEX_LIMIT");
    }
    var result = new ArrayList<Section>();
    var ancestors = new ArrayList<Section>();
    for (int i = 0; i < headings.size(); i++) {
      var heading = headings.get(i);
      int start = heading.getSourceSpans().getFirst().getLineIndex() + 1;
      int headingEnd = heading.getSourceSpans().getLast().getLineIndex() + 1;
      int end = lines.size();
      for (int next = i + 1; next < headings.size(); next++)
        if (headings.get(next).getLevel() <= heading.getLevel()) {
          end = headings.get(next).getSourceSpans().getFirst().getLineIndex();
          break;
        }
      int ownEnd =
          i + 1 < headings.size()
              ? headings.get(i + 1).getSourceSpans().getFirst().getLineIndex()
              : lines.size();
      while (!ancestors.isEmpty() && ancestors.getLast().level() >= heading.getLevel())
        ancestors.removeLast();
      String title = text(heading);
      var path = new ArrayList<>(ancestors.stream().map(Section::title).toList());
      path.add(title);
      var section =
          new Section(
              "line:" + start,
              title,
              List.copyOf(path),
              heading.getLevel(),
              start,
              headingEnd,
              end,
              ownEnd);
      result.add(section);
      ancestors.add(section);
    }
    return List.copyOf(result);
  }

  private static String text(Node node) {
    var text = new StringBuilder();
    var pending = new java.util.ArrayDeque<Node>();
    pending.push(node);
    while (!pending.isEmpty()) {
      var current = pending.pop();
      if (current instanceof org.commonmark.node.Text value) text.append(value.getLiteral());
      else if (current instanceof org.commonmark.node.Code value) text.append(value.getLiteral());
      for (Node child = current.getLastChild(); child != null; child = child.getPrevious())
        pending.push(child);
    }
    return text.toString();
  }
}
