package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * テキスト変更集合のユースケースを実現する。
 *
 * <p>Single existing UTF-8 file, exact baseline, durable proposal identity and explicit one-shot
 * Apply.
 */
@Service
public class TextChangeSetService {
  /** 保存済み文書。 */
  private TextDocumentChangeSetService documents;

  /** 共通の実行基盤で使用する保存済み文書を設定する。 */
  @Autowired(required = false)
  public void setDocuments(TextDocumentChangeSetService service) {
    documents = service;
  }

  /** 後続の判断で選べる候補を用意するため、保存済み文書を提案する。 */
  public TextDocumentChangeSetService.View proposeDocuments(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner,
      TextDocumentChangeSetService.Request request)
      throws IOException {
    return documents().propose(owner, request);
  }

  /** 共通の実行基盤で用いる状態確認保存済み文書を返す。 */
  public TextDocumentChangeSetService.View inspectDocuments(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner, String id) throws IOException {
    return documents().inspect(owner, id);
  }

  /** 共通の実行基盤で用いる破棄保存済み文書を返す。 */
  public TextDocumentChangeSetService.View discardDocuments(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner, String id, String sha)
      throws IOException {
    return documents().discard(owner, id, sha);
  }

  /** 指定された変更を有効にするため、保存済み文書を反映する。 */
  public TextDocumentChangeSetService.View applyDocuments(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner,
      String id,
      String sha,
      java.util.function.Consumer<java.util.List<TextDocumentTransaction.Change>> committed)
      throws IOException {
    return documents().apply(owner, id, sha, committed);
  }

  /** 共通の実行基盤で用いる取り消し保存済み文書を返す。 */
  public TextDocumentChangeSetService.View rollbackDocuments(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner,
      String id,
      String sha,
      String actualRequest)
      throws IOException {
    return documents().rollback(owner, id, sha, actualRequest);
  }

  /** 共通の実行基盤で用いる変更なし文書反映前の準備領域を返す。 */
  public TextDocumentChangeSetService.View cleanDocumentStaging(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner,
      String id,
      String sha,
      String actualRequest)
      throws IOException {
    return documents().cleanup(owner, id, sha, actualRequest);
  }

  /** 共通の実行基盤で用いる保存済み文書を返す。 */
  private TextDocumentChangeSetService documents() {
    if (documents == null)
      throw new IllegalStateException("Multi-file document service unavailable");
    return documents;
  }

  /**
   * 共通の実行基盤で受け渡す編集を表す。
   *
   * @param oldText 以前のテキスト
   * @param newText 新規テキスト
   */
  public record Edit(String oldText, String newText) {}

  /**
   * 共通の実行基盤で受け渡す要求を表す。
   *
   * @param path パス
   * @param expectedText 期待する内容テキスト
   * @param replacement 置換
   * @param baseVersion 基準形式のバージョン
   * @param edits 編集一覧
   */
  public record Request(
      String path,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String expectedText,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String replacement,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String baseVersion,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) List<Edit> edits) {
    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Request(String path, String expectedText, String replacement) {
      this(path, expectedText, replacement, null, null);
    }
  }

  /**
   * 共通の実行基盤で受け渡す比較の基準を表す。
   *
   * @param path パス
   * @param text テキスト
   * @param sha256 内容の同一性を照合するSHA-256ハッシュ
   */
  public record Baseline(String path, String text, String sha256) {}

  /**
   * 共通の実行基盤で受け渡す表示内容を表す。
   *
   * @param id 識別子
   * @param projectId 所属プロジェクトの識別子
   * @param path パス
   * @param status 状態
   * @param baselineSha256 比較の基準内容の同一性を照合するSHA-256ハッシュ
   * @param proposedSha256 提案内容内容の同一性を照合するSHA-256ハッシュ
   * @param proposalSha256 提案内容の同一性を照合するSHA-256ハッシュ
   * @param currentSha256 現在内容の同一性を照合するSHA-256ハッシュ
   * @param diff 差分
   * @param createdAt 作成日時
   */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record View(
      String id,
      String projectId,
      String path,
      String status,
      String baselineSha256,
      String proposedSha256,
      String proposalSha256,
      String currentSha256,
      String diff,
      Instant createdAt,
      TextDiff.Info diffInfo,
      List<TextDiff.FileSummary> changes,
      int changedFiles,
      int changeCount,
      boolean currentMatches,
      List<String> warnings,
      TextEditEngine.Failure error) {
    public View {
      changes = changes == null ? List.of() : List.copyOf(changes);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }

  /** 共通の実行基盤に必要な書き込み先を扱う。 */
  @FunctionalInterface
  public interface Writer {
    /** 指定された内容を書き込み先へ反映する。 */
    void write(Path path, String oldText, String newText) throws IOException;
  }

  /** 保存と検索の窓口。 */
  private final TextChangeSetRepository repository;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 識別子一覧。 */
  private final Supplier<String> ids;

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public TextChangeSetService(TextChangeSetRepository repository) {
    this(repository, Clock.systemUTC(), () -> UUID.randomUUID().toString());
  }

  /** 指定された値や利用先を使って、TextChangeSetServiceを初期化する。 */
  public TextChangeSetService(
      TextChangeSetRepository repository, Clock clock, Supplier<String> ids) {
    this.repository = repository;
    this.clock = clock;
    this.ids = ids;
  }

  /** 必要な内容を参照できるよう、基準を読み取る。 */
  public Baseline readBase(ProjectContext project, String path) throws IOException {
    RunCancellation.propagate(null);
    var root = project.root().toRealPath();
    var file = resolve(root, path);
    var text = read(file);
    return new Baseline(root.relativize(file).toString().replace('\\', '/'), text, hash(text));
  }

  /** 後続の判断で選べる候補を用意するため、テキスト変更集合サービスを提案する。 */
  public View propose(ProjectContext project, Request request) throws IOException {
    RunCancellation.propagate(null);
    if (request == null) throw new IllegalArgumentException("Change Set request required");
    TextEditEngine.requestSize(
        TextEditEngine.inputBytes(request.expectedText())
            + TextEditEngine.inputBytes(request.replacement())
            + TextEditEngine.inputBytes(request.edits()));
    if (request.edits() == null
        && (TextEditEngine.inputBytes(request.expectedText()) > 65536
            || TextEditEngine.inputBytes(request.replacement()) > 65536))
      throw TextEditEngine.failure("SIZE_LIMIT_EXCEEDED", request.path(), null, List.of(), null);
    String snapshotText = null;
    if (request.edits() != null) {
      if (request.expectedText() != null || request.replacement() != null)
        throw new IllegalArgumentException("Choose full replacement or partial edits, not both");
      if (request.baseVersion() == null)
        throw new IllegalArgumentException("Partial edits require baseVersion");
      var root = project.root().toRealPath();
      var file = resolve(root, request.path());
      String current = read(file);
      TextEditEngine.verifyVersion(request.path(), current, request.baseVersion());
      String replacement = TextEditEngine.edit(request.path(), current, request.edits()).text();
      validateText(replacement);
      // Reuse all existing validation, ownership, durable proposal identity, diff and explicit
      // Apply.
      snapshotText = current;
      request = new Request(request.path(), current, replacement);
    }
    if (request.baseVersion() != null)
      throw new IllegalArgumentException("baseVersion requires partial edits");
    validateText(request.expectedText());
    validateText(request.replacement());
    if (request.expectedText().isEmpty() || request.expectedText().equals(request.replacement()))
      throw new IllegalArgumentException("Nonempty baseline and an actual change required");
    var root = project.root().toRealPath();
    var file = resolve(root, request.path());
    var current = snapshotText == null ? read(file) : snapshotText;
    if (!current.equals(request.expectedText()))
      throw TextEditEngine.failure(
          "VERSION_MISMATCH", request.path(), null, List.of(), hash(current));
    var relative = root.relativize(file).toString().replace('\\', '/');
    var base = hash(current);
    var proposed = hash(request.replacement());
    var proposal =
        hash(
            project.id()
                + "\u0000"
                + root
                + "\u0000"
                + relative
                + "\u0000"
                + base
                + "\u0000"
                + proposed);
    var saved =
        new TextChangeSetRepository.Saved(
            ids.get(),
            project.id(),
            root.toString(),
            relative,
            current,
            request.replacement(),
            base,
            proposed,
            proposal,
            "PROPOSED",
            clock.instant());
    RunCancellation.propagate(null);
    repository.save(saved);
    return view(saved, base);
  }

  /** 対象の状態や内容を調べ、呼び出し元が確認できる情報として返す。 */
  public View inspect(ProjectContext project, String id) throws IOException {
    return inspect(project, id, true);
  }

  private View inspect(ProjectContext project, String id, boolean preview) throws IOException {
    var saved = owned(project, id);
    String current = null;
    try {
      current = hash(read(resolve(Path.of(saved.root()), saved.path())));
    } catch (IOException | IllegalArgumentException unavailable) {
      /* Missing/invalid current file is not an empty file or a successful receipt. */
    }
    return view(saved, current, preview);
  }

  /**
   * 共通の実行基盤で用いる書き出し提案を返す。
   *
   * <p>Export the persisted proposal, including stale proposals, without touching the current file.
   */
  public byte[] exportProposal(ProjectContext project, String id) throws IOException {
    var saved = owned(project, id);
    validateText(saved.proposed());
    return saved.proposed().getBytes(StandardCharsets.UTF_8);
  }

  /** 共通の実行基盤で用いる破棄を返す。 */
  public View discard(ProjectContext project, String id, String proposalHash) throws IOException {
    var saved = owned(project, id);
    if (proposalHash == null || !saved.proposalHash().equals(proposalHash))
      throw new IllegalArgumentException("Exact reviewed proposal hash required");
    if (saved.status().equals("DISCARDED")) return inspect(project, id);
    if (!saved.status().equals("PROPOSED")
        || !repository.transition(project.id(), id, "PROPOSED", "DISCARDED"))
      throw new IllegalStateException("Only an unclaimed proposal may be discarded");
    return inspect(project, id);
  }

  /** 指定された内容を、現在の対象へ反映する。 */
  public View apply(ProjectContext project, String id, String proposalHash, Writer writer)
      throws IOException {
    return apply(project, id, proposalHash, writer, null);
  }

  /** 共通の実行基盤に関する保護診断済みを処理する。 */
  void protectDiagnosed(ProjectContext project, String id) throws IOException {
    var saved = owned(project, id);
    if (!saved.status().equals("PROPOSED"))
      throw new IllegalStateException("Only a pending proposal can receive a repair guard");
    repository.protectDiagnosed(project.id(), id);
  }

  /** 指定された変更を有効にするため、診断済みを反映する。 */
  View applyDiagnosed(ProjectContext project, String id, String proposalHash, Writer writer)
      throws IOException {
    return apply(project, id, proposalHash, writer, "DIAGNOSED_REPAIR");
  }

  /** 指定された内容を、現在の対象へ反映する。 */
  private View apply(
      ProjectContext project, String id, String proposalHash, Writer writer, String trustedGuard)
      throws IOException {
    RunCancellation.propagate(null);
    var saved = owned(project, id);
    if (proposalHash == null || !saved.proposalHash().equals(proposalHash))
      throw new IllegalArgumentException("Exact reviewed proposal hash required");
    var root = Path.of(saved.root());
    var file = resolve(root, saved.path());
    var current = hash(read(file));
    if (saved.status().equals("APPLIED"))
      return view(saved, current, false); // Receipt read; never execute the writer twice.
    var guard = repository.applyGuard(project.id(), id);
    if (guard != null && !guard.equals(trustedGuard)
        || trustedGuard != null && !trustedGuard.equals(guard))
      throw new IllegalStateException(
          "Diagnosed repair proposal requires its exact human approval flow");
    if (!saved.status().equals("PROPOSED"))
      throw new IllegalStateException(
          "Change Set is no longer applicable; inspect current content");
    if (!saved.baselineHash().equals(current)) {
      repository.transition(project.id(), id, "PROPOSED", "STALE");
      return inspect(project, id, false);
    }
    if (!repository.transition(project.id(), id, "PROPOSED", "APPLYING"))
      throw new IllegalStateException("Change Set already claimed");
    try (var lease = repository.activate(id)) {
      RunCancellation.propagate(null);
      // Recheck after the durable claim and immediately before the existing editing boundary.
      file = resolve(root, saved.path());
      if (!saved.baselineHash().equals(hash(read(file)))) {
        repository.transition(project.id(), id, "APPLYING", "STALE");
        return inspect(project, id, false);
      }
      writer.write(file, saved.baseline(), saved.proposed());
      RunCancellation.propagate(null);
      if (!saved.proposedHash().equals(hash(read(resolve(root, saved.path())))))
        throw new IOException("Applied content requires inspection");
      repository.heartbeat(id);
      if (!repository.transition(project.id(), id, "APPLYING", "APPLIED"))
        throw new IOException("Apply receipt could not be saved");
      return inspect(project, id, false);
    } catch (IOException | RuntimeException error) {
      try {
        repository.transition(project.id(), id, "APPLYING", "FAILED_UNCERTAIN");
      } catch (RuntimeException persistence) {
        error.addSuppressed(persistence);
      }
      RunCancellation.propagate(error);
      throw error;
    }
  }

  /** 現在の状態と記録を照合し、差異に応じて管理状態を整える。 */
  public View reconcile(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner,
      String id,
      String proposalHash,
      String actualRequest)
      throws IOException {
    RunCancellation.propagate(null);
    if (owner == null
        || owner.mode() != dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE
        || owner.requestSource() != dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL
        || owner.conversationId().startsWith("subagent:"))
      throw new IllegalArgumentException("Exclusive human Shell owner required");
    var project = new ProjectContext(owner.projectId(), "", owner.projectRoot());
    var saved = owned(project, id);
    if (!saved.proposalHash().equals(proposalHash)
        || !("/document reconcile-single " + id + " " + proposalHash)
            .equals(actualRequest == null ? null : actualRequest.strip()))
      throw new IllegalArgumentException("Exact human reconciliation ID/SHA request required");
    if (saved.status().equals("RECONCILED")) return inspect(project, id);
    if (!Set.of("UNKNOWN", "FAILED_UNCERTAIN").contains(saved.status()))
      throw new IllegalStateException("Only an uncertain lost Apply can be reconciled");
    String current = hash(read(resolve(Path.of(saved.root()), saved.path())));
    if (!Set.of(saved.baselineHash(), saved.proposedHash()).contains(current))
      throw new IllegalStateException(
          "Current content is neither saved version; inspect and manually repair first");
    if (!repository.transition(project.id(), id, saved.status(), "RECONCILED"))
      throw new IllegalStateException("Reconciliation state changed");
    return inspect(project, id);
  }

  /** 共通の実行基盤で用いる自分が所有する対象を返す。 */
  private TextChangeSetRepository.Saved owned(ProjectContext project, String id)
      throws IOException {
    RunCancellation.propagate(null);
    if (id == null || id.isBlank()) throw new IllegalArgumentException("Change Set ID required");
    var saved = repository.peek(project.id(), id);
    if (!project.root().toRealPath().toString().equals(saved.root()))
      throw new IllegalArgumentException("Change Set belongs to a different Project root");
    return repository.get(project.id(), id);
  }

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  private static Path resolve(Path root, String name) throws IOException {
    if (name == null
        || name.isBlank()
        || name.length() > 1024
        || name.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Project-relative text path required");
    Path relative;
    try {
      relative = Path.of(name);
    } catch (InvalidPathException error) {
      throw new IllegalArgumentException("Invalid text path");
    }
    if (relative.isAbsolute()
        || relative.getRoot() != null
        || relative.normalize().startsWith("..")
        || RepositoryMapService.sensitive(relative))
      throw new IllegalArgumentException("Excluded or outside text path");
    var file = root.resolve(relative).normalize();
    var cursor = root;
    for (var part : root.relativize(file)) {
      cursor = cursor.resolve(part);
      if (Files.isSymbolicLink(cursor)
          || Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().equals(cursor))
        throw new IllegalArgumentException("Linked or aliased text paths are unsupported");
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || !file.toRealPath().startsWith(root))
      throw new IllegalArgumentException("Existing regular Project text file required");
    return file;
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  private static String read(Path file) throws IOException {
    if (Files.size(file) > TextWriteLimits.fileBytes())
      throw TextEditEngine.failure("SIZE_LIMIT_EXCEEDED", file.toString(), null, List.of(), null);
    var bytes = new FileSnapshots().get(file.getParent(), file).bytes();
    if (bytes.length > TextWriteLimits.fileBytes())
      throw TextEditEngine.failure("SIZE_LIMIT_EXCEEDED", file.toString(), null, List.of(), null);
    for (byte value : bytes)
      if (value == 0) throw new IllegalArgumentException("Binary text is unsupported");
    try {
      return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    } catch (java.nio.charset.CharacterCodingException error) {
      throw TextEditEngine.failure("UNSUPPORTED_ENCODING", file.toString(), null, List.of(), null);
    }
  }

  /** 利用条件に合わない指定を受け付けないよう、テキストを検証する。 */
  private static void validateText(String text) {
    if (text == null
        || text.indexOf(0) >= 0
        || text.getBytes(StandardCharsets.UTF_8).length > TextWriteLimits.fileBytes())
      throw TextEditEngine.failure("SIZE_LIMIT_EXCEEDED", null, null, List.of(), null);
    try {
      StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));
    } catch (java.nio.charset.CharacterCodingException error) {
      throw new IllegalArgumentException("Valid Unicode text required");
    }
  }

  /** 共通の実行基盤で用いる編集を返す。 */
  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  private static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  /** Pages are derived from the immutable saved proposal, never from a new current baseline. */
  public TextDiff.Page diffPage(
      ProjectContext project, String id, String sha, int offset, int maxBytes) throws IOException {
    var saved = owned(project, id);
    if (!saved.proposalHash().equals(sha))
      throw new IllegalArgumentException("Exact reviewed proposal SHA required");
    return TextDiff.page(
        List.of(
            new TextDocumentTransaction.Change(saved.path(), saved.baseline(), saved.proposed())),
        offset,
        maxBytes);
  }

  public TextDiff.Page documentDiffPage(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner,
      String id,
      String sha,
      int offset,
      int maxBytes)
      throws IOException {
    return documents().diffPage(owner, id, sha, offset, maxBytes);
  }

  private static View view(TextChangeSetRepository.Saved saved, String current) {
    return view(saved, current, true);
  }

  private static View view(TextChangeSetRepository.Saved saved, String current, boolean preview) {
    var changes =
        List.of(
            new TextDocumentTransaction.Change(saved.path(), saved.baseline(), saved.proposed()));
    var page = preview ? TextDiff.page(changes, 0, TextWriteLimits.diffBytes()) : null;
    var summaries = TextDiff.summaries(changes);
    boolean matching = saved.proposedHash().equals(current);
    return new View(
        saved.id(),
        saved.projectId(),
        saved.path(),
        saved.status(),
        saved.baselineHash(),
        saved.proposedHash(),
        saved.proposalHash(),
        current,
        page == null ? "" : page.diff(),
        saved.createdAt(),
        page == null ? null : page.info(),
        summaries,
        summaries.size(),
        summaries.stream().mapToInt(TextDiff.FileSummary::changeCount).sum(),
        matching,
        saved.status().equals("APPLIED") && !matching
            ? List.of("Historical receipt; current file differs or is unavailable")
            : List.of(),
        TextEditEngine.outcome(saved.status(), saved.path(), current));
  }
}
