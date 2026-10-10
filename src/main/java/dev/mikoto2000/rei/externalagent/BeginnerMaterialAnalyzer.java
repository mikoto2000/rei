package dev.mikoto2000.rei.externalagent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import com.fasterxml.jackson.databind.*;

/** Static, bounded evidence extraction. Text is data; nothing in a lesson is executed. */
public final class BeginnerMaterialAnalyzer {
  public enum ReadState { READ, PARTIAL, FAILED }
  public enum ReferenceState { PRESENT, MISSING, OUTSIDE_ROOT, EXCLUDED }
  public record Evidence(String value, int line) {}
  public record FileReference(String value, int line, ReferenceState state) {}
  public record Chapter(String file, ReadState state, int lastLine, List<Evidence> explained,
      List<Evidence> required, List<Evidence> implicitPrerequisites, List<Evidence> goals,
      List<Evidence> exercises, List<Evidence> questions, List<Evidence> expectedResults,
      List<FileReference> files, List<Evidence> environment, Set<String> knowledgeBefore,
      Set<String> knowledgeAfter) {}
  public record Analysis(List<Chapter> chapters, List<String> unreadFiles, List<String> warnings,
      boolean complete) {}
  private static final int MAX_FILES = 512;
  private static final int MAX_FILE_BYTES = 65536;
  private static final int MAX_TOTAL_BYTES = 262144;

  public Analysis analyze(Path directory, String entry, List<String> explicitOrder,
      Set<String> prerequisites, int byteBudget) throws IOException {
    dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
    Path root = directory.toRealPath();
    if (!Files.isDirectory(root)) throw new IllegalArgumentException("Material root must be a directory");
    if (byteBudget < 1) throw new IllegalArgumentException("Positive read budget required");
    Path entryPath = resolve(root, root, entry);
    var warnings = new ArrayList<String>();
    var inventory = enumerate(root, warnings);
    var order = new LinkedHashSet<Path>();
    if (!explicitOrder.isEmpty()) {
      for (String file : explicitOrder) order.add(resolve(root, root, file));
    } else {
      navigation(root, root, order, warnings, new int[]{0});
      if (order.isEmpty()) {
        order.add(entryPath);
        warnings.add("No supported navigation order; only the entry page is selected. Supply an explicit order for other pages.");
      }
    }
    if (order.size() > MAX_FILES) throw new IllegalArgumentException("Too many selected chapters");
    var chapters = new ArrayList<Chapter>();
    var knowledge = new LinkedHashSet<String>(prerequisites);
    int remaining = Math.min(byteBudget, MAX_TOTAL_BYTES);
    for (Path file : order) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
      var before = Set.copyOf(knowledge);
      String name = relative(root, file);
      try {
        if (ExternalAgentSourceSnapshot.excluded(root.relativize(file)) || !markdown(file)
            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file))
          throw new IOException();
        int limit = Math.min(remaining, MAX_FILE_BYTES);
        byte[] bytes;
        try (var stream = Files.newInputStream(file)) { bytes = stream.readNBytes(limit + 1); }
        boolean partial = bytes.length > limit;
        int length = Math.min(bytes.length, limit);
        remaining -= length;
        // Strict UTF-8. A truncated multibyte character is removed, never replaced or counted as read.
        while (partial && length > 0 && (bytes[length] & 0xc0) == 0x80) length--;
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, 0, length)).toString();
        if (partial) { int newline = text.lastIndexOf('\n'); text = newline < 0 ? "" : text.substring(0, newline + 1); }
        var chapter = extract(root, file, text, partial ? ReadState.PARTIAL : ReadState.READ, before);
        if (!partial) chapter.explained().forEach(e -> knowledge.add(e.value()));
        chapters.add(new Chapter(name, chapter.state(), chapter.lastLine(), chapter.explained(), chapter.required(),
            chapter.implicitPrerequisites(), chapter.goals(), chapter.exercises(), chapter.questions(), chapter.expectedResults(),
            chapter.files(), chapter.environment(), before, Set.copyOf(knowledge)));
        if (partial) warnings.add("Partial chapter: " + name);
      } catch (IOException error) {
        chapters.add(extract(root, file, "", ReadState.FAILED, before));
        warnings.add("Unreadable or excluded chapter: " + name);
      }
    }
    var unread = inventory.stream().filter(p -> !order.contains(p)).map(p -> relative(root, p)).toList();
    return new Analysis(List.copyOf(chapters), unread, List.copyOf(warnings),
        unread.isEmpty() && warnings.isEmpty() && chapters.stream().allMatch(c -> c.state() == ReadState.READ));
  }

  private static Chapter extract(Path root, Path file, String text, ReadState state, Set<String> before) {
    var groups = new HashMap<String, List<Evidence>>();
    var files = new ArrayList<FileReference>();
    String section = "";
    char fence = 0;
    int fenceLength = 0;
    String[] lines = text.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      var fenceMatch = java.util.regex.Pattern.compile("^(`{3,}|~{3,}).*$").matcher(line);
      if (fenceMatch.matches()) {
        char marker = line.charAt(0);
        int size = fenceMatch.group(1).length();
        if (fence == 0) { fence = marker; fenceLength = size; }
        else if (marker == fence && size >= fenceLength) fence = 0;
        continue;
      }
      if (fence != 0) continue;
      if (line.startsWith("#")) { section = category(line.replaceFirst("^#+\\s*", "").strip()); continue; }
      String value = line.replaceFirst("^(?:[-*+] |\\d+[.)] )", "").strip();
      if (value.isEmpty() || section.isEmpty()) continue;
      if (section.equals("explained")) {
        int delimiter = value.indexOf(':');
        if (delimiter < 0) delimiter = value.indexOf('：');
        // A name alone is not an explanation, even in a Concepts section.
        if (delimiter <= 0 || value.substring(delimiter + 1).isBlank()) continue;
        value = value.substring(0, delimiter).strip().replace("`", "");
      }
      Evidence evidence = new Evidence(dev.mikoto2000.rei.event.CredentialRedactor.redact(value), i + 1);
      if (section.equals("files")) files.add(reference(root, file.getParent(), evidence));
      else groups.computeIfAbsent(section, ignored -> new ArrayList<>()).add(evidence);
    }
    return new Chapter(relative(root, file), state, text.isEmpty() ? 0 : lines.length - (text.endsWith("\n") ? 1 : 0),
        group(groups, "explained"), group(groups, "required"), group(groups, "implicit"), group(groups, "goals"),
        group(groups, "exercises"), group(groups, "questions"), group(groups, "expected"), List.copyOf(files),
        group(groups, "environment"), before, before);
  }
  private static List<Evidence> group(Map<String,List<Evidence>> groups, String key) {
    return List.copyOf(groups.getOrDefault(key, List.of()));
  }
  private static String category(String heading) {
    return switch (heading.toLowerCase(Locale.ROOT)) {
      case "concepts", "概念", "用語解説" -> "explained";
      case "prerequisites", "前提知識" -> "required";
      case "implicit prerequisites", "暗黙の前提知識" -> "implicit";
      case "learning goals", "学習目標" -> "goals";
      case "exercises", "演習" -> "exercises";
      case "questions", "確認問題" -> "questions";
      case "expected results", "期待結果" -> "expected";
      case "required files", "必要なファイル" -> "files";
      case "environment", "必要な環境状態" -> "environment";
      default -> "";
    };
  }
  private static FileReference reference(Path root, Path parent, Evidence evidence) {
    String name = evidence.value().replace("`", "");
    try {
      Path file = resolve(root, parent, name);
      ReferenceState state = ExternalAgentSourceSnapshot.excluded(root.relativize(file)) ? ReferenceState.EXCLUDED
          : Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? ReferenceState.PRESENT : ReferenceState.MISSING;
      return new FileReference(evidence.value(), evidence.line(), state);
    } catch (IllegalArgumentException error) {
      return new FileReference(evidence.value(), evidence.line(), ReferenceState.OUTSIDE_ROOT);
    }
  }
  private static Path resolve(Path root, Path parent, String name) {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("Chapter path required");
    try {
      Path path = parent.resolve(name).toAbsolutePath().normalize();
      if (!path.startsWith(root)) throw new IllegalArgumentException("Path outside material root");
      Path ancestor = path;
      while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
      if (!ancestor.toRealPath().equals(ancestor)) throw new IllegalArgumentException("Linked material path");
      return path;
    } catch (IOException | InvalidPathException error) { throw new IllegalArgumentException("Invalid material path"); }
  }
  private static boolean markdown(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".md") || name.endsWith(".mdx");
  }
  private static String relative(Path root, Path file) { return root.relativize(file).toString().replace('\\', '/'); }
  private static List<Path> enumerate(Path root, List<String> warnings) throws IOException {
    var pages = new ArrayList<Path>();
    int[] visited = {0};
    Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 16, new SimpleFileVisitor<>() {
      @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
        dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
        if (++visited[0] > 4096) { warnings.add("Inventory limit reached; coverage incomplete"); return FileVisitResult.TERMINATE; }
        return ExternalAgentSourceSnapshot.excluded(root.relativize(dir)) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
      }
      @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
        dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
        if (++visited[0] > 4096 || pages.size() >= MAX_FILES) { warnings.add("Inventory limit reached; coverage incomplete"); return FileVisitResult.TERMINATE; }
        if (attributes.isDirectory()) warnings.add("Inventory depth limit reached: " + relative(root, file));
        if (attributes.isRegularFile() && markdown(file) && !ExternalAgentSourceSnapshot.excluded(root.relativize(file))) pages.add(file);
        return FileVisitResult.CONTINUE;
      }
      @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
        warnings.add("Inventory entry unavailable: " + relative(root, file)); return FileVisitResult.CONTINUE;
      }
    });
    pages.sort(Comparator.comparing(p -> relative(root, p)));
    return List.copyOf(pages);
  }
  private static void navigation(Path root, Path directory, Set<Path> order, List<String> warnings, int[] count) {
    dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
    if (++count[0] > MAX_FILES) { warnings.add("Navigation limit reached"); return; }
    Path meta = directory.resolve("_meta.json");
    if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS)) return;
    try {
      resolve(root, root, relative(root, meta));
      JsonNode json = new ObjectMapper().readTree(ExternalAgentSourceSnapshot.read(meta));
      if (!json.isArray()) { warnings.add("Unsupported navigation structure: " + relative(root, meta)); return; }
      for (JsonNode item : json) {
        String name = item.isTextual() ? item.asText() : item.path("name").asText();
        if (name.isBlank() || name.startsWith("http:") || name.startsWith("https:")) continue;
        Path path = resolve(root, directory, name);
        if (ExternalAgentSourceSnapshot.excluded(root.relativize(path))) continue;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) navigation(root, path, order, warnings, count);
        else if (markdown(path)) order.add(path);
        else if (Files.isRegularFile(Path.of(path + ".md"))) order.add(Path.of(path + ".md"));
        else if (Files.isRegularFile(Path.of(path + ".mdx"))) order.add(Path.of(path + ".mdx"));
        else warnings.add("Unresolved navigation page: " + relative(root, path));
      }
    } catch (IOException | IllegalArgumentException error) { warnings.add("Navigation unavailable: " + relative(root, meta)); }
  }
}
