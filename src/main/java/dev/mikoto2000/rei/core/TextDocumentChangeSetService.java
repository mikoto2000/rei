package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * テキスト文書変更集合のユースケースを実現する。
 *
 * <p>Multi-file extension of the same text proposal repository and exclusive Project editing
 * boundary.
 */
@Service
public final class TextDocumentChangeSetService {
  /**
   * 共通の実行基盤で受け渡す操作を表す。
   *
   * @param kind 種類
   * @param path パス
   * @param target 対象
   * @param expectedText 期待する内容テキスト
   * @param replacement 置換
   */
  public record Operation(
      String kind,
      String path,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String target,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String expectedText,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String replacement,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String baseVersion,
      @org.springframework.ai.tool.annotation.ToolParam(required = false)
          List<TextChangeSetService.Edit> edits) {
    public Operation {
      edits = edits == null ? null : List.copyOf(edits);
    }

    public Operation(
        String kind, String path, String target, String expectedText, String replacement) {
      this(kind, path, target, expectedText, replacement, null, null);
    }
  }

  /**
   * 共通の実行基盤で受け渡す要求を表す。
   *
   * @param operations 操作一覧
   */
  public record Request(List<Operation> operations) {
    /** 受け取った情報からRequestを作成する。 */
    public Request {
      operations = operations == null ? List.of() : List.copyOf(operations);
    }
  }

  /**
   * 共通の実行基盤で受け渡すファイル表示内容を表す。
   *
   * @param path パス
   * @param baselineSha256 比較の基準内容の同一性を照合するSHA-256ハッシュ
   * @param proposedSha256 提案内容内容の同一性を照合するSHA-256ハッシュ
   * @param currentSha256 現在内容の同一性を照合するSHA-256ハッシュ
   * @param baselineExists 比較の基準存在
   * @param proposedExists 提案内容存在
   * @param currentAvailable 現在利用可能
   * @param currentExists 現在存在
   */
  public record FileView(
      String path,
      String baselineSha256,
      String proposedSha256,
      String currentSha256,
      boolean baselineExists,
      boolean proposedExists,
      boolean currentAvailable,
      boolean currentExists) {}

  /**
   * 共通の実行基盤で受け渡す反映前の準備領域表示内容を表す。
   *
   * @param target 対象
   * @param temporary 一時的な
   * @param expectedSha256 期待する内容内容の同一性を照合するSHA-256ハッシュ
   * @param currentSha256 現在内容の同一性を照合するSHA-256ハッシュ
   * @param available 利用可能
   * @param exists 存在
   */
  public record StagingView(
      String target,
      String temporary,
      String expectedSha256,
      String currentSha256,
      boolean available,
      boolean exists) {}

  /**
   * 共通の実行基盤で受け渡す表示内容を表す。
   *
   * @param id 識別子
   * @param status 状態
   * @param proposalSha256 提案内容の同一性を照合するSHA-256ハッシュ
   * @param files ファイル一覧
   * @param diff 差分
   * @param createdAt 作成日時
   * @param warnings 注意事項
   * @param phase 段階
   * @param stagingFiles 反映前の準備領域ファイル一覧
   * @param staging 反映前の準備領域
   * @param currentMatches 現在一致結果
   */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record View(
      String id,
      String status,
      String proposalSha256,
      List<FileView> files,
      String diff,
      Instant createdAt,
      List<String> warnings,
      String phase,
      int stagingFiles,
      List<StagingView> staging,
      boolean currentMatches,
      TextDiff.Info diffInfo,
      List<TextDiff.FileSummary> changes,
      int changedFiles,
      int changeCount,
      TextEditEngine.Failure error) {
    public View {
      files = files == null ? List.of() : List.copyOf(files);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
      staging = staging == null ? List.of() : List.copyOf(staging);
      changes = changes == null ? List.of() : List.copyOf(changes);
    }
  }

  /**
   * 共通の実行基盤で受け渡す保存済みを表す。
   *
   * @param id 識別子
   * @param project プロジェクト
   * @param root 作業ルート
   * @param session 会話
   * @param createdAt 作成日時
   * @param operations 操作一覧
   * @param changes 変更一覧
   */
  private record Saved(
      String id,
      String project,
      String root,
      String session,
      Instant createdAt,
      List<Operation> operations,
      List<TextDocumentTransaction.Change> changes) {}

  /**
   * 共通の実行基盤で受け渡す行を表す。
   *
   * @param saved 保存済み
   * @param sha 内容照合用のハッシュ
   * @param status 状態
   * @param phase 段階
   * @param stages 段階一覧
   * @param warnings 注意事項
   * @param pid プロセス識別子
   * @param processStart プロセス開始
   */
  private record Row(
      Saved saved,
      String sha,
      String status,
      String phase,
      List<TextDocumentTransaction.Stage> stages,
      List<String> warnings,
      long pid,
      String processStart) {}

  /** 活動中。 */
  private static final Set<String> ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** moveに関する値。 */
  private final TextDocumentTransaction.Move move;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper(
              com.fasterxml.jackson.core.JsonFactory.builder()
                  .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      com.fasterxml.jackson.core.StreamReadConstraints.builder()
                          .maxNestingDepth(16)
                          .maxStringLength(8388608)
                          .maxNumberLength(64)
                          .build())
                  .build())
          .findAndRegisterModules()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @org.springframework.beans.factory.annotation.Autowired
  public TextDocumentChangeSetService(TextChangeSetRepository repository, Clock clock) {
    this(repository, clock, TextDocumentTransaction::replace);
  }

  /** 指定された値や利用先を使って、TextDocumentChangeSetServiceを初期化する。 */
  public TextDocumentChangeSetService(
      TextChangeSetRepository repository, Clock clock, TextDocumentTransaction.Move move) {
    db = repository.documentsDb();
    this.clock = clock;
    this.move = move;
  }

  /** 後続の判断で選べる候補を用意するため、テキスト文書変更集合サービスを提案する。 */
  public View propose(AgentRunContext owner, Request request) throws IOException {
    Path root = owner(owner, true);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (request == null || request.operations().isEmpty() || request.operations().size() > 32)
      throw new IllegalArgumentException("1..32 text operations required");
    long inputBytes = 0;
    for (var op : request.operations())
      if (op != null)
        inputBytes +=
            TextEditEngine.inputBytes(op.expectedText())
                + TextEditEngine.inputBytes(op.replacement())
                + TextEditEngine.inputBytes(op.edits());
    TextEditEngine.requestSize(inputBytes);
    var changes = new ArrayList<TextDocumentTransaction.Change>();
    var paths = new HashSet<String>();
    int bytes = 0;
    for (var op : request.operations()) {
      if (op == null
          || op.kind() == null
          || !Set.of("UPDATE", "CREATE", "DELETE", "RENAME").contains(op.kind()))
        throw new IllegalArgumentException("UPDATE/CREATE/DELETE/RENAME required");
      selected(root, op.path(), paths);
      String current = TextDocumentTransaction.read(root, op.path());
      if (op.edits() != null) {
        if (!op.kind().equals("UPDATE")
            || op.expectedText() != null
            || op.replacement() != null
            || op.target() != null)
          throw new IllegalArgumentException(
              "Partial edits require UPDATE without full text or target");
        TextEditEngine.verifyVersion(op.path(), current, op.baseVersion());
        String replacement = TextEditEngine.edit(op.path(), current, op.edits()).text();
        TextDocumentTransaction.text(replacement);
        if (Objects.equals(current, replacement))
          throw new IllegalArgumentException("Actual update required");
        changes.add(new TextDocumentTransaction.Change(op.path(), current, replacement));
        continue;
      }
      if (TextEditEngine.inputBytes(op.expectedText()) > 65536
          || TextEditEngine.inputBytes(op.replacement()) > 65536)
        throw TextEditEngine.failure("SIZE_LIMIT_EXCEEDED", op.path(), null, List.of(), null);
      if (op.baseVersion() != null)
        throw new IllegalArgumentException("baseVersion requires partial edits");
      if (op.kind().equals("CREATE")) {
        if (current != null || op.expectedText() != null || op.target() != null)
          throw new IllegalArgumentException("Create needs an absent target and no baseline");
        TextDocumentTransaction.text(op.replacement());
        changes.add(new TextDocumentTransaction.Change(op.path(), null, op.replacement()));
      } else {
        if (current == null || op.expectedText() == null || !current.equals(op.expectedText()))
          throw TextEditEngine.failure(
              "VERSION_MISMATCH",
              op.path(),
              null,
              List.of(),
              TextDocumentTransaction.hash(current));
        TextDocumentTransaction.text(op.expectedText());
        switch (op.kind()) {
          case "UPDATE" -> {
            if (op.target() != null || Objects.equals(current, op.replacement()))
              throw new IllegalArgumentException("Actual update required");
            TextDocumentTransaction.text(op.replacement());
            changes.add(new TextDocumentTransaction.Change(op.path(), current, op.replacement()));
          }
          case "DELETE" -> {
            if (op.target() != null || op.replacement() != null)
              throw new IllegalArgumentException("Delete has no target or replacement");
            changes.add(new TextDocumentTransaction.Change(op.path(), current, null));
          }
          case "RENAME" -> {
            selected(root, op.target(), paths);
            if (TextDocumentTransaction.read(root, op.target()) != null)
              throw new IllegalArgumentException("Rename destination must be absent");
            String replacement = op.replacement() == null ? current : op.replacement();
            TextDocumentTransaction.text(replacement);
            changes.add(new TextDocumentTransaction.Change(op.path(), current, null));
            changes.add(new TextDocumentTransaction.Change(op.target(), null, replacement));
          }
          default -> throw new IllegalArgumentException("Invalid operation");
        }
      }
    }
    for (var change : changes)
      for (String value : new String[] {change.before(), change.after()})
        if (value != null) bytes += value.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > TextWriteLimits.totalBytes())
      throw TextEditEngine.failure("SIZE_LIMIT_EXCEEDED", null, null, List.of(), null);
    String id = UUID.randomUUID().toString();
    TextDocumentTransaction.preflight(root, id, changes);
    var saved =
        new Saved(
            id,
            owner.projectId(),
            root.toString(),
            owner.conversationId(),
            clock.instant(),
            request.operations().stream()
                .map(op -> new Operation(op.kind(), op.path(), op.target(), null, null))
                .toList(),
            List.copyOf(changes));
    String payload = encode(saved), sha = TextDocumentTransaction.hash(payload);
    db.sql(
            "DELETE FROM text_document_change_sets WHERE project=? AND status IN ('DISCARDED','APPLIED','ROLLED_BACK','STALE') AND stages='[]' AND id NOT IN (SELECT id FROM text_document_change_sets WHERE project=? AND status IN ('DISCARDED','APPLIED','ROLLED_BACK','STALE') AND stages='[]' ORDER BY rowid DESC LIMIT 100)")
        .params(owner.projectId(), owner.projectId())
        .update();
    if (db.sql(
                "INSERT INTO text_document_change_sets(id,project,root,session,payload,sha,status) SELECT ?,?,?,?,?,?,'PROPOSED' WHERE (SELECT count(*) FROM text_document_change_sets WHERE project=?)<128 AND (SELECT count(*) FROM text_document_change_sets)<1024")
            .params(
                id,
                owner.projectId(),
                root.toString(),
                owner.conversationId(),
                payload,
                sha,
                owner.projectId())
            .update()
        != 1) throw new IllegalStateException("Document proposal history capacity reached");
    return inspect(owner, id);
  }

  /** 対象の状態や内容を調べ、呼び出し元が確認できる情報として返す。 */
  public View inspect(AgentRunContext owner, String id) throws IOException {
    return inspect(owner, id, true);
  }

  private View inspect(AgentRunContext owner, String id, boolean preview) throws IOException {
    var row = owned(owner, id);
    reconcile(row);
    return view(owned(owner, id), preview);
  }

  /** 共通の実行基盤で用いる破棄を返す。 */
  public View discard(AgentRunContext owner, String id, String sha) throws IOException {
    owner(owner, true);
    var row = owned(owner, id);
    exact(row, sha);
    if (row.status().equals("DISCARDED")) return view(row);
    if (!row.status().equals("PROPOSED"))
      throw new IllegalStateException("Only unclaimed proposals can be discarded");
    finish(row, "PROPOSED", "DISCARDED", List.of());
    return inspect(owner, id);
  }

  /** 指定された内容を、現在の対象へ反映する。 */
  public View apply(AgentRunContext owner, String id, String sha) throws IOException {
    return apply(owner, id, sha, changes -> {});
  }

  /** 指定された内容を、現在の対象へ反映する。 */
  public View apply(
      AgentRunContext owner,
      String id,
      String sha,
      java.util.function.Consumer<List<TextDocumentTransaction.Change>> committed)
      throws IOException {
    owner(owner, true);
    var row = owned(owner, id);
    exact(row, sha);
    if (row.status().equals("APPLIED")) return view(row, false);
    if (!row.status().equals("PROPOSED"))
      throw new IllegalStateException("Consumed or uncertain document set; inspect without replay");
    Path root = Path.of(row.saved().root());
    if (!TextDocumentTransaction.matches(root, row.saved().changes(), false)) {
      finish(row, "PROPOSED", "STALE", List.of("Baseline changed; no writes"));
      return inspect(owner, id, false);
    }
    if (!ACTIVE.add(id)) throw new IllegalStateException("Document apply already active");
    try {
      int claim =
          db.sql(
                  "UPDATE text_document_change_sets SET status='APPLYING',phase='CLAIMED',pid=?,process_start=?,heartbeat=? WHERE id=? AND status='PROPOSED' AND NOT EXISTS (SELECT 1 FROM text_change_sets s WHERE s.root=text_document_change_sets.root AND s.status IN ('APPLYING','FAILED_UNCERTAIN','UNKNOWN'))")
              .params(ProcessHandle.current().pid(), processStart(), clock.instant().toString(), id)
              .update();
      if (claim != 1)
        throw new IllegalStateException("Document or single-file apply requires inspection");
      var outcome =
          TextDocumentTransaction.apply(
              root, id, row.saved().changes(), (phase, stages) -> journal(id, phase, stages), move);
      finish(row, "APPLYING", outcome.status(), outcome.warnings());
      if (outcome.status().equals("APPLIED")) {
        cleanupTerminal(row);
        committed.accept(row.saved().changes());
      }
      return inspect(owner, id, false);
    } catch (IOException | RuntimeException failure) {
      try {
        var current = owned(owner, id);
        if (current.status().equals("APPLYING")) {
          boolean restored =
              current.phase().equals("ROLLED_BACK")
                  && TextDocumentTransaction.matches(root, row.saved().changes(), false);
          finish(
              current,
              "APPLYING",
              restored ? "ROLLED_BACK" : "UNKNOWN",
              List.of("Apply interrupted; inspect saved journal and current hashes"));
        }
      } catch (IOException | RuntimeException persistence) {
        failure.addSuppressed(persistence);
      }
      RunCancellation.propagate(failure);
      throw failure;
    } finally {
      ACTIVE.remove(id);
    }
  }

  /** 共通の実行基盤で用いる取り消しを返す。 */
  public View rollback(AgentRunContext owner, String id, String sha, String actualUserRequest)
      throws IOException {
    owner(owner, true);
    var row = owned(owner, id);
    exact(row, sha);
    if (!("/document rollback " + id + " " + sha)
        .equals(actualUserRequest == null ? null : actualUserRequest.strip()))
      throw new IllegalArgumentException("Exact human /document rollback ID SHA required");
    reconcile(row);
    row = owned(owner, id);
    if (row.status().equals("ROLLED_BACK")) return view(row);
    if (!row.status().equals("UNKNOWN"))
      throw new IllegalStateException("Only inspected UNKNOWN operations can be recovered");
    if (!ACTIVE.add(id)) throw new IllegalStateException("Recovery already active");
    try {
      if (db.sql(
                  "UPDATE text_document_change_sets SET status='ROLLING_BACK',pid=?,process_start=?,heartbeat=?,recoveries=recoveries+1 WHERE id=? AND status='UNKNOWN' AND recoveries<3")
              .params(ProcessHandle.current().pid(), processStart(), clock.instant().toString(), id)
              .update()
          != 1) throw new IllegalStateException("Recovery changed or limit reached");
      var outcome =
          TextDocumentTransaction.rollback(
              Path.of(row.saved().root()),
              id,
              row.saved().changes(),
              row.stages(),
              (phase, stages) -> journal(id, phase, stages));
      finish(row, "ROLLING_BACK", outcome.status(), outcome.warnings());
      return inspect(owner, id);
    } catch (IOException | RuntimeException failure) {
      db.sql(
              "UPDATE text_document_change_sets SET status='UNKNOWN' WHERE id=? AND status='ROLLING_BACK'")
          .param(id)
          .update();
      RunCancellation.propagate(failure);
      throw failure;
    } finally {
      ACTIVE.remove(id);
    }
  }

  /** 共通の実行基盤で用いる後片付けを返す。 */
  public View cleanup(AgentRunContext owner, String id, String sha, String actualUserRequest)
      throws IOException {
    owner(owner, true);
    var row = owned(owner, id);
    exact(row, sha);
    if (!("/document clean " + id + " " + sha)
            .equals(actualUserRequest == null ? null : actualUserRequest.strip())
        || !Set.of("APPLIED", "ROLLED_BACK", "STALE").contains(row.status()))
      throw new IllegalArgumentException("Exact human terminal staging cleanup required");
    cleanupTerminal(row);
    return inspect(owner, id);
  }

  /** 共通の実行基盤に関する後片付け終端を処理する。 */
  private void cleanupTerminal(Row row) throws IOException {
    var stages =
        ownedRow(row.saved().project(), row.saved().root(), row.saved().session(), row.saved().id())
            .stages();
    try {
      TextDocumentTransaction.cleanupOwned(
          Path.of(row.saved().root()), row.saved().id(), row.saved().changes(), stages);
      journal(row.saved().id(), "CLEANED", List.of());
    } catch (IOException | RuntimeException unavailable) {
      db.sql("UPDATE text_document_change_sets SET warnings=? WHERE id=?")
          .params(
              encode(
                  List.of("Target outcome is saved; owned staging files require explicit cleanup")),
              row.saved().id())
          .update();
    }
  }

  /** 現在の状態と記録を照合し、差異に応じて管理状態を整える。 */
  private void reconcile(Row row) {
    if (row.pid() == ProcessHandle.current().pid()
        && Objects.equals(row.processStart(), processStart())
        && ACTIVE.contains(row.saved().id())) return;
    if (Set.of("APPLYING", "ROLLING_BACK").contains(row.status())
        && (!alive(row.pid(), row.processStart())
            || row.pid() == ProcessHandle.current().pid() && !ACTIVE.contains(row.saved().id())))
      db.sql(
              "UPDATE text_document_change_sets SET status='UNKNOWN',warnings=? WHERE id=? AND status=? AND pid=? AND process_start=?")
          .params(
              encode(
                  List.of("Process/operation no longer active; no automatic replay or rollback")),
              row.saved().id(),
              row.status(),
              row.pid(),
              row.processStart())
          .update();
  }

  /** 共通の実行基盤において、稼働中に該当するか判定する。 */
  private static boolean alive(long pid, String start) {
    try {
      return ProcessHandle.of(pid)
          .filter(ProcessHandle::isAlive)
          .flatMap(handle -> handle.info().startInstant())
          .map(instant -> instant.toString().equals(start))
          .orElse(false);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  /** 共通の実行基盤で用いるプロセス開始を返す。 */
  private static String processStart() {
    return ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("unknown");
  }

  /** 共通の実行基盤に関するjournalを処理する。 */
  private void journal(String id, String phase, List<TextDocumentTransaction.Stage> stages)
      throws IOException {
    if (db.sql(
                "UPDATE text_document_change_sets SET phase=?,stages=?,heartbeat=? WHERE id=? AND status IN ('APPLYING','ROLLING_BACK','APPLIED','ROLLED_BACK','STALE')")
            .params(phase, encode(stages), clock.instant().toString(), id)
            .update()
        != 1) throw new IOException("Document journal unavailable");
  }

  /** 終了時に必要な状態を反映し、処理を完了させる。 */
  private void finish(Row row, String expected, String status, List<String> warnings)
      throws IOException {
    if (db.sql(
                "UPDATE text_document_change_sets SET status=?,phase=?,warnings=?,heartbeat=? WHERE id=? AND status=?")
            .params(
                status,
                status,
                encode(warnings),
                clock.instant().toString(),
                row.saved().id(),
                expected)
            .update()
        != 1) throw new IOException("Document terminal receipt unavailable");
  }

  /** 共通の実行基盤で用いる自分が所有する対象を返す。 */
  private Row owned(AgentRunContext owner, String id) throws IOException {
    Path root = owner(owner, false);
    if (id == null || !id.matches("[a-f0-9-]{36}"))
      throw new IllegalArgumentException("Exact document ID required");
    return ownedRow(owner.projectId(), root.toString(), owner.conversationId(), id);
  }

  /** 共通の実行基盤で用いる自分が所有する対象行を返す。 */
  private Row ownedRow(String project, String root, String session, String id) {
    return db.sql(
            "SELECT * FROM text_document_change_sets WHERE id=? AND project=? AND root=? AND session=?")
        .params(id, project, root, session)
        .query(
            (rs, n) -> {
              String payload = rs.getString("payload"), sha = rs.getString("sha");
              if (!Objects.equals(TextDocumentTransaction.hash(payload), sha))
                throw new IllegalStateException("Document proposal payload changed");
              var saved = decode(payload, Saved.class);
              if (!saved.id().equals(id)
                  || !saved.project().equals(project)
                  || !saved.root().equals(root)
                  || !saved.session().equals(session))
                throw new IllegalStateException("Document payload ownership changed");
              return new Row(
                  saved,
                  sha,
                  rs.getString("status"),
                  rs.getString("phase"),
                  List.of(decode(rs.getString("stages"), TextDocumentTransaction.Stage[].class)),
                  List.of(decode(rs.getString("warnings"), String[].class)),
                  rs.getLong("pid"),
                  rs.getString("process_start"));
            })
        .optional()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Document set not found in this Project/root/session"));
  }

  /** 共通の実行基盤で用いる表示内容を返す。 */
  public TextDiff.Page diffPage(
      AgentRunContext owner, String id, String sha, int offset, int maxBytes) throws IOException {
    var row = owned(owner, id);
    exact(row, sha);
    return TextDiff.page(row.saved().changes(), offset, maxBytes);
  }

  private View view(Row row) throws IOException {
    return view(row, true);
  }

  private View view(Row row, boolean preview) throws IOException {
    var files = new ArrayList<FileView>();
    var page =
        preview ? TextDiff.page(row.saved().changes(), 0, TextWriteLimits.diffBytes()) : null;
    var summaries = TextDiff.summaries(row.saved().changes());
    Path root = Path.of(row.saved().root());
    for (var change : row.saved().changes()) {
      var current = TextDocumentTransaction.fingerprint(root, change.path());
      files.add(
          new FileView(
              change.path(),
              TextDocumentTransaction.hash(change.before()),
              TextDocumentTransaction.hash(change.after()),
              current.sha256(),
              change.before() != null,
              change.after() != null,
              current.available(),
              current.exists()));
    }
    var staging = new ArrayList<StagingView>();
    var seen = new HashSet<String>();
    for (var stage : row.stages())
      if (seen.add(stage.temporary())) {
        var current = TextDocumentTransaction.fingerprint(root, stage.temporary());
        staging.add(
            new StagingView(
                stage.path(),
                stage.temporary(),
                stage.sha256(),
                current.sha256(),
                current.available(),
                current.exists()));
      }
    boolean matching =
        files.stream()
            .allMatch(
                file ->
                    file.currentAvailable()
                        && file.proposedExists() == file.currentExists()
                        && Objects.equals(file.proposedSha256(), file.currentSha256()));
    return new View(
        row.saved().id(),
        row.status(),
        row.sha(),
        List.copyOf(files),
        page == null ? "" : page.diff(),
        row.saved().createdAt(),
        row.warnings(),
        row.phase(),
        (int) staging.stream().filter(StagingView::exists).count(),
        List.copyOf(staging),
        matching,
        page == null ? null : page.info(),
        summaries,
        summaries.size(),
        summaries.stream().mapToInt(TextDiff.FileSummary::changeCount).sum(),
        TextEditEngine.outcome(row.status(), null, null));
  }

  /** 共通の実行基盤に関する完全一致を処理する。 */
  private static void exact(Row row, String sha) {
    if (sha == null || !sha.equals(row.sha()))
      throw new IllegalArgumentException("Exact reviewed whole Change Set SHA required");
  }

  /** 共通の実行基盤に関する選択中を処理する。 */
  private static void selected(Path root, String name, Set<String> paths) throws IOException {
    TextDocumentTransaction.path(root, name);
    if (name.substring(name.lastIndexOf('/') + 1).startsWith(".rei-document-")
        || !paths.add(name.toLowerCase(Locale.ROOT)))
      throw new IllegalArgumentException("Unique nonreserved target paths required");
  }

  /** 共通の実行基盤で用いる所有者を返す。 */
  private static Path owner(AgentRunContext owner, boolean write) throws IOException {
    RunCancellation.propagate(null);
    if (owner == null
        || owner.projectId() == null
        || owner.conversationId().startsWith("subagent:")
        || write && owner.mode() != AgentRunContext.Mode.EXCLUSIVE)
      throw new IllegalArgumentException(
          "Captured human Project/session and exclusive writes required");
    Path root = owner.projectRoot().toRealPath();
    if (!root.equals(owner.projectRoot()))
      throw new IllegalArgumentException("Canonical captured Project root required");
    return root;
  }

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  private String encode(Object value) {
    try {
      String payload = json.writeValueAsString(value);
      if (payload.getBytes(StandardCharsets.UTF_8).length > TextWriteLimits.payloadBytes())
        throw new IllegalArgumentException("Document payload exceeds 1MiB");
      return payload;
    } catch (IOException invalid) {
      throw new IllegalStateException("Document serialization unavailable", invalid);
    }
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  private <T> T decode(String value, Class<T> type) {
    try {
      if (value == null
          || value.getBytes(StandardCharsets.UTF_8).length > TextWriteLimits.payloadBytes())
        throw new IllegalStateException("Document payload exceeds bounds");
      return json.readValue(value, type);
    } catch (IOException invalid) {
      throw new IllegalStateException("Document payload unavailable", invalid);
    }
  }
}
