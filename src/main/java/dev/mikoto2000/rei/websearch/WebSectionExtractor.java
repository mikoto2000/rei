package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.http.FetchScope;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import java.util.*;
import org.jsoup.nodes.*;

/** Small in-memory BM25 over bounded section windows, plus headings and literal version/API evidence. */
final class WebSectionExtractor {
  record Selection(String content, List<WebExcerpt> excerpts, boolean truncated, List<String> omissions) { }
  private record Block(String text, String kind, List<String> headings, int position, int start, int end, String anchor, boolean partial) { }
  private record Ranked(Block block, double score) { }
  private static final int MAX_SECTIONS = 512, WINDOW = 800;
  static Selection select(Document document, String query, int maxCharacters, int maxTokens) {
    var terms = WebQueryTerms.of(query); var blocks = new ArrayList<Block>(); var path = new TreeMap<Integer, String>();
    boolean limited = false; int position = 0;
    for (Element element : document.body().getAllElements()) {
      FetchScope.current().check(); String tag = element.normalName(), text = null, kind = "paragraph";
      if (tag.matches("h[1-6]")) {
        int level = tag.charAt(1) - '0'; path.tailMap(level).clear(); path.put(level, clip(normalize(element.text()), 160));
        text = normalize(element.text()); kind = "heading";
      } else if (Set.of("p", "li", "pre", "table", "code").contains(tag)
          && element.parents().stream().noneMatch(parent -> Set.of("pre", "table").contains(parent.normalName()))) {
        if (tag.equals("pre") || tag.equals("code")) { text = element.wholeText().replace("\r\n", "\n"); kind = "code"; }
        else if (tag.equals("table")) {
          text = element.select("tr").stream().map(row -> row.select("th,td").stream().map(cell -> normalize(cell.text()))
              .collect(java.util.stream.Collectors.joining(" | "))).collect(java.util.stream.Collectors.joining("\n")); kind = "table";
        } else if (tag.equals("li")) {
          var own = element.clone(); own.select("ul,ol").remove();
          text = "  ".repeat((int) Math.min(16, element.parents().stream().filter(parent -> parent.normalName().equals("li")).count()))
              + "- " + normalize(own.text()); kind = "list";
        } else text = normalize(element.text());
      } else if (Set.of("body", "div", "article", "section").contains(tag)) text = normalize(element.ownText());
      if (text == null || text.isBlank()) continue;
      int start = element.sourceRange().isTracked() ? element.sourceRange().startPos() : -1;
      int end = element.endSourceRange().isTracked() ? element.endSourceRange().endPos()
          : element.sourceRange().isTracked() ? element.sourceRange().endPos() : -1;
      String anchor = element.id().isBlank() ? element.parents().stream().map(Element::id).filter(id -> !id.isBlank()).findFirst().orElse(null) : element.id();
      List<String> headings = List.copyOf(path.values());
      var offsets = new TreeSet<Integer>(); offsets.add(0);
      String lower = text.toLowerCase(Locale.ROOT);
      for (String term : terms) {
        int match = lower.indexOf(term);
        if (match >= 0) offsets.add(safeStart(text, Math.max(0, match - 200)));
      }
      for (int offset = WINDOW; offset < text.length() && offsets.size() < 64; offset += WINDOW) offsets.add(safeStart(text, offset));
      int window = 0;
      for (int offset : offsets) {
        if (blocks.size() >= MAX_SECTIONS) { limited = true; break; }
        int finish = safeEnd(text, Math.min(text.length(), offset + WINDOW));
        if (kind.equals("code") && offset > 0) { int newline = text.lastIndexOf('\n', offset); if (newline >= 0 && offset - newline < 200) offset = newline + 1; }
        blocks.add(new Block(text.substring(offset, finish), kind, headings, position * 100 + window++, start, end, anchor, offset > 0 || finish < text.length()));
      }
      position++;
      if (blocks.size() >= MAX_SECTIONS) { limited = true; break; }
    }
    if (blocks.isEmpty()) {
      String fallback = normalize(document.body().text());
      blocks.add(new Block(clip(fallback, WINDOW), "paragraph", List.of(), 0, -1, -1, null, fallback.length() > WINDOW));
    }
    var lengths = new IdentityHashMap<Block,Integer>();
    for(var block:blocks)lengths.put(block,words(block.text()));
    double average = lengths.values().stream().mapToInt(Integer::intValue).average().orElse(1);
    int[] frequencies = new int[terms.size()];
    for (int i = 0; i < terms.size(); i++) for (var block : blocks) if (WebQueryTerms.frequency(block.text(), terms.get(i)) > 0) frequencies[i]++;
    var ranked = new ArrayList<Ranked>();
    for (var block : blocks) {
      FetchScope.current().check(); double score = 0;
      for (int i = 0; i < terms.size(); i++) {
        String term = terms.get(i); int frequency = WebQueryTerms.frequency(block.text(), term);
        if (frequency > 0) score += Math.log(1 + (blocks.size() - frequencies[i] + .5) / (frequencies[i] + .5))
            * frequency * 2.2 / (frequency + 1.2 * (.25 + .75 * lengths.get(block) / average));
        if (WebQueryTerms.frequency(String.join(" ", block.headings()), term) > 0) score += 2;
        if (term.matches("\\d+\\.\\d+.*") && frequency > 0) score += 3;
      }
      if (block.kind().equals("code") && score > 0) score += 1;
      ranked.add(new Ranked(block, score));
    }
    ranked.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparingInt(value -> value.block().position()));
    boolean relevant = ranked.stream().anyMatch(value -> value.score() > 0); var selected = new ArrayList<Block>();
    int chars = 0, tokens = 0;
    for (var value : ranked) {
      if (relevant && value.score() == 0) continue;
      var block = value.block(); String rendered = render(block); int separator = selected.isEmpty() ? 0 : 2;
      int remaining = maxCharacters - chars - separator;
      if (remaining <= 0 || tokens >= maxTokens) break;
      rendered = clip(rendered, remaining);
      while (!rendered.isEmpty() && TokenEstimator.conservative().text(rendered) > maxTokens - tokens) rendered = clip(rendered, rendered.length() * 3 / 4);
      if (rendered.isEmpty()) continue;
      if (!rendered.equals(render(block))) block = new Block(rendered, "excerpt", block.headings(), block.position(), block.start(), block.end(), block.anchor(), true);
      selected.add(block); chars += rendered.length() + separator; tokens += TokenEstimator.conservative().text(rendered) + (separator == 0 ? 0 : 1);
    }
    selected.sort(Comparator.comparingInt(Block::position)); var content = new StringBuilder(); var excerpts = new ArrayList<WebExcerpt>();
    for (var block : selected) {
      if (!content.isEmpty()) content.append("\n\n"); int start = content.length(); content.append(render(block));
      excerpts.add(new WebExcerpt(block.kind(), block.headings(), block.position(), block.start(), block.end(), block.anchor(), start, content.length(), block.partial()));
    }
    boolean truncated = limited || selected.size() < blocks.size() || selected.stream().anyMatch(Block::partial);
    return new Selection(content.toString(), List.copyOf(excerpts), truncated, limited ? List.of("section_limit") : List.of());
  }
  private static int words(String text) { return Math.max(1, text.split("[^\\p{IsAlphabetic}\\p{IsDigit}]+").length); }
  private static String render(Block block) {
    if (block.kind().equals("code")) return (block.headings().isEmpty() ? "" : "### " + String.join(" / ", block.headings()) + "\n") + "```\n" + block.text() + "\n```";
    return block.text();
  }
  static String normalize(String text) { return Objects.toString(text, "").replaceAll("\\s+", " ").strip(); }
  static String clip(String value, int length) { return value.substring(0, safeEnd(value, Math.min(value.length(), Math.max(0, length)))); }
  private static int safeStart(String value, int offset) { return offset > 0 && offset < value.length() && Character.isLowSurrogate(value.charAt(offset)) ? offset - 1 : offset; }
  private static int safeEnd(String value, int offset) { return offset > 0 && offset < value.length() && Character.isLowSurrogate(value.charAt(offset)) ? offset - 1 : offset; }
}
