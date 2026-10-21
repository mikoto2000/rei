package dev.mikoto2000.rei.artifact;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.run.RunNotFoundException;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.session.CursorCodec;
import dev.mikoto2000.rei.application.session.CursorKey;
import dev.mikoto2000.rei.application.session.HistoryPage;
import dev.mikoto2000.rei.application.session.Pagination;
import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 成果物の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>A bounded immutable copy plus durable receipt, with no client-selected filesystem lookup.
 */
public final class ArtifactStore {
  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /** 作業ルート。 */
  private final Path root;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 上限一覧。 */
  private final ArtifactProperties limits;

  /** 成果物の管理における通知段階の選択肢。 */
  enum PublishStage {
    /** 予約済み。 */
    RESERVED,
    /** ファイルMOVED。 */
    FILE_MOVED
  }

  /** 再開用の記録。 */
  private final java.util.function.BiConsumer<PublishStage, String> checkpoint;

  /** 続きの取得位置一覧。 */
  private final CursorCodec cursors = new CursorCodec();

  /** 実行一覧。 */
  private RunRegistry runs;

  /** 成果物の管理で使用する実行登録台帳を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setRunRegistry(RunRegistry runs) {
    this.runs = runs;
  }

  /**
   * 成果物の管理で受け渡す保存済みを表す。
   *
   * @param item 項目
   * @param projectRoot 作業対象のルートディレクトリ
   * @param sourceKey 取得元キー
   * @param pid プロセス識別子
   * @param processStart プロセス開始
   */
  private record Stored(
      Artifact item, String projectRoot, String sourceKey, long pid, String processStart) {}

  /** 成果物の管理で用いるnullableを返す。 */
  private static String nullable(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  /** 後続の処理で使う値を返す。 */
  private static String value(String value) {
    return value == null ? "" : value;
  }

  /** 行。 */
  private static final org.springframework.jdbc.core.RowMapper<Stored> ROW =
      (row, n) ->
          new Stored(
              new Artifact(
                  row.getString("id"),
                  row.getString("owner"),
                  row.getString("project"),
                  nullable(row.getString("session")),
                  nullable(row.getString("run")),
                  nullable(row.getString("task")),
                  row.getString("media"),
                  row.getString("filename"),
                  row.getLong("size"),
                  row.getString("sha"),
                  Instant.parse(row.getString("created")),
                  Instant.parse(row.getString("expires")),
                  "artifact:" + row.getString("id"),
                  row.getString("status")),
              row.getString("root"),
              row.getString("source_key"),
              row.getLong("pid"),
              row.getString("process_start"));

  /** 成果物の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ArtifactStore(
      DataSource source,
      ProjectRegistry projects,
      Path root,
      Clock clock,
      ArtifactProperties limits) {
    this(source, projects, root, clock, limits, (stage, id) -> {});
  }

  /** 指定された値や利用先を使って、ArtifactStoreを初期化する。 */
  ArtifactStore(
      DataSource source,
      ProjectRegistry projects,
      Path root,
      Clock clock,
      ArtifactProperties limits,
      java.util.function.BiConsumer<PublishStage, String> checkpoint) {
    limits.validate();
    this.db = JdbcClient.create(source);
    this.projects = projects;
    this.root = root.toAbsolutePath().normalize();
    this.clock = clock;
    this.limits = limits;
    this.checkpoint = Objects.requireNonNull(checkpoint);
    db.sql(
            """
        CREATE TABLE IF NOT EXISTS rei_artifacts(id TEXT PRIMARY KEY,owner TEXT NOT NULL,project TEXT NOT NULL,root TEXT NOT NULL,
        session TEXT NOT NULL,run TEXT NOT NULL,task TEXT NOT NULL,source_key TEXT NOT NULL,media TEXT NOT NULL,filename TEXT NOT NULL,
        size INTEGER NOT NULL,sha TEXT NOT NULL,created TEXT NOT NULL,expires TEXT NOT NULL,status TEXT NOT NULL,pid INTEGER NOT NULL,process_start TEXT,
        UNIQUE(project,root,session,run,source_key))
        """)
        .update();
  }

  /** 状態や結果を購読先へ通知する。 */
  public Artifact publish(
      AgentRunContext owner, String sourceKey, String media, String filename, byte[] supplied) {
    var project = project(owner.projectId());
    if (!project.root().equals(owner.projectRoot())
        || owner.runId().isBlank()
        || owner.runId().length() > 128
        || owner.conversationId().length() > 256)
      throw new IllegalArgumentException("Invalid Artifact ownership");
    String session = owner.conversationId();
    if (runs != null)
      try {
        var registered = runs.get(owner.runId());
        if (!registered.context().equals(owner)) throw new OperationConflictException();
        if (registered.childOrigin() != null) session = registered.childOrigin().sessionId();
      } catch (RunNotFoundException absent) {
        /* Standalone trusted generation need not register a Run. */
      }
    return publishOwned(
        project, session, owner.runId(), "RUN", sourceKey, media, filename, supplied);
  }

  /** 実行状況を利用先で把握できるよう、会話を通知する。 */
  public Artifact publishSession(
      ProjectContext owner,
      String session,
      String sourceKey,
      String media,
      String filename,
      byte[] supplied) {
    var registered = project(owner.id());
    if (!registered.root().equals(owner.root())
        || session == null
        || session.isBlank()
        || session.length() > 256) throw new IllegalArgumentException("Invalid Artifact ownership");
    return publishOwned(registered, session, "", "SESSION", sourceKey, media, filename, supplied);
  }

  /** 実行状況を利用先で把握できるよう、自分が所有する対象を通知する。 */
  private Artifact publishOwned(
      ProjectContext project,
      String session,
      String run,
      String ownership,
      String sourceKey,
      String media,
      String filename,
      byte[] supplied) {
    dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
    if (sourceKey == null || !sourceKey.matches("[A-Za-z0-9._:-]{1,256}"))
      throw new IllegalArgumentException("Invalid Artifact source");
    if (supplied == null || supplied.length > limits.getMaxBytes())
      throw new ArtifactException(ArtifactException.Code.CAPACITY);
    byte[] bytes = Arrays.copyOf(supplied, supplied.length);
    filename(filename);
    media(media, bytes);
    String sha = hash(bytes);
    var previous =
        db.sql(
                "SELECT * FROM rei_artifacts WHERE project=? AND root=? AND session=? AND run=? AND source_key=?")
            .params(project.id(), project.root().toString(), session, run, sourceKey)
            .query(ROW)
            .optional();
    if (previous.isPresent()) return repeated(previous.get(), media, filename, sha);
    String id = UUID.randomUUID().toString();
    Instant now = clock.instant();
    int inserted =
        db.sql(
                """
        INSERT OR IGNORE INTO rei_artifacts SELECT :id,:owner,:project,:root,:session,:run,:task,:key,:media,:filename,:size,:sha,:created,:expires,'PUBLISHING',:pid,:start
        WHERE (SELECT COUNT(*) FROM rei_artifacts WHERE status<>'DELETED')<:count
          AND (SELECT COALESCE(SUM(size),0) FROM rei_artifacts WHERE status<>'DELETED')+:size<=:total
          AND (SELECT COUNT(*) FROM rei_artifacts)<10000
        """)
            .param("id", id)
            .param("owner", ownership)
            .param("task", run.isEmpty() ? "" : "run:" + run)
            .param("project", project.id())
            .param("root", project.root().toString())
            .param("session", session)
            .param("run", run)
            .param("key", sourceKey)
            .param("media", media)
            .param("filename", filename)
            .param("size", bytes.length)
            .param("sha", sha)
            .param("created", now.toString())
            .param("expires", now.plus(limits.getRetention()).toString())
            .param("pid", ProcessHandle.current().pid())
            .param("start", processStart())
            .param("count", limits.getMaxArtifacts())
            .param("total", limits.getMaxTotalBytes())
            .update();
    if (inserted != 1) {
      var duplicate =
          db.sql(
                  "SELECT * FROM rei_artifacts WHERE project=? AND root=? AND session=? AND run=? AND source_key=?")
              .params(project.id(), project.root().toString(), session, run, sourceKey)
              .query(ROW)
              .optional();
      if (duplicate.isPresent()) return repeated(duplicate.get(), media, filename, sha);
      throw new ArtifactException(ArtifactException.Code.CAPACITY);
    }
    Path temporary = null;
    try {
      checkpoint.accept(PublishStage.RESERVED, id);
      safeRoot();
      Files.createDirectories(root);
      safeRoot();
      var destination = path(id);
      temporary = Files.createTempFile(root, ".artifact-", ".tmp");
      Files.write(temporary, bytes);
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
      safeRoot();
      if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Duplicate destination");
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
      temporary = null;
      checkpoint.accept(PublishStage.FILE_MOVED, id);
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
      if (db.sql("UPDATE rei_artifacts SET status='AVAILABLE' WHERE id=? AND status='PUBLISHING'")
              .param(id)
              .update()
          != 1) throw new OperationConflictException();
      return get(project.id(), nullable(session), id);
    } catch (IOException error) {
      mark(id, "PUBLISHING", "UNKNOWN");
      throw new ArtifactException(ArtifactException.Code.STORAGE_FAILURE);
    } catch (RuntimeException error) {
      mark(id, "PUBLISHING", "UNKNOWN");
      throw error;
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
    }
  }

  /** 成果物の管理で用いるrepeatedを返す。 */
  private Artifact repeated(Stored previous, String media, String filename, String sha) {
    var item = previous.item();
    if (!item.sha256().equals(sha)
        || !item.mediaType().equals(media)
        || !item.filename().equals(filename)) throw new OperationConflictException();
    var current = get(item.projectId(), item.sessionId(), item.artifactId());
    if (!current.status().equals("AVAILABLE"))
      throw new ArtifactException(ArtifactException.Code.UNAVAILABLE);
    content(item.projectId(), item.sessionId(), item.artifactId());
    return current;
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Artifact get(String project, String session, String id) {
    return refresh(owned(project, session, id)).item();
  }

  /**
   * 指定された対象を削除する。
   *
   * <p>Explicit owner-scoped deletion only; retain the tombstone and never remove source files.
   */
  public Artifact delete(String project, String session, String id) {
    var item = get(project, session, id);
    if (item.status().equals("DELETED")) return item;
    if (!Set.of("AVAILABLE", "UNKNOWN", "MISSING", "STALE", "EXPIRED").contains(item.status()))
      throw new ArtifactException(ArtifactException.Code.UNAVAILABLE);
    Path file = path(id);
    if (db.sql(
                "UPDATE rei_artifacts SET status='DELETING',pid=?,process_start=? WHERE id=? AND status=?")
            .params(ProcessHandle.current().pid(), processStart(), id, item.status())
            .update()
        != 1) throw new OperationConflictException();
    try {
      Files.deleteIfExists(file);
      mark(id, "DELETING", "DELETED");
      return get(project, session, id);
    } catch (IOException error) {
      mark(id, "DELETING", "UNKNOWN");
      throw new ArtifactException(ArtifactException.Code.STORAGE_FAILURE);
    }
  }

  /** 成果物の管理で用いる内容を返す。 */
  public byte[] content(String project, String session, String id) {
    var item = get(project, session, id);
    if (!item.status().equals("AVAILABLE")) throw new ArtifactException(code(item.status()));
    try {
      Path file = path(id);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        mark(id, "AVAILABLE", "MISSING");
        throw new ArtifactException(ArtifactException.Code.MISSING);
      }
      if (Files.size(file) != item.size() || item.size() > limits.getMaxBytes()) {
        mark(id, "AVAILABLE", "STALE");
        throw new ArtifactException(ArtifactException.Code.STALE);
      }
      byte[] bytes;
      try (var channel =
          Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
        var buffer = ByteBuffer.allocate(Math.toIntExact(item.size()));
        while (buffer.hasRemaining() && channel.read(buffer) != -1) {}
        if (buffer.hasRemaining() || channel.read(ByteBuffer.allocate(1)) != -1) {
          mark(id, "AVAILABLE", "STALE");
          throw new ArtifactException(ArtifactException.Code.STALE);
        }
        bytes = buffer.array();
      }
      if (!hash(bytes).equals(item.sha256())) {
        mark(id, "AVAILABLE", "STALE");
        throw new ArtifactException(ArtifactException.Code.STALE);
      }
      return bytes;
    } catch (IOException error) {
      throw new ArtifactException(ArtifactException.Code.STORAGE_FAILURE);
    }
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public HistoryPage<Artifact> list(
      String project, String session, String run, Integer requested, String cursor) {
    int limit = Pagination.limit(requested);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (session != null && (session.isBlank() || session.length() > 256)
        || run != null && (run.isBlank() || run.length() > 128))
      throw new IllegalArgumentException("Invalid Artifact filter");
    String scope = "artifacts:" + value(project) + ":" + value(session) + ":" + value(run);
    var after = cursors.decode(scope, cursor);
    var selected = project == null ? projects.list() : List.of(project(project));
    var result = new TreeMap<String, Artifact>();
    for (var owner : selected)
      for (var stored :
          db.sql(
                  """
        SELECT * FROM rei_artifacts WHERE project=:project AND root=:root AND (:session IS NULL OR session=:session)
        AND (:run IS NULL OR run=:run) AND id>:after ORDER BY id LIMIT :limit
        """)
              .param("project", owner.id())
              .param("root", owner.root().toString())
              .param("session", session)
              .param("run", run)
              .param("after", after == null ? "" : after.id())
              .param("limit", limit + 1)
              .query(ROW)
              .list()) {
        var item = refresh(stored).item();
        result.put(item.artifactId(), item);
        if (result.size() > limit + 1) result.pollLastEntry();
      }
    return Pagination.page(
        List.copyOf(result.values()),
        limit,
        item -> cursors.encode(scope, new CursorKey(Instant.EPOCH, item.artifactId())));
  }

  /** 成果物の管理で用いる自分が所有する対象を返す。 */
  private Stored owned(String project, String session, String id) {
    var owner = project(project);
    if (!id(id)) throw new ResourceNotFoundException("Artifact");
    return db.sql("SELECT * FROM rei_artifacts WHERE id=? AND project=? AND root=? AND session=?")
        .params(id, project, owner.root().toString(), value(session))
        .query(ROW)
        .optional()
        .orElseThrow(() -> new ResourceNotFoundException("Artifact"));
  }

  /** 成果物の管理で用いる再確認を返す。 */
  private Stored refresh(Stored stored) {
    var item = stored.item();
    String next = null;
    if (Set.of("PUBLISHING", "DELETING").contains(item.status()) && !alive(stored))
      next = "UNKNOWN";
    if (item.status().equals("AVAILABLE") && !clock.instant().isBefore(item.expiresAt()))
      next = "EXPIRED";
    if (next != null) mark(item.artifactId(), item.status(), next);
    return next == null
        ? stored
        : db.sql("SELECT * FROM rei_artifacts WHERE id=?")
            .param(item.artifactId())
            .query(ROW)
            .single();
  }

  /** 後続の判断に現在の状態を反映するため、成果物保存先の状態を記録する。 */
  private void mark(String id, String before, String after) {
    db.sql("UPDATE rei_artifacts SET status=? WHERE id=? AND status=?")
        .params(after, id, before)
        .update();
  }

  /** 成果物の管理で用いるプロジェクトを返す。 */
  private ProjectContext project(String id) {
    return projects.resolveById(id).orElseThrow(() -> new ResourceNotFoundException("Artifact"));
  }

  /** 成果物の管理で用いるパスを返す。 */
  private Path path(String id) {
    if (!id(id)) throw new ResourceNotFoundException("Artifact");
    safeRoot();
    var path = root.resolve(id + ".bin");
    if (Files.isSymbolicLink(path))
      throw new ArtifactException(ArtifactException.Code.UNSAFE_STORAGE);
    return path;
  }

  /** 成果物の管理に関する安全性作業ルートを処理する。 */
  private void safeRoot() {
    for (Path directory = root; directory != null; directory = directory.getParent())
      if (Files.isSymbolicLink(directory))
        throw new ArtifactException(ArtifactException.Code.UNSAFE_STORAGE);
  }

  /** 成果物の管理において、識別子に該当するか判定する。 */
  private static boolean id(String id) {
    try {
      return id != null && UUID.fromString(id).toString().equals(id);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  /** 成果物の管理で用いるプロセス開始を返す。 */
  private static String processStart() {
    return ProcessHandle.current().info().startInstant().map(Instant::toString).orElse(null);
  }

  /** 成果物の管理において、稼働中に該当するか判定する。 */
  private static boolean alive(Stored stored) {
    return stored.processStart() != null
        && ProcessHandle.of(stored.pid())
            .filter(ProcessHandle::isAlive)
            .flatMap(p -> p.info().startInstant())
            .map(start -> start.toString().equals(stored.processStart()))
            .orElse(false);
  }

  /** 成果物の管理で用いるコードを返す。 */
  private static ArtifactException.Code code(String status) {
    try {
      return ArtifactException.Code.valueOf(status);
    } catch (IllegalArgumentException invalid) {
      return ArtifactException.Code.UNAVAILABLE;
    }
  }

  /** 成果物の管理に関するファイル名を処理する。 */
  private static void filename(String name) {
    if (name == null
        || name.isBlank()
        || name.length() > 128
        || name.getBytes(StandardCharsets.UTF_8).length > 255
        || name.equals(".")
        || name.equals("..")
        || name.chars().anyMatch(c -> c < 32 || c == 127 || "/\\:".indexOf(c) >= 0))
      throw new IllegalArgumentException("Invalid Artifact filename");
  }

  /** 成果物の管理に関するメディアを処理する。 */
  private static void media(String media, byte[] bytes) {
    if (media == null
        || !Set.of(
                "text/plain",
                "text/markdown",
                "application/json",
                "application/pdf",
                "image/png",
                "image/jpeg",
                "application/octet-stream")
            .contains(media)) throw new IllegalArgumentException("Unsupported Artifact media");
    if (media.startsWith("text/") || media.equals("application/json"))
      try {
        StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
      } catch (java.nio.charset.CharacterCodingException invalid) {
        throw new IllegalArgumentException("Invalid UTF-8");
      }
    byte[] prefix =
        switch (media) {
          case "image/png" -> new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
          case "image/jpeg" -> new byte[] {(byte) 255, (byte) 216, (byte) 255};
          case "application/pdf" -> "%PDF-".getBytes(StandardCharsets.US_ASCII);
          default -> new byte[0];
        };
    if (bytes.length < prefix.length || !Arrays.equals(prefix, Arrays.copyOf(bytes, prefix.length)))
      throw new IllegalArgumentException("Artifact media differs from content");
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
