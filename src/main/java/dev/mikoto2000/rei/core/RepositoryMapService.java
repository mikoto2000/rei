package dev.mikoto2000.rei.core;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.springframework.stereotype.Component;

/**
 * 保存と検索の窓口対応表のユースケースを実現する。
 *
 * <p>Bounded Java AST and explicit multilingual heuristic index; never executes source or build
 * configuration.
 */
@Component
public final class RepositoryMapService {
  /** 共通の実行基盤に必要な保有情報を扱う。 */
  @FunctionalInterface
  public interface Inventory {
    /** 共通の実行基盤で用いるファイル一覧を返す。 */
    List<String> files(Path root) throws IOException;
  }

  /**
   * 共通の実行基盤で受け渡すシンボルを表す。
   *
   * @param name 名前
   * @param kind 種類
   * @param line 行
   * @param entryPoint 項目Point
   * @param symbolId シンボル識別子
   * @param signature 署名
   * @param endLine 終了行
   * @param startOffset 開始開始位置
   * @param endOffset 終了開始位置
   * @param owner 所有者
   * @param signatureEndOffset 署名終了開始位置
   * @param javadocStartOffset APIの説明開始開始位置
   */
  public record Symbol(
      String name,
      String kind,
      long line,
      boolean entryPoint,
      String symbolId,
      String signature,
      long endLine,
      int startOffset,
      int endOffset,
      String owner,
      int signatureEndOffset,
      int javadocStartOffset) {
    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Symbol(String name, String kind, long line, boolean entryPoint) {
      this(name, kind, line, entryPoint, name, "", line, -1, -1, "", -1, -1);
    }
  }

  /** 共通の実行基盤において、種別種類に該当するか判定する。 */
  static boolean typeKind(String kind) {
    return Set.of("CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION_TYPE").contains(kind);
  }

  /**
   * 共通の実行基盤で受け渡すファイルを表す。
   *
   * @param path パス
   * @param module 機能単位
   * @param packageName パッケージ名前
   * @param sha256 内容の同一性を照合するSHA-256ハッシュ
   * @param status 状態
   * @param symbols シンボル一覧
   * @param imports 読み込む宣言
   * @param language 言語
   * @param analysisMode 分析動作モード
   */
  public record File(
      String path,
      String module,
      String packageName,
      String sha256,
      String status,
      List<Symbol> symbols,
      List<String> imports,
      String language,
      String analysisMode) {
    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public File(
        String path,
        String module,
        String packageName,
        String sha256,
        String status,
        List<Symbol> symbols,
        List<String> imports) {
      this(
          path,
          module,
          packageName,
          sha256,
          status,
          symbols,
          imports,
          HeuristicSourceIndex.language(path),
          path.endsWith(".java")
              ? "JAVA_AST"
              : HeuristicSourceIndex.source(path) ? "HEURISTIC" : "INVENTORY_ONLY");
    }
  }

  /**
   * 共通の実行基盤で受け渡す関係を表す。
   *
   * @param source 取得元
   * @param target 対象
   * @param kind 種類
   */
  public record Relation(String source, String target, String kind) {}

  /**
   * 共通の実行基盤で受け渡す機能単位要約を表す。
   *
   * @param name 名前
   * @param files ファイル一覧
   * @param parsedJavaFiles 解釈済みJavaファイル一覧
   * @param testFiles 検証ファイル一覧
   */
  public record ModuleSummary(String name, int files, int parsedJavaFiles, int testFiles) {}

  /**
   * 共通の実行基盤で受け渡すパッケージ要約を表す。
   *
   * @param name 名前
   * @param files ファイル一覧
   */
  public record PackageSummary(String name, int files) {}

  /**
   * 共通の実行基盤で受け渡す項目Pointを表す。
   *
   * @param path パス
   * @param symbol シンボル
   * @param line 行
   */
  public record EntryPoint(String path, String symbol, long line) {}

  /**
   * 共通の実行基盤で受け渡す要約を表す。
   *
   * @param files ファイル一覧
   * @param parsedJavaFiles 解釈済みJavaファイル一覧
   * @param unparsedJavaFiles unparsedJavaファイル一覧
   * @param inventoryOnlyFiles 保有情報限定ファイル一覧
   * @param modules 機能単位一覧
   * @param packages パッケージ一覧
   * @param entryPoints 項目観点
   * @param importRelations 取り込み関連一覧
   * @param testNameCandidates 検証名前候補一覧
   * @param partial 部分的な結果
   * @param heuristicFiles 推定によるファイル一覧
   */
  public record Summary(
      int files,
      int parsedJavaFiles,
      int unparsedJavaFiles,
      int inventoryOnlyFiles,
      List<ModuleSummary> modules,
      List<PackageSummary> packages,
      List<EntryPoint> entryPoints,
      int importRelations,
      int testNameCandidates,
      boolean partial,
      int heuristicFiles) {
    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Summary(
        int files,
        int parsedJavaFiles,
        int unparsedJavaFiles,
        int inventoryOnlyFiles,
        List<ModuleSummary> modules,
        List<PackageSummary> packages,
        List<EntryPoint> entryPoints,
        int importRelations,
        int testNameCandidates,
        boolean partial) {
      this(
          files,
          parsedJavaFiles,
          unparsedJavaFiles,
          inventoryOnlyFiles,
          modules,
          packages,
          entryPoints,
          importRelations,
          testNameCandidates,
          partial,
          0);
    }
  }

  /**
   * 共通の実行基盤で受け渡す表示内容を表す。
   *
   * @param root 作業ルート
   * @param scannedAt scanned日時
   * @param version 形式のバージョン
   * @param filesScanned ファイル一覧Scanned
   * @param partial 部分的な結果
   * @param warnings 注意事項
   * @param items 項目一覧
   * @param relations 関連一覧
   * @param summary 要約
   */
  public record View(
      String root,
      Instant scannedAt,
      String version,
      int filesScanned,
      boolean partial,
      List<String> warnings,
      List<File> items,
      List<Relation> relations,
      Summary summary) {}

  /**
   * 共通の実行基盤で受け渡す解釈済みを表す。
   *
   * @param packageName パッケージ名前
   * @param status 状態
   * @param symbols シンボル一覧
   * @param imports 読み込む宣言
   */
  private record Parsed(
      String packageName, String status, List<Symbol> symbols, List<String> imports) {}

  /**
   * 共通の実行基盤で受け渡す保存済みを表す。
   *
   * @param digest 内容の照合値
   * @param parsed 解釈済み
   */
  private record Cached(String digest, Parsed parsed) {}

  /** 保有情報。 */
  private final Inventory inventory;

  /** コンパイラー。 */
  private final JavaCompiler compiler;

  /** 保存される位置。 */
  private SqliteRepositoryMapIndex persistentIndex;

  /** キャッシュ一覧。 */
  private final LinkedHashMap<Path, Map<String, Cached>> caches =
      new LinkedHashMap<>(4, .75f, true);

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public RepositoryMapService() {
    this(RepositoryMapService::gitFiles);
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  RepositoryMapService(Inventory inventory) {
    this(inventory, ToolProvider.getSystemJavaCompiler());
  }

  /** 指定された値や利用先を使って、RepositoryMapServiceを初期化する。 */
  RepositoryMapService(Inventory inventory, JavaCompiler compiler) {
    this.inventory = inventory;
    this.compiler = compiler;
  }

  /** 共通の実行基盤に関する設定保存される位置を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configurePersistentIndex(
      javax.sql.DataSource data,
      @org.springframework.beans.factory.annotation.Value(
              "${rei.repository-map.persistent-index-enabled:false}")
          boolean enabled) {
    if (enabled) persistentIndex = new SqliteRepositoryMapIndex(data);
  }

  /** 共通の実行基盤で使用する保存される位置を設定する。 */
  void setPersistentIndex(SqliteRepositoryMapIndex index) {
    persistentIndex = index;
  }

  /** 共通の実行基盤で用いる対応表を返す。 */
  public synchronized View map(Path directory, String query, int limit) throws IOException {
    return build(directory, query, limit, false);
  }

  /** 現在の状態を、後から参照できる記録として取り出す。 */
  synchronized View snapshot(Path directory) throws IOException {
    return build(directory, "", 100, true);
  }

  /** 共通の実行基盤で用いるJava保有情報を返す。 */
  List<String> javaInventory(Path directory, FileSnapshots snapshots) throws IOException {
    try {
      return snapshots
          .inventory("java-symbols", () -> inventory.files(directory.toRealPath()))
          .stream()
          .filter(path -> path.endsWith(".java") && !sensitive(Path.of(path)))
          .toList();
    } catch (InterruptedException interrupted) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CancellationException();
    }
  }

  /**
   * 共通の実行基盤で用いる説明状態の記録を返す。
   *
   * <p>Reuse the same Java AST and hash-keyed metadata cache without rereading source bytes.
   */
  synchronized File describeSnapshot(Path directory, FileSnapshots.Snapshot snapshot)
      throws IOException {
    RunCancellation.propagate(null);
    Path root = directory.toRealPath();
    Path path = snapshot.path();
    if (!path.startsWith(root) || sensitive(root.relativize(path)))
      throw new IOException("Outside/excluded repository snapshot");
    String name = root.relativize(path).toString().replace('\\', '/');
    byte[] bytes = snapshot.bytes();
    Parsed parsed;
    var previous = caches.getOrDefault(root, Map.of());
    var cached = previous.get(name);
    if (bytes.length > 131072) parsed = new Parsed("", "TOO_LARGE", List.of(), List.of());
    else if (cached != null && cached.digest().equals(snapshot.version())) parsed = cached.parsed();
    else if (compiler == null)
      parsed = new Parsed("", "COMPILER_UNAVAILABLE", List.of(), List.of());
    else
      try (var manager =
          compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
        parsed =
            parse(
                compiler,
                manager,
                path,
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
      } catch (java.nio.charset.CharacterCodingException invalid) {
        parsed = new Parsed("", "UNREADABLE", List.of(), List.of());
      }
    var next = new TreeMap<>(previous);
    next.put(name, new Cached(snapshot.version(), parsed));
    while (next.size() > 1024) next.pollFirstEntry();
    caches.put(root, Map.copyOf(next));
    while (caches.size() > 2) caches.remove(caches.keySet().iterator().next());
    return new File(
        name,
        module(name),
        parsed.packageName(),
        snapshot.version(),
        parsed.status(),
        parsed.symbols(),
        parsed.imports());
  }

  /** 利用先に渡せるよう、保存と検索の窓口対応表サービスを組み立てる。 */
  private View build(Path directory, String query, int limit, boolean complete) throws IOException {
    RunCancellation.propagate(null);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (limit < 1 || limit > 100 || (query != null && query.length() > 256))
      throw new IllegalArgumentException(
          "Map limit must be 1 to 100 and query at most 256 characters");
    Path root = directory.toRealPath();
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    var paths = inventory.files(root).stream().distinct().sorted().toList();
    var previous = new HashMap<>(caches.getOrDefault(root, Map.of()));
    var next = new HashMap<String, Cached>();
    if (persistentIndex != null)
      try {
        persistentIndex
            .load(root)
            .forEach(
                (path, entry) ->
                    previous.putIfAbsent(
                        path,
                        new Cached(
                            entry.digest(),
                            new Parsed(
                                entry.packageName(), "PARSED", entry.symbols(), entry.imports()))));
      } catch (RuntimeException error) {
        RunCancellation.propagate(error);
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Repository metadata index read failed ({})", error.getClass().getSimpleName());
      }
    var files = new ArrayList<File>();
    var warnings = new LinkedHashSet<String>();
    if (paths.size() > 1024) warnings.add("File inventory limited to 1024 entries");
    var boundaries = HeuristicSourceIndex.boundaries(root, paths, deadline);
    warnings.addAll(boundaries.warnings());
    long bytesRead = boundaries.bytes();
    try (var manager =
        compiler == null
            ? null
            : compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
      for (String relative : paths.stream().limit(1024).toList()) {
        RunCancellation.propagate(null);
        if (System.nanoTime() - deadline >= 0) {
          warnings.add("Index time budget exhausted");
          break;
        }
        Path path;
        try {
          var input = Path.of(relative);
          if (input.isAbsolute() || input.normalize().startsWith("..") || sensitive(input))
            continue;
          path = root.resolve(input).normalize();
          if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
              || !path.toRealPath().startsWith(root)) continue;
        } catch (IOException | InvalidPathException error) {
          warnings.add("Some inventory paths were unavailable");
          continue;
        }
        String name = root.relativize(path).toString().replace('\\', '/');
        String digest = "";
        Parsed parsed = new Parsed("", "INVENTORY_ONLY", List.of(), List.of());
        if (HeuristicSourceIndex.source(name)) {
          try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            if (bytesRead + 131073 > 16 * 1024 * 1024) {
              warnings.add("Source byte budget exhausted");
              parsed = new Parsed("", "SKIPPED_LIMIT", List.of(), List.of());
            } else {
              byte[] bytes = stream.readNBytes(131073);
              bytesRead += bytes.length;
              if (bytes.length > 131072) {
                warnings.add("Oversized source skipped");
                parsed = new Parsed("", "TOO_LARGE", List.of(), List.of());
              } else {
                digest = hash(bytes);
                String cacheKey =
                    name.endsWith(".java")
                        ? digest
                        : digest + ":" + boundaries.forPath(name).root();
                var cached = previous.get(name);
                if (cached != null && cached.digest().equals(cacheKey)) parsed = cached.parsed();
                else if (name.endsWith(".java")) {
                  if (compiler == null)
                    parsed = new Parsed("", "COMPILER_UNAVAILABLE", List.of(), List.of());
                  else
                    parsed =
                        parse(
                            compiler,
                            manager,
                            path,
                            StandardCharsets.UTF_8
                                .newDecoder()
                                .decode(ByteBuffer.wrap(bytes))
                                .toString());
                } else {
                  var result =
                      HeuristicSourceIndex.parse(
                          name,
                          StandardCharsets.UTF_8
                              .newDecoder()
                              .decode(ByteBuffer.wrap(bytes))
                              .toString(),
                          boundaries.forPath(name));
                  parsed =
                      new Parsed(
                          result.packageName(),
                          result.status(),
                          result.symbols(),
                          result.imports());
                }
                next.put(name, new Cached(cacheKey, parsed));
              }
            }
          } catch (IOException error) {
            parsed = new Parsed("", "UNREADABLE", List.of(), List.of());
          }
          if (!name.endsWith(".java"))
            warnings.add(
                "Multilingual heuristic analysis is incomplete; no semantic compiler/module resolver, dynamic import or alias guarantee");
          if (!Set.of("PARSED", "HEURISTIC").contains(parsed.status()))
            warnings.add("Some sources could not be fully indexed: " + parsed.status());
        }
        files.add(
            new File(
                name,
                boundaries.forPath(name).root(),
                parsed.packageName(),
                digest,
                parsed.status(),
                parsed.symbols(),
                parsed.imports()));
      }
    }
    RunCancellation.propagate(null);
    caches.put(root, Map.copyOf(next));
    while (caches.size() > 2) caches.remove(caches.keySet().iterator().next());
    if (persistentIndex != null
        && warnings.stream().allMatch(value -> value.startsWith("Multilingual heuristic analysis")))
      try {
        persistentIndex.replace(
            root,
            next.entrySet().stream()
                .filter(e -> e.getValue().parsed().status().equals("PARSED"))
                .map(
                    e ->
                        new SqliteRepositoryMapIndex.Entry(
                            e.getKey(),
                            e.getValue().digest(),
                            e.getValue().parsed().packageName(),
                            e.getValue().parsed().symbols(),
                            e.getValue().parsed().imports()))
                .toList());
      } catch (RuntimeException error) {
        RunCancellation.propagate(error);
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Repository metadata index write failed ({})", error.getClass().getSimpleName());
      }
    String fingerprint =
        hash(
            (boundaries.values()
                    + files.stream()
                        .map(f -> f.path() + ":" + f.sha256() + ":" + f.status())
                        .reduce("", (a, b) -> a + "\n" + b))
                .getBytes(StandardCharsets.UTF_8));
    String filter = Objects.toString(query, "").toLowerCase(Locale.ROOT);
    var selected =
        files.stream()
            .filter(
                f ->
                    (f.path() + " " + f.packageName() + " " + f.module())
                            .toLowerCase(Locale.ROOT)
                            .contains(filter)
                        || f.symbols().stream()
                            .anyMatch(s -> s.name().toLowerCase(Locale.ROOT).contains(filter)))
            .limit(complete ? 1024 : limit)
            .toList();
    Set<String> visible = new HashSet<>();
    selected.forEach(f -> visible.add(f.path()));
    var combinedRelations = new LinkedHashSet<>(relations(files));
    combinedRelations.addAll(HeuristicSourceIndex.relations(files, boundaries));
    var allRelations = List.copyOf(combinedRelations);
    var relations =
        allRelations.stream()
            .filter(r -> visible.contains(r.source()) || visible.contains(r.target()))
            .limit(complete ? Long.MAX_VALUE : 200)
            .toList();
    return new View(
        root.toString(),
        Instant.now(),
        fingerprint,
        files.size(),
        !warnings.isEmpty(),
        List.copyOf(warnings),
        selected,
        relations,
        summary(files, allRelations, !warnings.isEmpty()));
  }

  /**
   * 共通の実行基盤で用いる要約を返す。
   *
   * <p>Observed structure over the bounded scan, independent of the caller's result filter.
   */
  private static Summary summary(List<File> files, List<Relation> relations, boolean partial) {
    var modules = new TreeMap<String, List<File>>();
    var packages = new TreeMap<String, Integer>();
    var entries = new ArrayList<EntryPoint>();
    int parsed = 0, unparsed = 0, inventory = 0, heuristic = 0;
    for (var file : files) {
      modules.computeIfAbsent(file.module(), key -> new ArrayList<>()).add(file);
      if (file.status().equals("PARSED")) {
        parsed++;
        packages.merge(
            file.packageName().isEmpty() ? "(default)" : file.packageName(), 1, Integer::sum);
        for (var symbol : file.symbols())
          if (symbol.entryPoint())
            entries.add(new EntryPoint(file.path(), symbol.name(), symbol.line()));
      } else if (file.path().endsWith(".java")) unparsed++;
      else if (file.status().equals("HEURISTIC")) {
        heuristic++;
        for (var symbol : file.symbols())
          if (symbol.entryPoint())
            entries.add(new EntryPoint(file.path(), symbol.name(), symbol.line()));
      } else inventory++;
      if (file.symbols().size() >= 64) partial = true;
    }
    boolean truncated = modules.size() > 100 || packages.size() > 100 || entries.size() > 100;
    var moduleSummaries =
        modules.entrySet().stream()
            .limit(100)
            .map(
                entry ->
                    new ModuleSummary(
                        entry.getKey(),
                        entry.getValue().size(),
                        (int)
                            entry.getValue().stream()
                                .filter(file -> file.status().equals("PARSED"))
                                .count(),
                        (int)
                            entry.getValue().stream()
                                .filter(file -> HeuristicSourceIndex.test(file.path()))
                                .count()))
            .toList();
    var packageSummaries =
        packages.entrySet().stream()
            .limit(100)
            .map(entry -> new PackageSummary(entry.getKey(), entry.getValue()))
            .toList();
    return new Summary(
        files.size(),
        parsed,
        unparsed,
        inventory,
        moduleSummaries,
        packageSummaries,
        entries.stream()
            .sorted(Comparator.comparing(EntryPoint::path).thenComparing(EntryPoint::symbol))
            .limit(100)
            .toList(),
        (int)
            relations.stream()
                .filter(relation -> Set.of("IMPORT", "HEURISTIC_IMPORT").contains(relation.kind()))
                .count(),
        (int)
            relations.stream()
                .filter(
                    relation ->
                        Set.of("TEST_NAME_CANDIDATE", "HEURISTIC_TEST_NAME_CANDIDATE")
                            .contains(relation.kind()))
                .count(),
        partial || truncated,
        heuristic);
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  private static Parsed parse(
      JavaCompiler compiler, StandardJavaFileManager manager, Path path, String text) {
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    String parseText = text.startsWith("\ufeff") ? " " + text.substring(1) : text;
    var source =
        new SimpleJavaFileObject(path.toUri(), JavaFileObject.Kind.SOURCE) {
          /** 共通の実行基盤で参照する文字内容を返す。 */
          @Override
          public CharSequence getCharContent(boolean ignored) {
            return parseText;
          }
        };
    try {
      // -proc:none does not disable javac's auto-start Plugin discovery during parse().
      // An explicit empty processor path prevents fallback to the host application's classpath.
      manager.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, List.of());
      var task =
          (JavacTask)
              compiler.getTask(
                  null, manager, diagnostics, List.of("-proc:none"), null, List.of(source));
      var unit = task.parse().iterator().next();
      if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR))
        return new Parsed("", "SYNTAX_ERROR", List.of(), List.of());
      String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
      var symbols = new ArrayList<Symbol>();
      var owners = new ArrayDeque<String>();
      var positions = Trees.instance(task).getSourcePositions();
      new TreePathScanner<Void, Void>() {
        /** 共通の実行基盤で用いる所有者を返す。 */
        String owner() {
          return (pkg.isEmpty() ? "" : pkg + ".") + String.join(".", owners);
        }

        /** 新たな対象を登録し、後から利用できるようにする。 */
        void add(
            Tree tree,
            String name,
            String kind,
            boolean entry,
            String id,
            String containing,
            int signatureEnd) {
          long start = positions.getStartPosition(unit, tree),
              end = positions.getEndPosition(unit, tree);
          if (symbols.size() >= 64 || start < 0 || end < start || end > text.length()) return;
          int from = (int) start,
              to = (int) end,
              header = Math.max(from, Math.min(to, signatureEnd));
          int docs = from, previous = from;
          while (previous > 0 && Character.isWhitespace(text.charAt(previous - 1))) previous--;
          if (previous >= 2 && text.substring(previous - 2, previous).equals("*/")) {
            int opening = text.lastIndexOf("/**", previous - 2);
            int closing = text.lastIndexOf("*/", previous - 3);
            if (opening >= 0 && opening > closing) docs = opening;
          }
          String signature = text.substring(from, header).strip();
          // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
          // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
          if (signature.length() > 2048) signature = signature.substring(0, 2048);
          symbols.add(
              new Symbol(
                  name,
                  kind,
                  unit.getLineMap().getLineNumber(start),
                  entry,
                  id,
                  signature,
                  unit.getLineMap().getLineNumber(Math.max(start, end - 1)),
                  from,
                  to,
                  containing,
                  header,
                  docs));
        }

        /** 共通の実行基盤で用いる対象の確認パッケージを返す。 */
        @Override
        public Void visitPackage(PackageTree tree, Void ignored) {
          add(tree, pkg, "PACKAGE", false, pkg, "", (int) positions.getEndPosition(unit, tree));
          return super.visitPackage(tree, ignored);
        }

        /** 共通の実行基盤で用いる対象の確認クラスを返す。 */
        @Override
        public Void visitClass(ClassTree tree, Void ignored) {
          if (tree.getSimpleName().isEmpty()) return null;
          String containing = owner();
          owners.addLast(tree.getSimpleName().toString());
          add(
              tree,
              owner(),
              tree.getKind().name(),
              false,
              owner(),
              containing,
              JavaSourceHeaders.end(
                  text,
                  (int) positions.getStartPosition(unit, tree),
                  (int) positions.getEndPosition(unit, tree)));
          super.visitClass(tree, ignored);
          owners.removeLast();
          return null;
        }

        /** 共通の実行基盤で用いる対象の確認メソッドを返す。 */
        @Override
        public Void visitMethod(MethodTree tree, Void ignored) {
          boolean main =
              tree.getName().contentEquals("main")
                  && tree.getReturnType() != null
                  && tree.getReturnType().toString().equals("void")
                  && tree.getModifiers()
                      .getFlags()
                      .containsAll(
                          Set.of(
                              javax.lang.model.element.Modifier.PUBLIC,
                              javax.lang.model.element.Modifier.STATIC))
                  && tree.getParameters().size() == 1
                  && Set.of("String[]", "java.lang.String[]")
                      .contains(tree.getParameters().getFirst().getType().toString());
          boolean constructor = tree.getReturnType() == null;
          String method = constructor ? owners.getLast() : tree.getName().toString();
          String types =
              tree.getParameters().stream()
                  .map(parameter -> parameter.getType().toString().replaceAll("\\s+", ""))
                  .collect(java.util.stream.Collectors.joining(","));
          int header =
              (int)
                  (tree.getBody() == null
                      ? positions.getEndPosition(unit, tree)
                      : positions.getStartPosition(unit, tree.getBody()));
          add(
              tree,
              owner() + "." + tree.getName(),
              constructor ? "CONSTRUCTOR" : "METHOD",
              main,
              owner() + "#" + method + "(" + types + ")",
              owner(),
              header);
          return super.visitMethod(tree, ignored);
        }

        /** 共通の実行基盤で用いる対象の確認可変値を返す。 */
        @Override
        public Void visitVariable(VariableTree tree, Void ignored) {
          if (getCurrentPath().getParentPath() != null
              && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree) {
            int header = (int) positions.getEndPosition(unit, tree);
            if (tree.getInitializer() != null) {
              header = (int) positions.getStartPosition(unit, tree.getInitializer());
              while (header > 0 && Character.isWhitespace(text.charAt(header - 1))) header--;
              if (header > 0 && text.charAt(header - 1) == '=') header--;
            }
            add(
                tree,
                owner() + "." + tree.getName(),
                "FIELD",
                false,
                owner() + "#" + tree.getName(),
                owner(),
                header);
          }
          return super.visitVariable(tree, ignored);
        }
      }.scan(unit, null);
      var imports =
          unit.getImports().stream()
              .limit(128)
              .map(i -> (i.isStatic() ? "static " : "") + i.getQualifiedIdentifier())
              .toList();
      return new Parsed(
          pkg,
          symbols.size() == 64 || unit.getImports().size() > 128 ? "DECLARATION_LIMIT" : "PARSED",
          List.copyOf(symbols),
          imports);
    } catch (IOException | RuntimeException error) {
      RunCancellation.propagate(error);
      return new Parsed("", "SYNTAX_ERROR", List.of(), List.of());
    }
  }

  /** 共通の実行基盤で用いる関連一覧を返す。 */
  private static List<Relation> relations(List<File> files) {
    var types = new HashMap<String, List<File>>();
    for (var file : files)
      if (file.language().equals("JAVA"))
        for (var symbol : file.symbols())
          if (typeKind(symbol.kind()))
            types.computeIfAbsent(symbol.name(), k -> new ArrayList<>()).add(file);
    var result = new LinkedHashSet<Relation>();
    for (var file : files) {
      if (!file.language().equals("JAVA")) continue;
      for (String imported : file.imports()) {
        String name = imported;
        if (name.startsWith("static ")) {
          name = name.substring(7);
          int dot = name.lastIndexOf('.');
          if (dot > 0) name = name.substring(0, dot);
        }
        var targets = types.get(name);
        if (targets != null
            && targets.size() == 1
            && !targets.getFirst().path().equals(file.path()))
          result.add(new Relation(file.path(), targets.getFirst().path(), "IMPORT"));
      }
      if (file.path().contains("/test/") || file.path().startsWith("test/"))
        for (var symbol : file.symbols())
          if (typeKind(symbol.kind()) && symbol.name().endsWith("Test")) {
            var targets = types.get(symbol.name().substring(0, symbol.name().length() - 4));
            if (targets != null && targets.size() == 1)
              result.add(
                  new Relation(file.path(), targets.getFirst().path(), "TEST_NAME_CANDIDATE"));
          }
    }
    return List.copyOf(result);
  }

  /** 共通の実行基盤で用いる機能単位を返す。 */
  private static String module(String path) {
    int marker = path.indexOf("/src/");
    return marker < 0 ? "." : path.substring(0, marker);
  }

  /** 共通の実行基盤において、秘密を含むに該当するか判定する。 */
  static boolean sensitive(Path path) {
    for (var part : path) {
      String name = part.toString().toLowerCase(Locale.ROOT);
      if (Set.of(".git", ".codex", ".agents", ".aws", ".m2", "target", "build", "node_modules")
              .contains(name)
          || name.matches(
              "\\.env(?:\\..*)?|credentials(?:[._].*)?|secrets(?:[._].*)?|.*\\.(key|pem)"))
        return true;
    }
    return false;
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  /** 共通の実行基盤で用いるGitファイル一覧を返す。 */
  private static List<String> gitFiles(Path root) throws IOException {
    var output =
        new dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner()
            .run(
                List.of(
                    "git",
                    "--no-optional-locks",
                    "-c",
                    "core.fsmonitor=false",
                    "ls-files",
                    "-z",
                    "--cached",
                    "--others",
                    "--exclude-standard"),
                root,
                "",
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                1048576,
                () -> Thread.currentThread().isInterrupted());
    if (output.status() == dev.mikoto2000.rei.externalagent.ExternalAgentResult.Status.CANCELLED)
      throw new java.util.concurrent.CancellationException();
    if (output.status() != dev.mikoto2000.rei.externalagent.ExternalAgentResult.Status.SUCCESS
        || output.truncated())
      throw new IOException("Git inventory unavailable or exceeds byte limit");
    return Arrays.stream(output.stdout().split("\u0000")).filter(s -> !s.isEmpty()).toList();
  }
}
