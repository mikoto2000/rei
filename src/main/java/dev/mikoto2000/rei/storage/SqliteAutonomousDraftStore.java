package dev.mikoto2000.rei.storage;

import dev.mikoto2000.rei.autonomous.AutonomousActionContext;
import dev.mikoto2000.rei.autonomous.AutonomousActionPolicy;
import dev.mikoto2000.rei.autonomous.AutonomousActionProperties;
import dev.mikoto2000.rei.autonomous.AutonomousConflictException;
import dev.mikoto2000.rei.autonomous.AutonomousDraft;
import dev.mikoto2000.rei.autonomous.AutonomousDraftStore;
import dev.mikoto2000.rei.autonomous.AutonomousPostRecord;
import dev.mikoto2000.rei.autonomous.AutonomousPublicationStore;
import dev.mikoto2000.rei.autonomous.AutonomousTidGenerator;
import dev.mikoto2000.rei.autonomous.DefaultAutonomousActionPolicy;
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
public final class SqliteAutonomousDraftStore
    implements AutonomousDraftStore, AutonomousPublicationStore {
  private final StorageDatabase database;
  private final Clock clock;
  private final AutonomousActionProperties settings;
  private final AutonomousActionPolicy policy;
  private final AutonomousTidGenerator recordKeys;
  private final AtomicBoolean stopping = new AtomicBoolean();

  @Autowired
  public SqliteAutonomousDraftStore(
      StorageObjectRegistry registry, Clock clock, AutonomousActionProperties settings) {
    this(new StorageDatabase(registry.root()), clock, settings);
    recoverInterruptedConsiderations();
    recoverInterruptedPublications();
  }

  public SqliteAutonomousDraftStore(
      StorageDatabase database, Clock clock, AutonomousActionProperties settings) {
    this.database = Objects.requireNonNull(database);
    this.clock = Objects.requireNonNull(clock);
    this.recordKeys = new AutonomousTidGenerator(clock);
    this.settings = Objects.requireNonNull(settings);
    var limits = settings.bluesky();
    this.policy =
        new DefaultAutonomousActionPolicy(
            settings.enabled(),
            limits.enabled(),
            limits.maxPendingDrafts(),
            limits.maxDraftsPerDay(),
            limits.cooldown(),
            limits.maxRegenerations(),
            limits.regenerationCooldown());
  }

  @PreDestroy
  public void stop() {
    stopping.set(true);
  }

  public boolean isStopping() {
    return stopping.get() || Thread.currentThread().isInterrupted();
  }

  public PublishClaim beginPublication(String id, int expectedRevision, String actor) {
    if (id == null
        || id.isBlank()
        || id.length() > 256
        || expectedRevision < 1
        || actor == null
        || actor.isBlank()
        || actor.length() > 256
        || actor.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid publication review");
    publicationAdmission();
    get(id);
    return database.transaction(db -> {
      publicationAdmission();
      var current = find(db, id);
      if ((current.state() != AutonomousDraft.State.PENDING_REVIEW
              && current.state() != AutonomousDraft.State.FAILED)
          || current.content().revision() != expectedRevision
          || !clock.instant().isBefore(current.expiresAt()))
        throw new AutonomousConflictException();
      var prior = publicationJournal(db, current);
      boolean retry = current.state() == AutonomousDraft.State.FAILED;
      if (retry && prior == null) throw new AutonomousConflictException();
      if (!retry && prior != null) throw new IllegalStateException("Unexpected publication record");
      var now = clock.instant();
      var record =
          retry ? prior.record() : AutonomousPostRecord.fromDraft(current, recordKeys.next(), now);
      var token = UUID.randomUUID().toString();
      if (retry) {
        try (var sql =
            prepared(
                db,
                "UPDATE autonomous_publications SET token=?,updated=? WHERE draft_id=? AND token IS NULL",
                token,
                now.toEpochMilli(),
                id)) {
          if (sql.executeUpdate() != 1) throw new AutonomousConflictException();
        }
      } else {
        try (var sql =
            prepared(
                db,
                "INSERT INTO autonomous_publications VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,
                record.did(),
                record.recordKey(),
                expectedRevision,
                current.content().bodyHash(),
                record.recordJson(),
                record.recordHash(),
                token,
                now.toEpochMilli(),
                now.toEpochMilli())) {
          sql.executeUpdate();
        }
      }
      var changed =
          publicationState(
              current,
              AutonomousDraft.State.PUBLISHING,
              new AutonomousDraft.Approval(now, actor),
              new AutonomousDraft.Publication(
                  now, record.recordKey(), record.recordJson(), null, null, "PUBLISHING", ""),
              now);
      update(db, changed, expectedRevision, current.state());
      audit(
          db, id, "PUBLICATION_STARTED", retry ? "explicit_retry" : "selected_review", actor, now);
      return new PublishClaim(id, token, record);
    });
  }

  public AutonomousDraft finishPublication(
      PublishClaim claim, Outcome outcome, String uri, String cid, String failureCode) {
    if (claim == null
        || claim.draftId() == null
        || claim.token() == null
        || claim.record() == null
        || outcome == null
        || failureCode == null
        || !failureCode.matches("[a-z0-9_]{0,128}"))
      throw new IllegalArgumentException("Invalid publication result");
    return database.transaction(db -> {
      var current = find(db, claim.draftId());
      if (current.state() != AutonomousDraft.State.PUBLISHING)
        throw new AutonomousConflictException();
      var journal = publicationJournal(db, current);
      if (journal == null
          || !claim.token().equals(journal.token())
          || !claim.record().equals(journal.record())) throw new AutonomousConflictException();
      var finalOutcome = outcome;
      var code = failureCode;
      if (outcome == Outcome.PUBLISHED
          && (!journal.record().uri().equals(uri) || !AutonomousPostRecord.validRecordCid(cid))) {
        finalOutcome = Outcome.UNKNOWN;
        code = "invalid_record_receipt";
      }
      var now = clock.instant();
      boolean published = finalOutcome == Outcome.PUBLISHED;
      var previous = current.publication();
      var publication =
          new AutonomousDraft.Publication(
              previous.startedAt(),
              previous.recordKey(),
              previous.recordJson(),
              published ? uri : null,
              published ? cid : null,
              finalOutcome.name(),
              published ? "" : code);
      var changed =
          publicationState(
              current,
              AutonomousDraft.State.valueOf(finalOutcome.name()),
              current.approval(),
              publication,
              now);
      update(db, changed, current.content().revision(), AutonomousDraft.State.PUBLISHING);
      try (var sql =
          prepared(
              db,
              "UPDATE autonomous_publications SET token=NULL,updated=? WHERE draft_id=? AND token=?",
              now.toEpochMilli(),
              current.id(),
              claim.token())) {
        if (sql.executeUpdate() != 1) throw new AutonomousConflictException();
      }
      audit(
          db,
          current.id(),
          "PUBLICATION_FINISHED",
          finalOutcome.name(),
          current.approval().approvedBy(),
          now);
      return changed;
    });
  }

  public Optional<AutonomousPostRecord> publicationRecord(String id) {
    return database.read(db -> {
      var journal = publicationJournal(db, find(db, id));
      return journal == null ? Optional.empty() : Optional.of(journal.record());
    });
  }

  public AutonomousDraft confirmPublication(
      String id,
      int expectedRevision,
      AutonomousPostRecord expectedRecord,
      String uri,
      String cid,
      String actor) {
    if (expectedRevision < 1
        || expectedRecord == null
        || actor == null
        || actor.isBlank()
        || actor.length() > 256
        || actor.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid publication reconciliation");
    if (isStopping()) throw new AutonomousConflictException();
    return database.transaction(db -> {
      if (isStopping()) throw new AutonomousConflictException();
      var current = find(db, id);
      if (current.state() != AutonomousDraft.State.UNKNOWN
          || current.content().revision() != expectedRevision)
        throw new AutonomousConflictException();
      var journal = publicationJournal(db, current);
      if (journal == null
          || journal.token() != null
          || !expectedRecord.equals(journal.record())
          || !journal.record().uri().equals(uri)
          || !AutonomousPostRecord.validRecordCid(cid)) throw new AutonomousConflictException();
      var now = clock.instant();
      var old = current.publication();
      var publication =
          new AutonomousDraft.Publication(
              old.startedAt(), old.recordKey(), old.recordJson(), uri, cid, "PUBLISHED", "");
      var changed =
          publicationState(
              current, AutonomousDraft.State.PUBLISHED, current.approval(), publication, now);
      update(db, changed, expectedRevision, AutonomousDraft.State.UNKNOWN);
      try (var sql =
          prepared(
              db,
              "UPDATE autonomous_publications SET updated=? WHERE draft_id=? AND token IS NULL",
              now.toEpochMilli(),
              id)) {
        if (sql.executeUpdate() != 1) throw new AutonomousConflictException();
      }
      audit(db, id, "PUBLICATION_RECONCILED", "matching_record", actor, now);
      return changed;
    });
  }

  public void recoverInterruptedPublications() {
    int recovered;
    do {
      recovered =
          database.transaction(db -> {
            var interrupted = new ArrayList<AutonomousDraft>();
            try (var query = db.createStatement();
                var rows =
                    query.executeQuery(
                        "SELECT * FROM autonomous_drafts WHERE state='PUBLISHING' LIMIT 256")) {
              while (rows.next()) interrupted.add(row(rows));
            }
            for (var current : interrupted) {
              var now = clock.instant();
              var old = current.publication();
              var publication =
                  old == null
                      ? null
                      : new AutonomousDraft.Publication(
                          old.startedAt(),
                          old.recordKey(),
                          old.recordJson(),
                          null,
                          null,
                          "UNKNOWN",
                          "restart_during_publication");
              var changed =
                  publicationState(
                      current, AutonomousDraft.State.UNKNOWN, current.approval(), publication, now);
              update(db, changed, current.content().revision(), AutonomousDraft.State.PUBLISHING);
              try (var sql =
                  prepared(
                      db,
                      "UPDATE autonomous_publications SET token=NULL,updated=? WHERE draft_id=?",
                      now.toEpochMilli(),
                      current.id())) {
                sql.executeUpdate();
              }
              audit(db, current.id(), "PUBLICATION_RECOVERED", "restart_unknown", "system", now);
            }
            return interrupted.size();
          });
    } while (recovered == 256);
  }

  private void publicationAdmission() {
    if (!settings.enabled() || !settings.bluesky().enabled())
      throw new SecurityException("Autonomous publication is disabled");
    if (isStopping()) throw new AutonomousConflictException();
  }

  private record PublicationJournal(AutonomousPostRecord record, String token) {}

  private PublicationJournal publicationJournal(Connection db, AutonomousDraft draft)
      throws SQLException {
    try (var query =
            prepared(db, "SELECT * FROM autonomous_publications WHERE draft_id=?", draft.id());
        var rows = query.executeQuery()) {
      if (!rows.next()) return null;
      var record =
          new AutonomousPostRecord(
              rows.getString("target_did"),
              rows.getString("record_key"),
              rows.getString("record_json"),
              rows.getString("record_hash"));
      var token = rows.getString("token");
      var publication = draft.publication();
      if (!record.did().equals(draft.target().did())
          || rows.getInt("revision") != draft.content().revision()
          || !rows.getString("body_hash").equals(draft.content().bodyHash())
          || !record.text().equals(draft.content().text())
          || publication == null
          || !record.recordKey().equals(publication.recordKey())
          || !record.recordJson().equals(publication.recordJson())
          || (draft.state() == AutonomousDraft.State.PUBLISHING) != (token != null)
          || rows.getLong("updated") != draft.updatedAt().toEpochMilli())
        throw new IllegalStateException("Inconsistent publication journal");
      return new PublicationJournal(record, token);
    }
  }

  private static AutonomousDraft publicationState(
      AutonomousDraft current,
      AutonomousDraft.State state,
      AutonomousDraft.Approval approval,
      AutonomousDraft.Publication publication,
      Instant now) {
    return new AutonomousDraft(
        current.id(),
        current.actionType(),
        current.opportunityId(),
        current.trigger(),
        current.createdAt(),
        now,
        current.target(),
        current.content(),
        state,
        current.expiresAt(),
        current.rejectionReason(),
        current.rejectionReasonPublic(),
        current.regenerationAttempts(),
        current.lastRegenerationAttemptAt(),
        approval,
        publication);
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
          if (sql.executeUpdate() != 1) throw new AutonomousConflictException();
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
    validateOpportunityId(opportunityId);
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
      if (!"NEW".equals(held.kind()) || held.draftId() != null || held.expectedRevision() != null)
        throw new AutonomousConflictException();
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

  public Consideration reserveRegeneration(
      String opportunityId,
      String draftId,
      int expectedRevision,
      String targetDid,
      String targetHandle) {
    validateOpportunityId(opportunityId);
    get(draftId);
    return database.transaction(db -> {
      if (scalar(db, "SELECT COUNT(*) FROM autonomous_opportunities WHERE id=?", opportunityId) > 0)
        return new Consideration(
            new AutonomousActionPolicy.ActionDecision(false, "duplicate_opportunity"), null);
      var now = clock.instant();
      expire(db, now);
      var current = pending(db, draftId, expectedRevision);
      boolean targetMatches =
          current.target().did().equals(targetDid)
              && current.target().handle().equals(targetHandle);
      var decision =
          policy.evaluate(
              facts(
                  db,
                  now,
                  targetDid,
                  targetHandle,
                  null,
                  regenerationFacts(db, draftId, null, targetMatches)));
      var token = decision.shouldConsider() ? UUID.randomUUID().toString() : null;
      try (var insert =
          prepared(
              db,
              """
              INSERT INTO autonomous_opportunities
              (id,trigger,kind,status,token,created,updated,considered,zone,target_did,target_handle,
               reason,draft_id,expected_revision)
              VALUES(?, 'MANUAL', 'REGENERATE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              """,
              opportunityId,
              decision.shouldConsider() ? "RUNNING" : "POLICY_SKIPPED",
              token,
              now.toEpochMilli(),
              now.toEpochMilli(),
              decision.shouldConsider() ? 1 : 0,
              zone().getId(),
              targetDid == null ? "" : targetDid,
              targetHandle == null ? "" : targetHandle,
              decision.reason(),
              draftId,
              expectedRevision)) {
        insert.executeUpdate();
      }
      if (decision.shouldConsider()) {
        update(db, current.regenerationAttempted(now), expectedRevision);
        audit(db, draftId, "REGENERATION_STARTED", "attempt_reserved", now);
      }
      audit(db, opportunityId, "OPPORTUNITY", decision.reason(), now);
      return new Consideration(
          decision, decision.shouldConsider() ? new Claim(opportunityId, token) : null);
    });
  }

  public Optional<AutonomousDraft> completeRegeneration(
      Claim claim, String text, String reason, List<String> sourceIds) {
    return database.transaction(db -> {
      var held = held(db, claim);
      if (!"REGENERATE".equals(held.kind())
          || held.draftId() == null
          || held.expectedRevision() == null) throw new AutonomousConflictException();
      var now = clock.instant();
      expire(db, now);
      var current = find(db, held.draftId());
      if (current.state() != AutonomousDraft.State.PENDING_REVIEW
          || current.content().revision() != held.expectedRevision()
          || !now.isBefore(current.expiresAt())) {
        finish(db, claim, "ABORTED", "draft_changed", null, now);
        return Optional.empty();
      }
      var decision =
          policy.evaluate(
              facts(
                  db,
                  now,
                  held.target().did(),
                  held.target().handle(),
                  claim.opportunityId(),
                  regenerationFacts(
                      db,
                      held.draftId(),
                      claim.opportunityId(),
                      current.target().equals(held.target()))));
      if (!decision.shouldConsider()) {
        finish(db, claim, "ABORTED", decision.reason(), null, now);
        return Optional.empty();
      }
      var content =
          AutonomousDraft.Content.create(
              Math.incrementExact(held.expectedRevision()), text, reason, sourceIds);
      var changed = current.edited(content, now);
      update(db, changed, held.expectedRevision());
      revision(db, changed);
      finish(db, claim, "GENERATED", "regenerate_post", changed.id(), now);
      audit(db, changed.id(), "REGENERATED", "new_revision", now);
      return Optional.of(changed);
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
    return facts(db, now, did, handle, excluding, null);
  }

  private AutonomousActionContext facts(
      Connection db,
      Instant now,
      String did,
      String handle,
      String excluding,
      AutonomousActionContext.Regeneration regeneration)
      throws SQLException {
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
        attempted < 0 ? null : Instant.ofEpochMilli(attempted),
        regeneration);
  }

  private AutonomousActionContext.Regeneration regenerationFacts(
      Connection db, String draftId, String excluding, boolean targetMatches) throws SQLException {
    int attempts =
        Math.toIntExact(
            scalar(
                db,
                """
                SELECT COUNT(*) FROM autonomous_opportunities
                WHERE kind='REGENERATE' AND considered=1 AND draft_id=? AND (? IS NULL OR id<>?)
                """,
                draftId,
                excluding,
                excluding));
    long attempted =
        scalar(
            db,
            """
            SELECT COALESCE(MAX(created),-1) FROM autonomous_opportunities
            WHERE kind='REGENERATE' AND considered=1 AND draft_id=? AND (? IS NULL OR id<>?)
            """,
            draftId,
            excluding,
            excluding);
    return new AutonomousActionContext.Regeneration(
        attempts, attempted < 0 ? null : Instant.ofEpochMilli(attempted), targetMatches);
  }

  private static void validateOpportunityId(String opportunityId) {
    if (opportunityId == null || !opportunityId.matches("[A-Za-z0-9][A-Za-z0-9:_./-]{0,127}"))
      throw new IllegalArgumentException("Invalid opportunity identifier");
  }

  private record Held(
      AutonomousDraft.Trigger trigger,
      AutonomousDraft.Target target,
      String kind,
      String draftId,
      Integer expectedRevision) {}

  private Held held(Connection db, Claim claim) throws SQLException {
    if (claim == null || claim.token() == null) throw new AutonomousConflictException();
    try (var query =
            prepared(
                db,
                "SELECT trigger,target_did,target_handle,kind,draft_id,expected_revision FROM autonomous_opportunities WHERE id=? AND token=? AND status='RUNNING'",
                claim.opportunityId(),
                claim.token());
        var rows = query.executeQuery()) {
      if (!rows.next()) throw new AutonomousConflictException();
      return new Held(
          AutonomousDraft.Trigger.valueOf(rows.getString(1)),
          new AutonomousDraft.Target(rows.getString(2), rows.getString(3)),
          rows.getString(4),
          rows.getString(5),
          rows.getObject(6) == null ? null : rows.getInt(6));
    }
  }

  private void finish(
      Connection db, Claim claim, String state, String reason, String draft, Instant now)
      throws SQLException {
    try (var sql =
        prepared(
            db,
            "UPDATE autonomous_opportunities SET status=?,token=NULL,reason=?,draft_id=COALESCE(?,draft_id),updated=? WHERE id=? AND token=? AND status='RUNNING'",
            state,
            reason,
            draft,
            now.toEpochMilli(),
            claim.opportunityId(),
            claim.token())) {
      if (sql.executeUpdate() != 1) throw new AutonomousConflictException();
    }
    audit(db, claim.opportunityId(), "CONSIDERATION_FINISHED", state, now);
  }

  private AutonomousDraft pending(Connection db, String id, int expected)
      throws SQLException, IOException {
    var draft = find(db, id);
    if (draft.state() != AutonomousDraft.State.PENDING_REVIEW
        || draft.content().revision() != expected
        || !clock.instant().isBefore(draft.expiresAt())) throw new AutonomousConflictException();
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
    update(db, draft, expected, AutonomousDraft.State.PENDING_REVIEW);
  }

  private void update(
      Connection db, AutonomousDraft draft, int expected, AutonomousDraft.State prior)
      throws SQLException, IOException {
    try (var sql =
        prepared(
            db,
            "UPDATE autonomous_drafts SET state=?,revision=?,updated=?,body_hash=?,snapshot=? WHERE id=? AND revision=? AND state=?",
            draft.state().name(),
            draft.content().revision(),
            draft.updatedAt().toEpochMilli(),
            draft.content().bodyHash(),
            StorageDatabase.JSON.writeValueAsString(draft),
            draft.id(),
            expected,
            prior.name())) {
      if (sql.executeUpdate() != 1) throw new AutonomousConflictException();
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
    audit(db, subject, operation, reason, null, now);
  }

  private void audit(
      Connection db, String subject, String operation, String reason, String actor, Instant now)
      throws SQLException {
    try (var sql =
        prepared(
            db,
            "INSERT INTO autonomous_audit(id,subject,operation,reason_code,actor,created) VALUES(?,?,?,?,?,?)",
            UUID.randomUUID().toString(),
            subject,
            operation,
            reason,
            actor,
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
