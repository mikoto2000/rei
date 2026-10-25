package dev.mikoto2000.rei.secretary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Transactional task/source/history ledger; no remote access or model invocation. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SecretaryLedger {
  private final SecretaryDatabase database;
  private final Clock clock;

  public SecretaryLedger(SecretaryDatabase database, Clock clock) {
    this.database = database;
    this.clock = clock;
  }

  /** Inspect only the new message, never the conversation transcript. All results need approval. */
  public List<SecretaryTask> extract(String project, String event, String text) {
    required(project, 128);
    required(event, 512);
    required(text, 32768);
    String fingerprint = hash(text);
    return database.transaction(
        db -> {
          input(db, project, event, fingerprint);
          var result = new ArrayList<SecretaryTask>();
          String[] sentences = text.split("[。！？!?\\r\\n]+");
          for (int i = 0; i < sentences.length && result.size() < 16; i++) {
            String sentence = sentences[i].strip();
            if (sentence.length() > 1000 || !actionable(sentence)) continue;
            var intent =
                sentence.matches("(?i).*(したい|進めたい|やりたい|できたら|あたり|someday|would like).*")
                    ? SecretaryTask.Intent.IDEA
                    : sentence.matches(".*(約束|必ず|します|will|promise).*")
                        ? SecretaryTask.Intent.COMMITMENT
                        : SecretaryTask.Intent.TODO;
            result.add(
                register(
                    db,
                    project,
                    event,
                    i,
                    fingerprint,
                    sentence,
                    intent,
                    SecretaryTask.Status.CANDIDATE,
                    null,
                    3,
                    List.of(),
                    List.of(),
                    "CONVERSATION"));
          }
          return List.copyOf(result);
        });
  }

  private static boolean actionable(String text) {
    if (text.matches(".*(しない|不要|取り消|取消|したくない|don't|do not|cancel).*$")) return false;
    return text.matches(
        "(?i).*(したい|進めたい|やりたい|する予定|約束|必ず|します|TODO|need to|will |promise |would like|someday).*");
  }

  public SecretaryTask create(
      String project,
      String key,
      String title,
      SecretaryTask.Intent intent,
      LocalDate due,
      int priority,
      List<String> tags,
      List<String> references) {
    validate(project, title, intent, priority, tags, references);
    required(key, 512);
    String fingerprint =
        hash(
            SecretaryJson.JSON
                .valueToTree(
                    List.of(
                        title,
                        intent.name(),
                        due == null ? "" : due.toString(),
                        priority,
                        tags,
                        references))
                .toString());
    return database.transaction(
        db -> {
          input(db, project, key, fingerprint);
          return register(
              db,
              project,
              key,
              0,
              fingerprint,
              title.strip(),
              intent,
              SecretaryTask.Status.OPEN,
              due,
              priority,
              tags,
              references,
              "EXPLICIT");
        });
  }

  private void input(Connection db, String project, String event, String fingerprint)
      throws java.sql.SQLException {
    try (var insert = db.prepareStatement("INSERT OR IGNORE INTO secretary_inputs VALUES(?,?,?)")) {
      insert.setString(1, project);
      insert.setString(2, event);
      insert.setString(3, fingerprint);
      insert.executeUpdate();
    }
    try (var query =
        db.prepareStatement(
            "SELECT fingerprint FROM secretary_inputs WHERE project=? AND event=?")) {
      query.setString(1, project);
      query.setString(2, event);
      try (var rows = query.executeQuery()) {
        if (!rows.next() || !fingerprint.equals(rows.getString(1)))
          throw new IllegalArgumentException("Source key already used with different content");
      }
    }
  }

  private SecretaryTask register(
      Connection db,
      String project,
      String event,
      int ordinal,
      String fingerprint,
      String title,
      SecretaryTask.Intent intent,
      SecretaryTask.Status status,
      LocalDate due,
      int priority,
      List<String> tags,
      List<String> references,
      String origin)
      throws java.sql.SQLException, com.fasterxml.jackson.core.JsonProcessingException {
    try (var query =
        db.prepareStatement(
            "SELECT task FROM secretary_sources WHERE project=? AND event=? AND ordinal=?")) {
      query.setString(1, project);
      query.setString(2, event);
      query.setInt(3, ordinal);
      try (var rows = query.executeQuery()) {
        if (rows.next()) return get(db, project, rows.getString(1));
      }
    }
    SecretaryTask task = null;
    // Extracted duplicates may add evidence to an existing task; explicit creates retain all
    // metadata.
    if (status == SecretaryTask.Status.CANDIDATE) {
      try (var query =
          db.prepareStatement(
              "SELECT record FROM secretary_tasks WHERE project=? AND title_key=? AND status IN ('CANDIDATE','OPEN','WAITING') ORDER BY id LIMIT 1")) {
        query.setString(1, project);
        query.setString(2, normalize(title));
        try (var rows = query.executeQuery()) {
          if (rows.next()) task = decode(rows.getString(1));
        }
      }
    }
    if (task == null) {
      task =
          new SecretaryTask(
              UUID.randomUUID().toString(),
              project,
              title,
              intent,
              status,
              due,
              priority,
              tags,
              references,
              origin,
              0,
              clock.instant(),
              clock.instant());
      save(db, task, null);
    }
    try (var insert = db.prepareStatement("INSERT INTO secretary_sources VALUES(?,?,?,?,?)")) {
      insert.setString(1, project);
      insert.setString(2, event);
      insert.setInt(3, ordinal);
      insert.setString(4, fingerprint);
      insert.setString(5, task.id());
      insert.executeUpdate();
    }
    return task;
  }

  public SecretaryTask get(String project, String id) {
    required(project, 128);
    required(id, 128);
    try {
      return database.read(db -> get(db, project, id));
    } catch (IllegalStateException failure) {
      if (failure.getCause() instanceof IllegalArgumentException invalid) throw invalid;
      throw failure;
    }
  }

  private SecretaryTask get(Connection db, String project, String id)
      throws java.sql.SQLException, com.fasterxml.jackson.core.JsonProcessingException {
    try (var query =
        db.prepareStatement("SELECT record FROM secretary_tasks WHERE project=? AND id=?")) {
      query.setString(1, project);
      query.setString(2, id);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) throw new IllegalArgumentException("Secretary task not found in project");
        return decode(rows.getString(1));
      }
    }
  }

  public List<SecretaryTask> list(String project, int offset, int limit) {
    required(project, 128);
    if (offset < 0 || limit < 1 || limit > 1000)
      throw new IllegalArgumentException("Invalid page bounds");
    return database.read(
        db -> {
          try (var query =
              db.prepareStatement(
                  "SELECT record FROM secretary_tasks WHERE project=? ORDER BY priority,id LIMIT ? OFFSET ?")) {
            query.setString(1, project);
            query.setInt(2, limit);
            query.setInt(3, offset);
            var result = new ArrayList<SecretaryTask>();
            try (var rows = query.executeQuery()) {
              while (rows.next()) result.add(decode(rows.getString(1)));
            }
            return List.copyOf(result);
          }
        });
  }

  public SecretaryTask transition(
      String project, String id, long revision, SecretaryTask.Status status) {
    var current = get(project, id);
    return update(
        project,
        id,
        revision,
        current.title(),
        current.intent(),
        status,
        current.due(),
        current.priority(),
        current.tags(),
        current.references());
  }

  public SecretaryTask update(
      String project,
      String id,
      long revision,
      String title,
      SecretaryTask.Intent intent,
      SecretaryTask.Status status,
      LocalDate due,
      int priority,
      List<String> tags,
      List<String> references) {
    validate(project, title, intent, priority, tags, references);
    if (status == null || revision < 0)
      throw new IllegalArgumentException("Status and revision required");
    return database.transaction(
        db -> {
          var current = get(db, project, id);
          if (current.revision() != revision) throw new SecretaryConflictException();
          if (current.status() == SecretaryTask.Status.DONE
              || current.status() == SecretaryTask.Status.CANCELLED)
            throw new IllegalArgumentException("Terminal task cannot be changed");
          if (current.status() == SecretaryTask.Status.CANDIDATE
              && status != SecretaryTask.Status.CANDIDATE
              && status != SecretaryTask.Status.OPEN
              && status != SecretaryTask.Status.CANCELLED)
            throw new IllegalArgumentException("Approve candidate before progressing");
          if (current.status() != SecretaryTask.Status.CANDIDATE
              && status == SecretaryTask.Status.CANDIDATE)
            throw new IllegalArgumentException("Confirmed task cannot become a candidate");
          var updated =
              new SecretaryTask(
                  id,
                  project,
                  title.strip(),
                  intent,
                  status,
                  due,
                  priority,
                  tags,
                  references,
                  current.origin(),
                  revision + 1,
                  current.created(),
                  clock.instant());
          save(db, updated, revision);
          return updated;
        });
  }

  private void save(Connection db, SecretaryTask task, Long expected)
      throws java.sql.SQLException, com.fasterxml.jackson.core.JsonProcessingException {
    String json = SecretaryJson.JSON.writeValueAsString(task);
    try (var write =
        expected == null
            ? db.prepareStatement(
                "INSERT INTO secretary_tasks(project,title_key,status,due,priority,revision,record,id) VALUES(?,?,?,?,?,?,?,?)")
            : db.prepareStatement(
                "UPDATE secretary_tasks SET project=?,title_key=?,status=?,due=?,priority=?,revision=?,record=? WHERE id=? AND revision=?")) {
      write.setString(1, task.projectId());
      write.setString(2, normalize(task.title()));
      write.setString(3, task.status().name());
      write.setString(4, task.due() == null ? null : task.due().toString());
      write.setInt(5, task.priority());
      write.setLong(6, task.revision());
      write.setString(7, json);
      write.setString(8, task.id());
      if (expected != null) write.setLong(9, expected);
      if (write.executeUpdate() != 1) throw new SecretaryConflictException();
    }
    try (var write = db.prepareStatement("INSERT INTO secretary_history VALUES(?,?,?)")) {
      write.setString(1, task.id());
      write.setLong(2, task.revision());
      write.setString(3, json);
      write.executeUpdate();
    }
  }

  public List<SecretaryTask> history(String project, String id) {
    get(project, id);
    return database.read(
        db -> {
          try (var query =
              db.prepareStatement(
                  "SELECT record FROM secretary_history WHERE task=? ORDER BY revision LIMIT 1000")) {
            query.setString(1, id);
            var result = new ArrayList<SecretaryTask>();
            try (var rows = query.executeQuery()) {
              while (rows.next()) result.add(decode(rows.getString(1)));
            }
            return List.copyOf(result);
          }
        });
  }

  public List<String> sources(String project, String id) {
    get(project, id);
    return database.read(
        db -> {
          try (var query =
              db.prepareStatement(
                  "SELECT event,ordinal FROM secretary_sources WHERE project=? AND task=? ORDER BY event,ordinal LIMIT 1000")) {
            query.setString(1, project);
            query.setString(2, id);
            var result = new ArrayList<String>();
            try (var rows = query.executeQuery()) {
              while (rows.next()) result.add(rows.getString(1) + "#" + rows.getInt(2));
            }
            return List.copyOf(result);
          }
        });
  }

  /** Bounded local similarity; a hint only, never silently combines distinct work. */
  public List<SecretaryTask> related(String project, String id) {
    var task = get(project, id);
    var title = normalize(task.title());
    var grams = grams(title);
    return list(project, 0, 1000).stream()
        .filter(t -> !t.id().equals(id))
        .filter(
            t -> {
              if (title.equals(normalize(t.title()))) return true;
              if (SecretaryRelatedness.related(task.title(), task.tags(), t.title(), t.tags()))
                return true;
              var other = grams(normalize(t.title()));
              long common = grams.stream().filter(other::contains).count();
              return common >= 2
                  && (double) common / Math.max(1, Math.min(grams.size(), other.size())) >= 0.5;
            })
        .limit(20)
        .toList();
  }

  private static java.util.Set<String> grams(String title) {
    var result = new java.util.HashSet<String>();
    for (int i = 0; i + 2 <= title.length(); i++) result.add(title.substring(i, i + 2));
    return result;
  }

  private static String normalize(String title) {
    return java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC)
        .strip()
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  private static void validate(
      String project,
      String title,
      SecretaryTask.Intent intent,
      int priority,
      List<String> tags,
      List<String> references) {
    required(project, 128);
    required(title, 1000);
    if (intent == null
        || priority < 1
        || priority > 5
        || tags == null
        || references == null
        || tags.size() > 32
        || references.size() > 64)
      throw new IllegalArgumentException("Invalid task metadata (priority 1..5)");
    tags.forEach(t -> required(t, 128));
    references.forEach(r -> required(r, 512));
  }

  private static void required(String value, int max) {
    if (value == null || value.isBlank() || value.length() > max)
      throw new IllegalArgumentException("Required value missing or too long");
  }

  private static SecretaryTask decode(String json)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    return SecretaryJson.JSON.readValue(json, SecretaryTask.class);
  }

  private static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
