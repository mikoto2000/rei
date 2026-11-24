package dev.mikoto2000.rei.llm.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.durable.DurableTaskStore;
import dev.mikoto2000.rei.review.ReviewCoverageLedger;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Only discovery metadata is stored here. Operation status remains in the original ledger. */
@Repository
public class ToolDiscoveryStore {
  public record Saved(
      RunToolAvailability.Saved availability,
      Map<String, String> definitions,
      String endpoint,
      Map<String, Set<String>> references,
      Set<String> unresolved,
      Map<String, String> requestDefinitions) {
    public Saved(
        RunToolAvailability.Saved availability,
        Map<String, String> definitions,
        String endpoint,
        Map<String, Set<String>> references,
        Set<String> unresolved) {
      this(availability, definitions, endpoint, references, unresolved, Map.of());
    }

    public Saved {
      definitions = Map.copyOf(definitions);
      var copy = new TreeMap<String, Set<String>>();
      references.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
      references = Collections.unmodifiableMap(copy);
      unresolved = Set.copyOf(unresolved);
      requestDefinitions = requestDefinitions == null ? Map.of() : Map.copyOf(requestDefinitions);
    }
  }

  private final JdbcClient db;
  private final ObjectMapper json = new ObjectMapper();
  private final ObjectProvider<ReviewCoverageLedger> reviews;
  private final ObjectProvider<DurableTaskStore> tasks;

  public ToolDiscoveryStore(
      @Qualifier("memoryConsolidationDataSource") DataSource source,
      ObjectProvider<ReviewCoverageLedger> reviews,
      ObjectProvider<DurableTaskStore> tasks) {
    this.db = JdbcClient.create(source);
    this.reviews = reviews;
    this.tasks = tasks;
    db.sql(
            "CREATE TABLE IF NOT EXISTS tool_discovery_runs(run TEXT PRIMARY KEY, project TEXT NOT"
                + " NULL, root TEXT NOT NULL, session TEXT NOT NULL, metadata TEXT NOT NULL)")
        .update();
  }

  public Optional<Saved> load(AgentRunContext owner) {
    return db.sql(
            "SELECT metadata FROM tool_discovery_runs WHERE run=? AND project=? AND root=? AND"
                + " session=?")
        .params(owner.runId(), owner.projectId(), root(owner), owner.conversationId())
        .query(String.class)
        .optional()
        .map(s -> {
          try {
            return json.readValue(s, Saved.class);
          } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Invalid saved tool availability", invalid);
          }
        });
  }

  public void save(AgentRunContext owner, Saved saved) {
    try {
      String encoded = json.writeValueAsString(saved);
      if (encoded.length() > 262144)
        throw new IllegalStateException("Tool discovery metadata exceeds limit");
      int updated =
          db.sql(
                  "INSERT INTO tool_discovery_runs(run,project,root,session,metadata)"
                      + " VALUES(?,?,?,?,?) ON CONFLICT(run) DO UPDATE SET"
                      + " metadata=excluded.metadata WHERE project=excluded.project AND"
                      + " root=excluded.root AND session=excluded.session")
              .params(
                  owner.runId(), owner.projectId(), root(owner), owner.conversationId(), encoded)
              .update();
      if (updated != 1) throw new IllegalStateException("Tool availability owner mismatch");
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("Cannot save tool availability", invalid);
    }
  }

  public void inherit(AgentRunContext next, String previousRun) {
    if (previousRun == null || load(next).isPresent()) return;
    var previous =
        new AgentRunContext(
            previousRun,
            next.conversationId(),
            next.projectRoot(),
            next.projectId(),
            next.requestSource(),
            next.mode());
    load(previous).ifPresent(saved -> save(next, saved));
  }

  private static String root(AgentRunContext owner) {
    return owner.projectRoot().toAbsolutePath().normalize().toString();
  }

  /** Read-only projections: never call an inspect method that reconciles or starts recovery. */
  public boolean required(AgentRunContext owner, String set, String id) {
    String p = owner.projectId(), r = root(owner), s = owner.conversationId();
    return switch (set) {
      case "text-change" ->
          status(
              "SELECT status FROM text_change_sets WHERE id=? AND project_id=? AND root=?",
              List.of(id, p, r),
              Set.of("APPLIED", "DISCARDED", "RECONCILED"));
      case "document-change" -> {
        var states =
            db.sql(
                    "SELECT status,phase,stages FROM text_document_change_sets WHERE id=? AND"
                        + " project=? AND root=? AND session=?")
                .params(id, p, r, s)
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional();
        yield states.isEmpty()
            || !(Set.of("APPLIED", "ROLLED_BACK", "STALE", "DISCARDED")
                    .contains(states.get().get(0))
                && ("CLEANED".equals(states.get().get(1)) || "[]".equals(states.get().get(2))));
      }
      case "diagnosed-repair" ->
          status(
              "SELECT status FROM diagnosed_repairs WHERE id=? AND project=? AND root=? AND"
                  + " session=?",
              List.of(id, p, r, s),
              Set.of("VERIFIED_CHECKS"));
      case "external-implementation" -> {
        var current =
            db.sql(
                    "SELECT status FROM implementation_requests WHERE id=? AND project=? AND root=?"
                        + " AND session=?")
                .params(id, p, r, s)
                .query(String.class)
                .optional();
        if (current.isEmpty()) yield true;
        if (Set.of("DENIED", "FAILED", "REJECTED").contains(current.get())) yield false;
        yield db.sql("SELECT count(*) FROM implementation_acceptance_evaluations WHERE request=?")
                .param(id)
                .query(Integer.class)
                .single()
            == 0;
      }
      case "external-codex-review", "external-claude-review" ->
          status(
              "SELECT status FROM external_reviews WHERE id=? AND project=? AND root=? AND"
                  + " session=?",
              List.of(id, p, r, s),
              Set.of(
                  "SUCCESS",
                  "SUCCESS_WITH_WARNINGS",
                  "FAILED",
                  "UNAVAILABLE",
                  "TOTAL_TIMEOUT",
                  "INACTIVITY_TIMEOUT",
                  "CANCELLED",
                  "REJECTED"));
      case "dependency" -> {
        boolean active =
            status(
                "SELECT state FROM agent_dependencies WHERE id=? AND project=? AND root=? AND"
                    + " session=?",
                List.of(id, p, r, s),
                Set.of("COMPLETED", "FAILED", "CANCELLED"));
        yield active
            || db.sql(
                        "SELECT count(*) FROM agent_dependency_facts WHERE dependency=? AND"
                            + " project=? AND session=? AND sent=0")
                    .params(id, p, s)
                    .query(Integer.class)
                    .single()
                > 0;
      }
      case "review-legacy" -> {
        var ledger = reviews.getIfAvailable();
        yield ledger == null
            || !"COMPLETE"
                .equals(
                    ledger.get(id, new ReviewCoverageLedger.Scope(p, s, owner.runId())).status());
      }
      case "review-durable" -> {
        var store = tasks.getIfAvailable();
        if (store == null) {
          var ledger = reviews.getIfAvailable();
          if (ledger != null) store = ledger.durableTasks();
        }
        yield store == null
            || !"DONE".equals(store.status(new DurableTaskStore.Scope(p, s, r), id).state());
      }
      case "subagent" -> {
        var payload =
            db.sql("SELECT snapshot FROM subagent_checkpoints WHERE id=?")
                .param(id)
                .query(String.class)
                .optional();
        if (payload.isEmpty()) yield true;
        try {
          var child = json.readTree(payload.get());
          if (!p.equals(child.path("project").asText())
              || !s.equals(child.path("session").asText())
              || !r.equals(child.path("root").asText()))
            throw new IllegalStateException("Child scope mismatch");
          boolean pending = false;
          for (var op : child.path("operations"))
            if (Set.of("STARTED", "UNKNOWN").contains(op.path("status").asText())) pending = true;
          yield pending
              || !Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT")
                  .contains(child.path("status").asText());
        } catch (java.io.IOException invalid) {
          throw new IllegalStateException("Invalid child ledger", invalid);
        }
      }
      default -> true;
    };
  }

  private boolean status(String sql, List<?> args, Set<String> terminal) {
    return db.sql(sql)
        .params(args)
        .query(String.class)
        .optional()
        .map(v -> !terminal.contains(v))
        .orElse(true);
  }
}
