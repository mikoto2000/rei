package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.CredentialRedactor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * ツール承認を保存・検索するための窓口。
 *
 * <p>Exact, expiring grants. Atomic SQL consumption happens before the side effect, never after it.
 */
@Repository
public class ToolApprovalRepository implements dev.mikoto2000.rei.secretary.SecretaryPermissions {
  @Override
  public String requestSecretaryAction(String project, String action, String canonical) {
    secretaryScope(project, action, canonical);
    String id = UUID.randomUUID().toString();
    db.sql("INSERT INTO tool_approvals VALUES(?,?,?,?,?,?,?,?,?)")
        .params(
            id,
            project,
            "secretary-action:" + id,
            id,
            "secretaryAction:" + action,
            digest(canonical),
            CredentialRedactor.redact(canonical),
            "PENDING",
            clock.instant().plus(Duration.ofMinutes(15)).toEpochMilli())
        .update();
    return id;
  }

  @Override
  public String secretaryActionStatus(String project, String id, String action, String canonical) {
    secretaryScope(project, action, canonical);
    return secretaryGrantStatus(project, id, "secretaryAction:" + action, digest(canonical));
  }

  @Override
  public boolean consumeSecretaryAction(
      String project, String id, String action, String canonical) {
    secretaryScope(project, action, canonical);
    return consumeSecretaryGrant(project, id, "secretaryAction:" + action, digest(canonical));
  }

  @Override
  public String secretaryPreparationStatus(String project, String id, String task, String rule) {
    return secretaryGrantStatus(
        project, id, "secretaryAuto:" + rule, digest(project + "\n" + task + "\n" + rule));
  }

  @Override
  public boolean consumeSecretaryPreparation(String project, String id, String task, String rule) {
    return consumeSecretaryGrant(
        project, id, "secretaryAuto:" + rule, digest(project + "\n" + task + "\n" + rule));
  }

  private static void secretaryScope(String project, String action, String canonical) {
    if (project == null
        || project.isBlank()
        || project.length() > 128
        || project.chars().anyMatch(Character::isISOControl)
        || !java.util.Set.of("DRAFT", "UPDATE_TASK", "GOAL", "ROUTINE").contains(action)
        || canonical == null
        || canonical.isBlank()
        || canonical.length() > 16384)
      throw new IllegalArgumentException("Invalid scoped secretary action");
  }

  private String secretaryGrantStatus(String project, String id, String tool, String digest) {
    return db.sql(
            "SELECT status,expires FROM tool_approvals WHERE project=? AND id=? AND tool=? AND digest=?")
        .params(project, id, tool, digest)
        .query(
            (r, n) ->
                r.getLong(2) <= clock.millis()
                        && java.util.Set.of("PENDING", "APPROVED").contains(r.getString(1))
                    ? "EXPIRED"
                    : r.getString(1))
        .optional()
        .orElse("MISSING");
  }

  private boolean consumeSecretaryGrant(String project, String id, String tool, String digest) {
    return db.sql(
                "UPDATE tool_approvals SET status='CONSUMED' WHERE project=? AND id=? AND tool=? AND digest=? AND status='APPROVED' AND expires>?")
            .params(project, id, tool, digest, clock.millis())
            .update()
        == 1;
  }

  /** Request a narrowly scoped, single-use preparation permit through the existing human UI. */
  @Override
  public String requestSecretaryPermission(String project, String task, String kind) {
    for (String value : new String[] {project, task, kind}) {
      if (value == null
          || value.isBlank()
          || value.length() > 128
          || value.chars().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Invalid secretary approval scope");
    }
    if (!java.util.Set.of(
            "OVERDUE",
            "DUE",
            "NEXT_STEP",
            "WAITING",
            "IMPORTANT",
            "NEGLECTED",
            "STALLED",
            "CONTINUATION")
        .contains(kind)) throw new IllegalArgumentException("Unknown secretary preparation kind");
    String id = UUID.randomUUID().toString();
    String scope = project + "\n" + task + "\n" + kind;
    db.sql("INSERT INTO tool_approvals VALUES(?,?,?,?,?,?,?,?,?)")
        .params(
            id,
            project,
            "secretary:" + task,
            id,
            "secretaryAuto:" + kind,
            digest(scope),
            CredentialRedactor.redact(
                "Secretary preparation only: project="
                    + project
                    + ", task="
                    + task
                    + ", kind="
                    + kind
                    + ". One execution within 15 minutes. Existing tool permissions still apply; external or destructive actions need their own approval."),
            "PENDING",
            clock.instant().plus(Duration.ofMinutes(15)).toEpochMilli())
        .update();
    return id;
  }

  /** 時間計測。 */
  private dev.mikoto2000.rei.timing.TimingRecorder timing;

  /** ツール利用の権限制御で使用する時間計測を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setTiming(dev.mikoto2000.rei.timing.TimingRecorder timing) {
    this.timing = timing;
  }

  /** 状況を判断する根拠を得るため、ツール承認保存と検索の窓口を観測する。 */
  private void observe(Request request, boolean begin, boolean approved) {
    try {
      if (timing == null || !timing.enabled()) return;
      if (begin && request.status().equals("PENDING"))
        timing.beginSpan(
            request.runId(),
            request.id(),
            request.runId(),
            null,
            null,
            dev.mikoto2000.rei.timing.TimingRecorder.Category.APPROVAL_WAIT);
      else if (!begin)
        timing.endSpan(
            request.runId(),
            request.id(),
            approved
                ? dev.mikoto2000.rei.timing.TimingRecorder.Status.SUCCESS
                : dev.mikoto2000.rei.timing.TimingRecorder.Status.FAILED);
    } catch (RuntimeException unavailable) {
      /* Metrics cannot change grants or consumption. */
    }
  }

  /**
   * ツール利用の権限制御で受け渡す要求を表す。
   *
   * @param id 識別子
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param runId 実行の識別子
   * @param tool ツール
   * @param argumentsPreview 引数事前確認
   * @param status 状態
   * @param expiresAt 有効期限
   */
  public record Request(
      String id,
      String projectId,
      String sessionId,
      String runId,
      String tool,
      String argumentsPreview,
      String status,
      Instant expiresAt) {}

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、ToolApprovalRepositoryを初期化する。 */
  public ToolApprovalRepository(
      @Qualifier("memoryConsolidationDataSource") DataSource source, Clock clock) {
    this.db = JdbcClient.create(source);
    this.clock = clock;
    db.sql(
            "CREATE TABLE IF NOT EXISTS tool_approvals(id TEXT PRIMARY KEY,project TEXT NOT NULL,session TEXT NOT NULL,run TEXT NOT NULL,tool TEXT NOT NULL,digest TEXT NOT NULL,preview TEXT NOT NULL,status TEXT NOT NULL,expires INTEGER NOT NULL,UNIQUE(project,session,run,tool,digest))")
        .update();
    db.sql(
            "CREATE INDEX IF NOT EXISTS tool_approvals_grants ON tool_approvals(project,session,tool,digest,status,expires)")
        .update();
  }

  /** 行。 */
  private static final org.springframework.jdbc.core.RowMapper<Request> ROW =
      (rs, n) ->
          new Request(
              rs.getString("id"),
              rs.getString("project"),
              rs.getString("session"),
              rs.getString("run"),
              rs.getString("tool"),
              rs.getString("preview"),
              rs.getString("status"),
              Instant.ofEpochMilli(rs.getLong("expires")));

  /** ツール利用の権限制御で用いる要求を返す。 */
  public Request request(String tool, String input, AgentRunContext owner) {
    validate(owner);
    Objects.requireNonNull(tool);
    Objects.requireNonNull(input);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (input.length() > 16384)
      throw new IllegalArgumentException(
          "Approval arguments exceed 16384 characters; split the action before requesting approval");
    String digest = actionDigest(input, owner);
    long expires = clock.instant().plus(Duration.ofMinutes(15)).toEpochMilli();
    db.sql("INSERT OR IGNORE INTO tool_approvals VALUES(?,?,?,?,?,?,?,?,?)")
        .params(
            UUID.randomUUID().toString(),
            owner.projectId(),
            owner.conversationId(),
            owner.runId(),
            tool,
            digest,
            CredentialRedactor.redact(input),
            "PENDING",
            expires)
        .update();
    var request =
        db.sql(
                "SELECT * FROM tool_approvals WHERE project=? AND session=? AND run=? AND tool=? AND digest=?")
            .params(owner.projectId(), owner.conversationId(), owner.runId(), tool, digest)
            .query(ROW)
            .single();
    observe(request, true, false);
    return request;
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<Request> list(String project) {
    return db.sql(
            "SELECT * FROM tool_approvals WHERE project=? AND status IN ('PENDING','APPROVED') AND expires>? ORDER BY expires,id LIMIT 256")
        .params(project, clock.millis())
        .query(ROW)
        .list();
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Request get(String project, String id) {
    return db.sql("SELECT * FROM tool_approvals WHERE project=? AND id=?")
        .params(project, id)
        .query(ROW)
        .optional()
        .orElseThrow(
            () -> new IllegalArgumentException("Approval request not found in this project"));
  }

  /**
   * ツール利用の権限制御で用いる判断を返す。
   *
   * <p>Only human-facing Shell/HTTP entry points call this method; it is not a model Tool.
   */
  public Request decide(String project, String id, boolean approved) {
    get(project, id);
    int changed =
        db.sql(
                "UPDATE tool_approvals SET status=? WHERE project=? AND id=? AND status='PENDING' AND expires>?")
            .params(approved ? "APPROVED" : "DENIED", project, id, clock.millis())
            .update();
    if (changed != 1) throw new IllegalStateException("Approval is expired or already decided");
    var request = get(project, id);
    observe(request, false, approved);
    return request;
  }

  /** ツール利用の権限制御において、consumeに該当するか判定する。 */
  public boolean consume(String tool, String input, AgentRunContext owner) {
    validate(owner);
    // One SQL writer operation serializes concurrent clients and processes. Never reuse on callback
    // failure.
    return db.sql(
                "UPDATE tool_approvals SET status='CONSUMED' WHERE id=(SELECT id FROM tool_approvals WHERE project=? AND session=? AND tool=? AND digest=? AND status='APPROVED' AND expires>? ORDER BY expires,id LIMIT 1) AND status='APPROVED'")
            .params(
                owner.projectId(),
                owner.conversationId(),
                tool,
                actionDigest(input, owner),
                clock.millis())
            .update()
        == 1;
  }

  /**
   * ツール利用の権限制御に関するretire継続入力許可を処理する。
   *
   * <p>A completed/uncertain continuation must not leave a reusable grant after a policy became
   * automatic.
   */
  public void retireContinuationGrant(String project, String id) {
    db.sql(
            "UPDATE tool_approvals SET status='CONSUMED' WHERE project=? AND id=? AND status='APPROVED'")
        .params(project, id)
        .update();
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  private static void validate(AgentRunContext owner) {
    if (owner == null
        || owner.projectId() == null
        || owner.projectId().isBlank()
        || owner.conversationId().isBlank())
      throw new IllegalArgumentException("Approval requires an owning project and session");
  }

  /** ツール利用の権限制御で用いる内容の照合値を返す。 */
  private static String digest(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** ツール利用の権限制御で用いる操作内容の照合値を返す。 */
  private static String actionDigest(String input, AgentRunContext owner) {
    return digest(owner.projectRoot() + "\u0000" + input);
  }
}
