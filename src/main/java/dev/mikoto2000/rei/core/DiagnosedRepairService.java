package dev.mikoto2000.rei.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.service.SystemShellService;
import dev.mikoto2000.rei.event.CredentialRedactor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 診断済み修復のユースケースを実現する。
 *
 * <p>Saved failure -> existing Change Set -> exact human approval -> bounded existing repair cycle.
 */
@Service
public final class DiagnosedRepairService {
  /**
   * 共通の実行基盤で受け渡す要求を表す。
   *
   * @param reportPath レポートパス
   * @param change 変更
   * @param testCommand 検証コマンド
   * @param timeoutSeconds タイムアウトの秒数
   */
  public record Request(
      String reportPath,
      TextChangeSetService.Request change,
      String testCommand,
      Integer timeoutSeconds) {}

  /**
   * 共通の実行基盤で受け渡す表示内容を表す。
   *
   * @param id 識別子
   * @param status 状態
   * @param receiptSha256 受付記録内容の同一性を照合するSHA-256ハッシュ
   * @param diagnosis 診断
   * @param patchVersion 変更差分形式のバージョン
   * @param changeSet 変更集合
   * @param testCommand 検証コマンド
   * @param timeoutSeconds タイムアウトの秒数
   * @param verification 確認結果
   */
  public record View(
      String id,
      String status,
      String receiptSha256,
      TestReportDiagnosisService.Result diagnosis,
      String patchVersion,
      TextChangeSetService.View changeSet,
      String testCommand,
      int timeoutSeconds,
      SelfPatchRepairService.Result verification) {}

  /**
   * 共通の実行基盤で受け渡す保存済みを表す。
   *
   * @param id 識別子
   * @param project プロジェクト
   * @param root 作業ルート
   * @param session 会話
   * @param receiptHash 受付記録ハッシュ値
   * @param diagnosis 診断
   * @param patchVersion 変更差分形式のバージョン
   * @param changeHash 変更ハッシュ値
   * @param testCommand 検証コマンド
   * @param timeoutSeconds タイムアウトの秒数
   */
  private record Saved(
      String id,
      String project,
      String root,
      String session,
      String receiptHash,
      TestReportDiagnosisService.Result diagnosis,
      String patchVersion,
      String changeHash,
      String testCommand,
      int timeoutSeconds) {}

  /**
   * 共通の実行基盤で受け渡す保存済みを表す。
   *
   * @param saved 保存済み
   * @param status 状態
   * @param result 結果
   */
  private record Stored(Saved saved, String status, SelfPatchRepairService.Result result) {}

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 一連の更新。 */
  private final TransactionTemplate transaction;

  /** 変更一覧。 */
  private final TextChangeSetService changes;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 有効状態。 */
  private final boolean enabled;

  /** 取得記録。 */
  private final SelfPatchReviewService.Capture capture;

  /** 一回の診断・修復のまとまり。 */
  private final SelfPatchRepairService.Cycle cycle;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  /** 利用権一覧。 */
  private final PersistedReceiptLease leases;

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @org.springframework.beans.factory.annotation.Autowired
  public DiagnosedRepairService(
      @org.springframework.beans.factory.annotation.Qualifier("memoryConsolidationDataSource")
          DataSource source,
      TextChangeSetService changes,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value("${rei.repair.diagnosed-enabled:false}")
          boolean enabled,
      SystemShellService shell) {
    this(
        source,
        changes,
        clock,
        enabled,
        new GitPatchInspector(new dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner())
            ::capture,
        new SelfPatchReviewService(shell)::verify);
  }

  /** 指定された値や利用先を使って、DiagnosedRepairServiceを初期化する。 */
  public DiagnosedRepairService(
      DataSource source,
      TextChangeSetService changes,
      Clock clock,
      boolean enabled,
      SelfPatchReviewService.Capture capture,
      SelfPatchRepairService.Cycle cycle) {
    this.changes = changes;
    this.clock = clock;
    this.enabled = enabled;
    this.capture = capture;
    this.cycle = cycle;
    db = JdbcClient.create(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    db.sql(
            "CREATE TABLE IF NOT EXISTS diagnosed_repairs(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,payload TEXT NOT NULL,status TEXT NOT NULL,result TEXT)")
        .update();
    leases = new PersistedReceiptLease(source, "diagnosed_repairs", clock);
  }

  /** 後続の判断で選べる候補を用意するため、診断済み修復サービスを提案する。 */
  public View propose(AgentRunContext owner, Request request) throws IOException {
    requireEnabled();
    requireExclusive(owner);
    Path root = owner(owner);
    validate(request);
    long deadline = deadline();
    var diagnosis = new TestReportDiagnosisService().read(root, request.reportPath());
    requireFailure(diagnosis);
    var snapshot = capture.capture(root, deadline);
    RunCancellation.propagate(null);
    if (!snapshot.complete()
        || snapshot.changedFiles().isEmpty()
        || snapshot.changedFiles().size() > 32)
      throw new IllegalStateException("Complete patch of 1 to 32 files required");
    if (!snapshot.changedFiles().contains(request.change().path().replace('\\', '/')))
      throw new IllegalArgumentException("Repair must select a changed patch file");
    final int seconds = request.timeoutSeconds() == null ? 30 : request.timeoutSeconds();
    var saved =
        transaction.execute(
            status -> {
              db.sql("UPDATE diagnosed_repairs SET status=status WHERE id=''").update();
              if (db.sql("SELECT count(*) FROM diagnosed_repairs WHERE project=?")
                      .param(owner.projectId())
                      .query(Integer.class)
                      .single()
                  >= 128)
                throw new IllegalStateException("Diagnosed repair history capacity reached (128)");
              try {
                var proposal = changes.propose(project(owner, root), request.change());
                changes.protectDiagnosed(project(owner, root), proposal.id());
                String hash =
                    hash(
                        proposal.id()
                            + "\u0000"
                            + owner.projectId()
                            + "\u0000"
                            + root
                            + "\u0000"
                            + owner.conversationId()
                            + "\u0000"
                            + diagnosis.sha256()
                            + "\u0000"
                            + snapshot.version()
                            + "\u0000"
                            + proposal.proposalSha256()
                            + "\u0000"
                            + request.testCommand()
                            + "\u0000"
                            + seconds);
                var record =
                    new Saved(
                        proposal.id(),
                        owner.projectId(),
                        root.toString(),
                        owner.conversationId(),
                        hash,
                        diagnosis,
                        snapshot.version(),
                        proposal.proposalSha256(),
                        request.testCommand(),
                        seconds);
                db.sql(
                        "INSERT INTO diagnosed_repairs(id,project,root,session,payload,status,result) VALUES(?,?,?,?,?,'PROPOSED',NULL)")
                    .params(
                        record.id(),
                        record.project(),
                        record.root(),
                        record.session(),
                        encode(record))
                    .update();
                return record;
              } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
              }
            });
    return view(owner, new Stored(saved, "PROPOSED", null));
  }

  /** 対象の状態や内容を調べ、呼び出し元が確認できる情報として返す。 */
  public View inspect(AgentRunContext owner, String id) throws IOException {
    return view(owner, owned(owner, id));
  }

  /** 指定された内容を、現在の対象へ反映する。 */
  public View apply(
      AgentRunContext owner,
      String id,
      String receiptHash,
      String actualUserRequest,
      TextChangeSetService.Writer writer)
      throws IOException {
    requireEnabled();
    requireExclusive(owner);
    Path root = owner(owner);
    var stored = owned(owner, id);
    var saved = stored.saved();
    if (!Objects.equals(saved.receiptHash(), receiptHash)
        || !("/repair apply " + id + " " + receiptHash)
            .equals(actualUserRequest == null ? null : actualUserRequest.strip()))
      throw new IllegalArgumentException(
          "Exact human /repair apply ID receiptHash request required");
    if (stored.status().equals("VERIFIED_CHECKS")) return view(owner, stored);
    if (!stored.status().equals("PROPOSED"))
      throw new IllegalStateException("Repair is claimed or uncertain; inspect without replay");
    long deadline = deadline();
    var current = new TestReportDiagnosisService().read(root, saved.diagnosis().path());
    if (!current.sha256().equals(saved.diagnosis().sha256()))
      throw new IllegalStateException("Failure report changed; propose again after diagnosis");
    requireFailure(current);
    var snapshot = capture.capture(root, deadline);
    if (!snapshot.complete() || !snapshot.version().equals(saved.patchVersion()))
      throw new IllegalStateException("Patch changed since diagnosis; propose again");
    var proposal = changes.inspect(project(owner, root), id);
    if (!proposal.status().equals("PROPOSED")
        || !proposal.proposalSha256().equals(saved.changeHash())
        || !Objects.equals(proposal.baselineSha256(), proposal.currentSha256()))
      throw new IllegalStateException("Repair Change Set stale, claimed or changed");
    RunCancellation.propagate(null);
    if (db.sql(
                "UPDATE diagnosed_repairs SET status='STARTED' WHERE id=? AND project=? AND status='PROPOSED'")
            .params(id, owner.projectId())
            .update()
        != 1) throw new IllegalStateException("Repair already claimed");
    try (var lease = leases.activate(id)) {
      SelfPatchRepairService.Cycle bounded =
          (path, request, budget) -> {
            var observed = capture.capture(path, budget);
            // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
            if (!observed.complete() || observed.changedFiles().size() > 32)
              throw new IOException("Repair patch exceeds 32 files or is incomplete");
            return cycle.verify(path, request, Math.min(deadline, budget));
          };
      var verifier =
          new SelfPatchRepairService(
              bounded,
              capture,
              (path, repair, budget) -> {
                SelfPatchReviewService.remaining(Math.min(deadline, budget), 1);
                var reproduced =
                    new TestReportDiagnosisService().read(path, saved.diagnosis().path());
                requireFailure(reproduced);
                if (!failureIdentities(reproduced).equals(failureIdentities(saved.diagnosis())))
                  throw new IOException("Failure identities changed; diagnose before applying");
                var receipt =
                    changes.applyDiagnosed(
                        project(owner, path), repair.id(), repair.proposalSha256(), writer);
                return new SelfPatchRepairService.Receipt(
                    receipt.id(),
                    receipt.status(),
                    receipt.proposedSha256(),
                    receipt.currentSha256());
              });
      var result =
          verifier.verify(
              root,
              new SelfPatchRepairService.Request(
                  saved.testCommand(),
                  saved.timeoutSeconds(),
                  List.of(new SelfPatchRepairService.Repair(id, saved.changeHash()))),
              deadline);
      RunCancellation.propagate(null);
      SelfPatchReviewService.remaining(deadline, 1);
      leases.heartbeat(id);
      if (db.sql(
                  "UPDATE diagnosed_repairs SET status=?,result=? WHERE id=? AND project=? AND status='STARTED'")
              .params(result.status(), encode(result), id, owner.projectId())
              .update()
          != 1) throw new IOException("Repair receipt unavailable");
      return view(owner, new Stored(saved, result.status(), result));
    } catch (IOException | RuntimeException error) {
      try {
        db.sql(
                "UPDATE diagnosed_repairs SET status='UNKNOWN' WHERE id=? AND project=? AND status='STARTED'")
            .params(id, owner.projectId())
            .update();
      } catch (RuntimeException persistence) {
        error.addSuppressed(persistence);
      }
      RunCancellation.propagate(error);
      throw error;
    }
  }

  /** 共通の実行基盤で用いる自分が所有する対象を返す。 */
  private Stored owned(AgentRunContext owner, String id) throws IOException {
    Path root = owner(owner);
    if (id == null || !id.matches("[0-9a-fA-F-]{36}"))
      throw new IllegalArgumentException("Repair UUID required");
    var stored =
        db.sql(
                "SELECT payload,status,result FROM diagnosed_repairs WHERE id=? AND project=? AND root=? AND session=?")
            .params(id, owner.projectId(), root.toString(), owner.conversationId())
            .query(
                (row, n) ->
                    new Stored(
                        decode(row.getString("payload"), Saved.class),
                        row.getString("status"),
                        row.getString("result") == null
                            ? null
                            : decode(row.getString("result"), SelfPatchRepairService.Result.class)))
            .optional()
            .orElseThrow(
                () ->
                    new IllegalArgumentException("Repair not found in this Project/root/session"));
    if (!id.equals(stored.saved().id())
        || !owner.projectId().equals(stored.saved().project())
        || !root.toString().equals(stored.saved().root())
        || !owner.conversationId().equals(stored.saved().session()))
      throw new IllegalStateException("Repair ownership receipt invalid");
    leases.reconcile(id);
    String currentStatus =
        db.sql("SELECT status FROM diagnosed_repairs WHERE id=?")
            .param(id)
            .query(String.class)
            .single();
    return new Stored(stored.saved(), currentStatus, stored.result());
  }

  /** 共通の実行基盤で用いる表示内容を返す。 */
  private View view(AgentRunContext owner, Stored stored) throws IOException {
    var saved = stored.saved();
    return new View(
        saved.id(),
        stored.status(),
        saved.receiptHash(),
        saved.diagnosis(),
        saved.patchVersion(),
        changes.inspect(project(owner, Path.of(saved.root())), saved.id()),
        saved.testCommand(),
        saved.timeoutSeconds(),
        stored.result());
  }

  /** 条件を満たさない操作を拒否するため、失敗情報の前提条件を確認する。 */
  private void requireFailure(TestReportDiagnosisService.Result diagnosis) {
    if (diagnosis.partial()
        || diagnosis.failedTests().isEmpty()
        || diagnosis.observed().failures() + diagnosis.observed().errors() == 0)
      throw new IllegalArgumentException("Complete saved failing test evidence required");
    if (diagnosis.modifiedAt().isBefore(clock.instant().minus(Duration.ofDays(7)))
        || diagnosis.modifiedAt().isAfter(clock.instant().plusSeconds(60)))
      throw new IllegalStateException("Failure report is stale or future dated");
  }

  /** 共通の実行基盤で用いる失敗情報識別情報一覧を返す。 */
  private static Set<String> failureIdentities(TestReportDiagnosisService.Result diagnosis) {
    var identities = new TreeSet<String>();
    for (var failure : diagnosis.failedTests())
      identities.add(failure.test() + "\u0000" + failure.kind());
    return identities;
  }

  /** 共通の実行基盤で用いる所有者を返す。 */
  private static Path owner(AgentRunContext owner) throws IOException {
    RunCancellation.propagate(null);
    if (owner == null
        || owner.projectId() == null
        || owner.conversationId().startsWith("subagent:"))
      throw new IllegalArgumentException("Human Project/session owner required");
    return owner.projectRoot().toRealPath();
  }

  /** 条件を満たさない操作を拒否するため、排他の前提条件を確認する。 */
  private static void requireExclusive(AgentRunContext owner) {
    RunCancellation.propagate(null);
    if (owner == null || owner.mode() != AgentRunContext.Mode.EXCLUSIVE)
      throw new IllegalArgumentException("Exclusive human Run required for repair writes");
  }

  /** 共通の実行基盤で用いるプロジェクトを返す。 */
  private static ProjectContext project(AgentRunContext owner, Path root) {
    return new ProjectContext(owner.projectId(), "", root);
  }

  /** 条件を満たさない操作を拒否するため、有効状態の前提条件を確認する。 */
  private void requireEnabled() {
    if (!enabled)
      throw new IllegalStateException(
          "Enable rei.repair.diagnosed-enabled before diagnosed repair");
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  private static void validate(Request request) {
    if (request == null
        || request.change() == null
        || request.testCommand() == null
        || request.testCommand().isBlank()
        || request.testCommand().length() > 4096
        || !CredentialRedactor.redact(request.testCommand()).equals(request.testCommand())
        || request.testCommand().codePoints().anyMatch(Character::isISOControl)
        || request.timeoutSeconds() != null
            && (request.timeoutSeconds() < 1 || request.timeoutSeconds() > 60))
      throw new IllegalArgumentException(
          "Bounded explicit repair, nonsecret test command and 1..60 seconds required");
  }

  /** 共通の実行基盤で用いる期限を返す。 */
  private static long deadline() {
    return System.nanoTime() + Duration.ofSeconds(180).toNanos();
  }

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  private String encode(Object value) {
    try {
      String encoded = json.writeValueAsString(value);
      if (encoded.getBytes(StandardCharsets.UTF_8).length > 131072)
        throw new IllegalArgumentException("Repair receipt exceeds 128KiB");
      return encoded;
    } catch (IOException invalid) {
      throw new IllegalStateException("Repair receipt unavailable", invalid);
    }
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  private <T> T decode(String value, Class<T> type) {
    try {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (value.length() > 131072) throw new IllegalStateException("Repair receipt exceeds bounds");
      return json.readValue(value, type);
    } catch (IOException invalid) {
      throw new IllegalStateException("Repair receipt unavailable", invalid);
    }
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
