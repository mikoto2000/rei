package dev.mikoto2000.rei.autonomous;

import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageObjectRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Durable reservations and immutable review revisions. No model, tool, or external transport. */
@Component
@DependsOn("storageMigrationLease")
public final class AutonomousDraftStore {
  public record Claim(String opportunityId, String token) {}

  public record Consideration(AutonomousActionPolicy.ActionDecision decision, Claim claim) {}

  private final StorageDatabase database;
  private final Clock clock;
  private final AutonomousActionProperties settings;
  private final AutonomousActionPolicy policy;
  private final AtomicBoolean stopping = new AtomicBoolean();

  @Autowired
  public AutonomousDraftStore(
      StorageObjectRegistry registry, Clock clock, AutonomousActionProperties settings) {
    this(new StorageDatabase(registry.root()), clock, settings);
    recoverInterruptedConsiderations();
  }

  public AutonomousDraftStore(
      StorageDatabase database, Clock clock, AutonomousActionProperties settings) {
    this.database = Objects.requireNonNull(database);
    this.clock = Objects.requireNonNull(clock);
    this.settings = Objects.requireNonNull(settings);
    var limits = settings.bluesky();
    this.policy =
        new DefaultAutonomousActionPolicy(
            settings.enabled(),
            limits.enabled(),
            limits.maxPendingDrafts(),
            limits.maxDraftsPerDay(),
            limits.cooldown());
  }

  @PreDestroy
  public void stop() {
    stopping.set(true);
  }

  public boolean isStopping() {
    return stopping.get() || Thread.currentThread().isInterrupted();
  }

  /** Startup recovery; standalone test repositories do not automatically abandon live claims. */
  public void recoverInterruptedConsiderations() {
    database.transaction(db -> {
      var interrupted = new ArrayList<String>();
      try (var query = db.createStatement();
          var rows =
              query.executeQuery(
                  "SELECT id FROM autonomous_opportunities WHERE status='RUNNING'")) {
        while (rows.next()) interrupted.add(rows.getString(1));
      }
      for (var id : interrupted) {
        var now = clock.instant();
        try (var sql =
            prepared(
                db,
                "UPDATE autonomous_opportunities SET status='ABORTED',token=NULL,reason='restart',updated=? WHERE id=? AND status='RUNNING'",
                now.toEpochMilli(),
                id)) {
          if (sql.executeUpdate() != 1) throw new OperationConflictException();
        }
        audit(db, id, "RESTART_RECOVERY", "aborted", now);
      }
      return null;
    });
  }

  public Consideration reserve(
      String opportunityId,
      AutonomousDraft.Trigger trigger,
      String targetDid,
      String targetHandle) {
    if (opportunityId == null || !opportunityId.matches("[A-Za-z0-9][A-Za-z0-9:_./-]{0,127}"))
      throw new IllegalArgumentException("Invalid opportunity identifier");
    Objects.requireNonNull(trigger);
    return database.transaction(db -> {
      var now = clock.instant();
      try (var find =
              prepared(db, "SELECT id FROM autonomous_opportunities WHERE id=?", opportunityId);
          var rows = find.executeQuery()) {
        if (rows.next())
          return new Consideration(
              new AutonomousActionPolicy.ActionDecision(false, "duplicate_opportunity"), null);
      }
      expire(db, now);
      var decision = policy.evaluate(facts(db, now, targetDid, targetHandle, null));
      var token = decision.shouldConsider() ? UUID.randomUUID().toString() : null;
      if (decision.shouldConsider()) new AutonomousDraft.Target(targetDid, targetHandle);
      try (var insert =
          prepared(
              db,
              """
              INSERT INTO autonomous_opportunities
              (id,trigger,kind,status,token,created,updated,considered,zone,target_did,target_handle,reason)
              VALUES(?,?,'NEW',?,?,?,?,?,?,?,?,?)
              """,
              opportunityId,
              trigger.name(),
              decision.shouldConsider() ? "RUNNING" : "POLICY_SKIPPED",
              token,
              now.toEpochMilli(),
              now.toEpochMilli(),
              decision.shouldConsider() ? 1 : 0,
              zone().getId(),
              targetDid == null ? "" : targetDid,
              targetHandle == null ? "" : targetHandle,
              decision.reason())) {
        insert.executeUpdate();
      }
      audit(db, opportunityId, "OPPORTUNITY", decision.reason(), now);
      return new Consideration(
          decision, decision.shouldConsider() ? new Claim(opportunityId, token) : null);
    });
  }

  public Optional<AutonomousDraft> complete(
      Claim claim, String text, String reason, List<String> sourceIds) {
    var content = AutonomousDraft.Content.create(1, text, reason, sourceIds);
    return database.transaction(db -> {
      var held = held(db, claim);
      var now = clock.instant();
      expire(db, now);
      var decision =
          policy.evaluate(
              facts(db, now, held.target().did(), held.target().handle(), claim.opportunityId()));
      if (!decision.shouldConsider()) {
        finish(db, claim, "ABORTED", decision.reason(), null, now);
        return Optional.empty();
      }
      var draft =
          new AutonomousDraft(
              UUID.randomUUID().toString(),
              AutonomousDraft.ActionType.BLUESKY_POST,
              claim.opportunityId(),
              held.trigger(),
              now,
              now,
              held.target(),
              content,
              AutonomousDraft.State.PENDING_REVIEW,
              now.plus(settings.bluesky().draftExpiration()),
              "",
              false,
              0,
              null,
              null,
              null);
      insert(db, draft);
      revision(db, draft);
      finish(db, claim, "GENERATED", "propose_post", draft.id(), now);
      audit(db, draft.id(), "DRAFT_CREATED", "pending_review", now);
      return Optional.of(draft);
    });
  }

  public void skip(Claim claim, String reason) {
    if (reason == null || reason.length() > 2048)
      throw new IllegalArgumentException("Invalid skip reason");
    database.transaction(db -> {
      held(db, claim);
      finish(db, claim, "SKIPPED", reason, null, clock.instant());
      return null;
    });
  }

  public void fail(Claim claim) {
    database.transaction(db -> {
      held(db, claim);
      finish(db, claim, "FAILED", "generation_failed", null, clock.instant());
      return null;
    });
  }

  public AutonomousDraft get(String id) {
    return database.transaction(db -> expireIfDue(db, find(db, id), clock.instant()));
  }

  public List<AutonomousDraft> list(int limit) {
    if (limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid draft page size");
    return database.transaction(db -> {
      var now = clock.instant();
      expire(db, now);
      var result = new ArrayList<AutonomousDraft>();
      try (var query =
              prepared(
                  db, "SELECT * FROM autonomous_drafts ORDER BY created DESC,id LIMIT ?", limit);
          var rows = query.executeQuery()) {
        while (rows.next()) result.add(row(rows));
      }
      for (int index = 0; index < result.size(); index++)
        result.set(index, expireIfDue(db, result.get(index), now));
      return List.copyOf(result);
    });
  }

  public AutonomousDraft edit(String id, int expectedRevision, String text) {
    get(id);
    return database.transaction(db -> {
      var current = pending(db, id, expectedRevision);
      var revised =
          AutonomousDraft.Content.create(
              Math.incrementExact(expectedRevision),
              text,
              current.content().reason(),
              current.content().sourceIds());
      var changed = current.edited(revised, clock.instant());
      update(db, changed, expectedRevision);
      revision(db, changed);
      audit(db, id, "EDITED", "new_revision", clock.instant());
      return changed;
    });
  }

  public AutonomousDraft reject(String id, int expectedRevision, String reason) {
    if (reason == null || reason.length() > 2048)
      throw new IllegalArgumentException("Invalid rejection reason");
    get(id);
    return database.transaction(db -> {
      var current = pending(db, id, expectedRevision);
      var changed = current.reviewed(AutonomousDraft.State.REJECTED, reason, clock.instant());
      update(db, changed, expectedRevision);
      audit(db, id, "REJECTED", "human_review", clock.instant());
      return changed;
    });
  }

  public List<AutonomousDraft.Content> revisions(String id) {
    return database.read(db -> {
      var result = new ArrayList<AutonomousDraft.Content>();
      try (var query =
              prepared(
                  db,
                  "SELECT snapshot FROM autonomous_draft_revisions WHERE draft_id=? ORDER BY revision",
                  id);
          var rows = query.executeQuery()) {
        while (rows.next())
          result.add(
              StorageDatabase.JSON.readValue(rows.getString(1), AutonomousDraft.Content.class));
      }
      return List.copyOf(result);
    });
  }

  private ZoneId zone() {
    return settings.bluesky().zone().isBlank()
        ? clock.getZone()
        : ZoneId.of(settings.bluesky().zone());
  }

  private AutonomousActionContext facts(
      Connection db, Instant now, String did, String handle, String excluding) throws SQLException {
    var day = now.atZone(zone()).toLocalDate();
    long begin = day.atStartOfDay(zone()).toInstant().toEpochMilli();
    long end = day.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli();
    int pending =
        Math.toIntExact(
            scalar(
                db,
                "SELECT COUNT(*) FROM autonomous_drafts WHERE state='PENDING_REVIEW' AND expires>?",
                now.toEpochMilli()));
    int generated =
        Math.toIntExact(
            scalar(
                db,
                "SELECT COUNT(*) FROM autonomous_drafts WHERE created>=? AND created<?",
                begin,
                end));
    boolean active =
        scalar(
                db,
                "SELECT COUNT(*) FROM autonomous_opportunities WHERE status='RUNNING' AND (? IS NULL OR id<>?)",
                excluding,
                excluding)
            > 0;
    long attempted =
        scalar(
            db,
            "SELECT COALESCE(MAX(created),-1) FROM autonomous_opportunities WHERE considered=1 AND (? IS NULL OR id<>?)",
            excluding,
            excluding);
    return new AutonomousActionContext(
        now,
        did,
        handle,
        isStopping(),
        active,
        pending,
        generated,
        attempted < 0 ? null : Instant.ofEpochMilli(attempted));
  }

  private record Held(AutonomousDraft.Trigger trigger, AutonomousDraft.Target target) {}

  private Held held(Connection db, Claim claim) throws SQLException {
    if (claim == null || claim.token() == null) throw new OperationConflictException();
    try (var query =
            prepared(
                db,
                "SELECT trigger,target_did,target_handle FROM autonomous_opportunities WHERE id=? AND token=? AND status='RUNNING'",
                claim.opportunityId(),
                claim.token());
        var rows = query.executeQuery()) {
      if (!rows.next()) throw new OperationConflictException();
      return new Held(
          AutonomousDraft.Trigger.valueOf(rows.getString(1)),
          new AutonomousDraft.Target(rows.getString(2), rows.getString(3)));
    }
  }

  private void finish(
      Connection db, Claim claim, String state, String reason, String draft, Instant now)
      throws SQLException {
    try (var sql =
        prepared(
            db,
            "UPDATE autonomous_opportunities SET status=?,token=NULL,reason=?,draft_id=?,updated=? WHERE id=? AND token=? AND status='RUNNING'",
            state,
            reason,
            draft,
            now.toEpochMilli(),
            claim.opportunityId(),
            claim.token())) {
      if (sql.executeUpdate() != 1) throw new OperationConflictException();
    }
    audit(db, claim.opportunityId(), "CONSIDERATION_FINISHED", state, now);
  }

  private AutonomousDraft pending(Connection db, String id, int expected)
      throws SQLException, IOException {
    var draft = find(db, id);
    if (draft.state() != AutonomousDraft.State.PENDING_REVIEW
        || draft.content().revision() != expected
        || !clock.instant().isBefore(draft.expiresAt())) throw new OperationConflictException();
    return draft;
  }

  private AutonomousDraft find(Connection db, String id) throws SQLException, IOException {
    try (var query = prepared(db, "SELECT * FROM autonomous_drafts WHERE id=?", id);
        var rows = query.executeQuery()) {
      if (!rows.next()) throw new NoSuchElementException("Draft not found");
      return row(rows);
    }
  }

  private AutonomousDraft row(ResultSet rows) throws SQLException, IOException {
    var draft = StorageDatabase.JSON.readValue(rows.getString("snapshot"), AutonomousDraft.class);
    if (!draft.id().equals(rows.getString("id"))
        || !draft.state().name().equals(rows.getString("state"))
        || draft.content().revision() != rows.getInt("revision")
        || !draft.content().bodyHash().equals(rows.getString("body_hash"))
        || !draft.opportunityId().equals(rows.getString("opportunity"))
        || !draft.target().did().equals(rows.getString("target_did"))
        || draft.createdAt().toEpochMilli() != rows.getLong("created")
        || draft.updatedAt().toEpochMilli() != rows.getLong("updated")
        || draft.expiresAt().toEpochMilli() != rows.getLong("expires"))
      throw new IllegalStateException("Inconsistent autonomous draft snapshot");
    return draft;
  }

  private void insert(Connection db, AutonomousDraft draft) throws SQLException, IOException {
    try (var sql =
        prepared(
            db,
            "INSERT INTO autonomous_drafts VALUES(?,?,?,?,?,?,?,?,?,?)",
            draft.id(),
            draft.opportunityId(),
            draft.state().name(),
            draft.content().revision(),
            draft.createdAt().toEpochMilli(),
            draft.updatedAt().toEpochMilli(),
            draft.expiresAt().toEpochMilli(),
            draft.target().did(),
            draft.content().bodyHash(),
            StorageDatabase.JSON.writeValueAsString(draft))) {
      sql.executeUpdate();
    }
  }

  private void update(Connection db, AutonomousDraft draft, int expected)
      throws SQLException, IOException {
    try (var sql =
        prepared(
            db,
            "UPDATE autonomous_drafts SET state=?,revision=?,updated=?,body_hash=?,snapshot=? WHERE id=? AND revision=? AND state='PENDING_REVIEW'",
            draft.state().name(),
            draft.content().revision(),
            draft.updatedAt().toEpochMilli(),
            draft.content().bodyHash(),
            StorageDatabase.JSON.writeValueAsString(draft),
            draft.id(),
            expected)) {
      if (sql.executeUpdate() != 1) throw new OperationConflictException();
    }
  }

  private void revision(Connection db, AutonomousDraft draft) throws SQLException, IOException {
    try (var sql =
        prepared(
            db,
            "INSERT INTO autonomous_draft_revisions VALUES(?,?,?,?)",
            draft.id(),
            draft.content().revision(),
            draft.updatedAt().toEpochMilli(),
            StorageDatabase.JSON.writeValueAsString(draft.content()))) {
      sql.executeUpdate();
    }
  }

  private void expire(Connection db, Instant now) throws SQLException, IOException {
    var candidates = new ArrayList<AutonomousDraft>();
    try (var query =
            prepared(
                db,
                "SELECT * FROM autonomous_drafts WHERE state='PENDING_REVIEW' AND expires<=? ORDER BY expires LIMIT 256",
                now.toEpochMilli());
        var rows = query.executeQuery()) {
      while (rows.next()) candidates.add(row(rows));
    }
    for (var draft : candidates) expireIfDue(db, draft, now);
  }

  private AutonomousDraft expireIfDue(Connection db, AutonomousDraft draft, Instant now)
      throws SQLException, IOException {
    if (draft.state() != AutonomousDraft.State.PENDING_REVIEW || now.isBefore(draft.expiresAt()))
      return draft;
    var expired = draft.reviewed(AutonomousDraft.State.EXPIRED, draft.rejectionReason(), now);
    update(db, expired, draft.content().revision());
    audit(db, draft.id(), "EXPIRED", "review_deadline", now);
    return expired;
  }

  private void audit(Connection db, String subject, String operation, String reason, Instant now)
      throws SQLException {
    try (var sql =
        prepared(
            db,
            "INSERT INTO autonomous_audit(id,subject,operation,reason_code,created) VALUES(?,?,?,?,?)",
            UUID.randomUUID().toString(),
            subject,
            operation,
            reason,
            now.toEpochMilli())) {
      sql.executeUpdate();
    }
  }

  private static long scalar(Connection db, String sql, Object... args) throws SQLException {
    try (var query = prepared(db, sql, args);
        var rows = query.executeQuery()) {
      if (!rows.next()) throw new SQLException("Missing autonomous policy facts");
      return rows.getLong(1);
    }
  }

  private static PreparedStatement prepared(Connection db, String sql, Object... args)
      throws SQLException {
    var query = db.prepareStatement(sql);
    try {
      for (int i = 0; i < args.length; i++) query.setObject(i + 1, args[i]);
      return query;
    } catch (SQLException | RuntimeException failure) {
      try {
        query.close();
      } catch (SQLException close) {
        failure.addSuppressed(close);
      }
      throw failure;
    }
  }
}
