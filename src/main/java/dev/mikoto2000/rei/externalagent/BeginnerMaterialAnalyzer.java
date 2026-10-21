package dev.mikoto2000.rei.externalagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 外部エージェントへの委譲に必要な初学者教材分析を扱う。
 *
 * <p>Static, bounded evidence extraction. Text is data; nothing in a lesson is executed.
 */
public final class BeginnerMaterialAnalyzer {
  /** 外部エージェントへの委譲における読み取り状態の選択肢。 */
  public enum ReadState {
    /** 読み取り。 */
    READ,
    /** 部分的な結果。 */
    PARTIAL,
    /** 失敗。 */
    FAILED
  }

  /** 外部エージェントへの委譲における参照先状態の選択肢。 */
  public enum ReferenceState {
    /** 存在する。 */
    PRESENT,
    /** 未指定。 */
    MISSING,
    /** 範囲外作業ルート。 */
    OUTSIDE_ROOT,
    /** 対象外。 */
    EXCLUDED
  }

  /**
   * 外部エージェントへの委譲で受け渡す根拠を表す。
   *
   * @param value 値
   * @param line 行
   */
  public record Evidence(String value, int line) {}

  /**
   * 外部エージェントへの委譲で受け渡すファイル参照先を表す。
   *
   * @param value 値
   * @param line 行
   * @param state 状態
   */
  public record FileReference(String value, int line, ReferenceState state) {}

  /**
   * 外部エージェントへの委譲で受け渡すChapterを表す。
   *
   * @param file ファイル
   * @param state 状態
   * @param lastLine 直近行
   * @param explained 説明済みの内容
   * @param required 必須
   * @param implicitPrerequisites 暗黙の前提条件
   * @param goals 目標一覧
   * @param exercises 演習
   * @param questions 質問
   * @param expectedResults 期待する内容結果一覧
   * @param files ファイル一覧
   * @param environment 実行環境
   * @param knowledgeBefore 知識変更前
   * @param knowledgeAfter 知識変更後
   */
  public record Chapter(
      String file,
      ReadState state,
      int lastLine,
      List<Evidence> explained,
      List<Evidence> required,
      List<Evidence> implicitPrerequisites,
      List<Evidence> goals,
      List<Evidence> exercises,
      List<Evidence> questions,
      List<Evidence> expectedResults,
      List<FileReference> files,
      List<Evidence> environment,
      Set<String> knowledgeBefore,
      Set<String> knowledgeAfter) {}

  /**
   * 外部エージェントへの委譲で受け渡す分析を表す。
   *
   * @param chapters 章の構成
   * @param unreadFiles unreadファイル一覧
   * @param warnings 注意事項
   * @param complete 完了
   */
  public record Analysis(
      List<Chapter> chapters, List<String> unreadFiles, List<String> warnings, boolean complete) {}

  /** 最大ファイル一覧。 */
  private static final int MAX_FILES = 512;

  /** 最大ファイルバイト数。 */
  private static final int MAX_FILE_BYTES = 65536;

  /** 最大合計バイト数。 */
  private static final int MAX_TOTAL_BYTES = 262144;

  /** 外部エージェントへの委譲で用いる分析を返す。 */
  public Analysis analyze(
      Path directory,
      String entry,
      List<String> explicitOrder,
      Set<String> prerequisites,
      int byteBudget)
      throws IOException {
    dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
    Path root = directory.toRealPath();
    if (root.getFileName() == null || ExternalAgentSourceSnapshot.excluded(root.getFileName()))
      throw new IllegalArgumentException("Material root is excluded");
    if (!Files.isDirectory(root))
      throw new IllegalArgumentException("Material root must be a directory");
    if (byteBudget < 1) throw new IllegalArgumentException("Positive read budget required");
    Path entryPath = resolve(root, root, entry);
    var warnings = new ArrayList<String>();
    var inventory = enumerate(root, warnings);
    var order = new LinkedHashSet<Path>();
    if (!explicitOrder.isEmpty()) {
      for (String file : explicitOrder) order.add(resolve(root, root, file));
    } else {
      navigation(root, root, order, warnings, new int[] {0});
      if (order.isEmpty()) {
        order.add(entryPath);
        warnings.add(
            "No supported navigation order; only the entry page is selected. Supply an explicit order for other pages.");
      }
    }
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (order.size() > MAX_FILES) throw new IllegalArgumentException("Too many selected chapters");
    var chapters = new ArrayList<Chapter>();
    var knowledge = new LinkedHashSet<String>(prerequisites);
    int remaining = Math.min(byteBudget, MAX_TOTAL_BYTES);
    for (Path file : order) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
      var before = Set.copyOf(knowledge);
      String name = relative(root, file);
      try {
        if (ExternalAgentSourceSnapshot.excluded(root.relativize(file))
            || !markdown(file)
            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            || !file.toRealPath().equals(file)) throw new IOException();
        int limit = Math.min(remaining, MAX_FILE_BYTES);
        byte[] bytes;
        try (var stream = Files.newInputStream(file)) {
          bytes = stream.readNBytes(limit + 1);
        }
        boolean partial = bytes.length > limit;
        int length = Math.min(bytes.length, limit);
        remaining -= length;
        // Strict UTF-8. A truncated multibyte character is removed, never replaced or counted as
        // read.
        while (partial && length > 0 && (bytes[length] & 0xc0) == 0x80) length--;
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes, 0, length))
                .toString();
        if (partial) {
          int newline = text.lastIndexOf('\n');
          text = newline < 0 ? "" : text.substring(0, newline + 1);
        }
        var chapter =
            extract(root, file, text, partial ? ReadState.PARTIAL : ReadState.READ, before);
        if (!partial) chapter.explained().forEach(e -> knowledge.add(e.value()));
        chapters.add(
            new Chapter(
                name,
                chapter.state(),
                chapter.lastLine(),
                chapter.explained(),
                chapter.required(),
                chapter.implicitPrerequisites(),
                chapter.goals(),
                chapter.exercises(),
                chapter.questions(),
                chapter.expectedResults(),
                chapter.files(),
                chapter.environment(),
                before,
                Set.copyOf(knowledge)));
        if (partial) warnings.add("Partial chapter: " + name);
      } catch (IOException error) {
        chapters.add(extract(root, file, "", ReadState.FAILED, before));
        warnings.add("Unreadable or excluded chapter: " + name);
      }
    }
    var unread =
        inventory.stream().filter(p -> !order.contains(p)).map(p -> relative(root, p)).toList();
    return new Analysis(
        List.copyOf(chapters),
        unread,
        List.copyOf(warnings),
        unread.isEmpty()
            && warnings.isEmpty()
            && chapters.stream().allMatch(c -> c.state() == ReadState.READ));
  }

  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
  private static Chapter extract(
      Path root, Path file, String text, ReadState state, Set<String> before) {
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
        if (fence == 0) {
          fence = marker;
          fenceLength = size;
        } else if (marker == fence && size >= fenceLength) fence = 0;
        continue;
      }
      if (fence != 0) continue;
      if (line.startsWith("#")) {
        section = category(line.replaceFirst("^#+\\s*", "").strip());
        continue;
      }
      String value = line.replaceFirst("^(?:[-*+] |\\d+[.)] )", "").strip();
      if (value.isEmpty() || section.isEmpty()) continue;
      if (section.equals("explained")) {
        int delimiter = value.indexOf(':');
        if (delimiter < 0) delimiter = value.indexOf('：');
        // A name alone is not an explanation, even in a Concepts section.
        if (delimiter <= 0 || value.substring(delimiter + 1).isBlank()) continue;
        value = value.substring(0, delimiter).strip().replace("`", "");
      }
      Evidence evidence =
          new Evidence(dev.mikoto2000.rei.event.CredentialRedactor.redact(value), i + 1);
      if (section.equals("files")) files.add(reference(root, file.getParent(), evidence));
      else groups.computeIfAbsent(section, ignored -> new ArrayList<>()).add(evidence);
    }
    return new Chapter(
        relative(root, file),
        state,
        text.isEmpty() ? 0 : lines.length - (text.endsWith("\n") ? 1 : 0),
        group(groups, "explained"),
        group(groups, "required"),
        group(groups, "implicit"),
        group(groups, "goals"),
        group(groups, "exercises"),
        group(groups, "questions"),
        group(groups, "expected"),
        List.copyOf(files),
        group(groups, "environment"),
        before,
        before);
  }

  /** 外部エージェントへの委譲で用いるグループを返す。 */
  private static List<Evidence> group(Map<String, List<Evidence>> groups, String key) {
    return List.copyOf(groups.getOrDefault(key, List.of()));
  }

  /** 外部エージェントへの委譲で用いる分類を返す。 */
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

  /** 外部エージェントへの委譲で用いる参照先を返す。 */
  private static FileReference reference(Path root, Path parent, Evidence evidence) {
    String name = evidence.value().replace("`", "");
    try {
      Path file = resolve(root, parent, name);
      ReferenceState state =
          ExternalAgentSourceSnapshot.excluded(root.relativize(file))
              ? ReferenceState.EXCLUDED
              : Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                  ? ReferenceState.PRESENT
                  : ReferenceState.MISSING;
      return new FileReference(evidence.value(), evidence.line(), state);
    } catch (IllegalArgumentException error) {
      return new FileReference(evidence.value(), evidence.line(), ReferenceState.OUTSIDE_ROOT);
    }
  }

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  private static Path resolve(Path root, Path parent, String name) {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("Chapter path required");
    try {
      Path path = parent.resolve(name).toAbsolutePath().normalize();
      if (!path.startsWith(root)) throw new IllegalArgumentException("Path outside material root");
      Path ancestor = path;
      while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
      if (!ancestor.toRealPath().equals(ancestor))
        throw new IllegalArgumentException("Linked material path");
      return path;
    } catch (IOException | InvalidPathException error) {
      throw new IllegalArgumentException("Invalid material path");
    }
  }

  /** 外部エージェントへの委譲において、Markdownに該当するか判定する。 */
  private static boolean markdown(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".md") || name.endsWith(".mdx");
  }

  /** 外部エージェントへの委譲で用いる相対を返す。 */
  private static String relative(Path root, Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  /** 外部エージェントへの委譲で用いるenumerateを返す。 */
  private static List<Path> enumerate(Path root, List<String> warnings) throws IOException {
    var pages = new ArrayList<Path>();
    int[] visited = {0};
    Files.walkFileTree(
        root,
        EnumSet.noneOf(FileVisitOption.class),
        16,
        new SimpleFileVisitor<>() {
          /** 外部エージェントへの委譲で用いるpre対象の確認ディレクトリを返す。 */
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
              throws IOException {
            dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
            if (++visited[0] > 4096) {
              warnings.add("Inventory limit reached; coverage incomplete");
              return FileVisitResult.TERMINATE;
            }
            return ExternalAgentSourceSnapshot.excluded(root.relativize(dir))
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          /** 外部エージェントへの委譲で用いる対象の確認ファイルを返す。 */
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
            if (++visited[0] > 4096 || pages.size() >= MAX_FILES) {
              warnings.add("Inventory limit reached; coverage incomplete");
              return FileVisitResult.TERMINATE;
            }
            if (attributes.isDirectory())
              warnings.add("Inventory depth limit reached: " + relative(root, file));
            if (attributes.isRegularFile()
                && markdown(file)
                && !ExternalAgentSourceSnapshot.excluded(root.relativize(file))) pages.add(file);
            return FileVisitResult.CONTINUE;
          }

          /** 外部エージェントへの委譲で用いる対象の確認ファイル失敗を返す。 */
          @Override
          public FileVisitResult visitFileFailed(Path file, IOException error) {
            warnings.add("Inventory entry unavailable: " + relative(root, file));
            return FileVisitResult.CONTINUE;
          }
        });
    pages.sort(Comparator.comparing(p -> relative(root, p)));
    return List.copyOf(pages);
  }

  /** 外部エージェントへの委譲に関するnavigationを処理する。 */
  private static void navigation(
      Path root, Path directory, Set<Path> order, List<String> warnings, int[] count) {
    dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
    if (++count[0] > MAX_FILES) {
      warnings.add("Navigation limit reached");
      return;
    }
    Path meta = directory.resolve("_meta.json");
    if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS)) return;
    try {
      resolve(root, root, relative(root, meta));
      JsonNode json = new ObjectMapper().readTree(ExternalAgentSourceSnapshot.read(meta));
      if (!json.isArray()) {
        warnings.add("Unsupported navigation structure: " + relative(root, meta));
        return;
      }
      for (JsonNode item : json) {
        String name = item.isTextual() ? item.asText() : item.path("name").asText();
        if (name.isBlank() || name.startsWith("http:") || name.startsWith("https:")) continue;
        Path path = resolve(root, directory, name);
        if (ExternalAgentSourceSnapshot.excluded(root.relativize(path))) continue;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          navigation(root, path, order, warnings, count);
        else if (markdown(path)) order.add(path);
        else if (Files.isRegularFile(Path.of(path + ".md"))) order.add(Path.of(path + ".md"));
        else if (Files.isRegularFile(Path.of(path + ".mdx"))) order.add(Path.of(path + ".mdx"));
        else warnings.add("Unresolved navigation page: " + relative(root, path));
      }
    } catch (IOException | IllegalArgumentException error) {
      warnings.add("Navigation unavailable: " + relative(root, meta));
    }
  }
}
