package dev.mikoto2000.rei.core.working;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 現在のタスクで実際に使用されたファイルの集合を保持する。
 *
 * <p>検索結果だけでは追加せず、read / write / edit / create の成功時のみ登録する。 最大件数を超えた場合は lastAccessedAt
 * が古いものから削除する（LRU 相当）。
 */
public class WorkingSet {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(WorkingSet.class);

  /** 秘密情報ASSIGNMENT。 */
  private static final Pattern SECRET_ASSIGNMENT =
      Pattern.compile(
          "(?i)((?:api[_-]?key|access[_-]?token|token|password|secret)\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|\\S+)");

  /** 既定最大ファイル一覧。 */
  static final int DEFAULT_MAX_FILES = 50;

  /** 最大ファイル一覧。 */
  private final int maxFiles;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 実行状況を共有する通知先。 */
  private final AgentEventBus eventBus;

  /** ファイル一覧。 */
  private final Map<String, FileReference> files =
      java.util.Collections.synchronizedMap(new LinkedHashMap<>());

  /** 保存パス。 */
  private Path storagePath;

  private static final class BatchState {
    int depth;
    boolean dirty;
  }

  private final ThreadLocal<BatchState> persistenceBatch = new ThreadLocal<>();

  /** Defer disk snapshots on this thread only; memory, events and eviction remain immediate. */
  public PersistenceBatch batchPersistence() {
    var state = persistenceBatch.get();
    if (state == null) {
      state = new BatchState();
      persistenceBatch.set(state);
    }
    state.depth++;
    return new PersistenceBatch(state);
  }

  public final class PersistenceBatch implements AutoCloseable {
    private final BatchState state;
    private boolean closed;

    private PersistenceBatch(BatchState state) {
      this.state = state;
    }

    @Override
    public void close() {
      if (closed) return;
      if (persistenceBatch.get() != state)
        throw new IllegalStateException("Working Set batch must close on its owning thread");
      closed = true;
      if (--state.depth == 0) {
        persistenceBatch.remove();
        if (state.dirty) persistNow();
      }
    }
  }

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final com.fasterxml.jackson.databind.ObjectMapper mapper =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(
              com.fasterxml.jackson.databind.DeserializationFeature
                  .ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

  /** 作業中の情報管理に関する有効化Persistenceを処理する。 */
  public void enablePersistence(Path path) {
    this.storagePath = path;
    if (java.nio.file.Files.exists(path)) {
      try {
        for (FileReference reference :
            mapper.readValue(java.nio.file.Files.readString(path), FileReference[].class))
          files.put(reference.path(), reference);
      } catch (java.io.IOException error) {
        throw new IllegalStateException("Cannot restore Working Set: " + path, error);
      }
    }
  }

  /** 後の処理へ引き継げるよう、作業中集合を永続化する。 */
  private void persist() {
    var state = persistenceBatch.get();
    if (state != null) state.dirty = true;
    else persistNow();
  }

  private synchronized void persistNow() {
    if (storagePath == null) return;
    try {
      var parent = storagePath.getParent();
      if (parent != null) java.nio.file.Files.createDirectories(parent);
      Path temporary = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
      java.nio.file.Files.writeString(temporary, mapper.writeValueAsString(getFiles()));
      try {
        java.nio.file.Files.move(
            temporary,
            storagePath,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        java.nio.file.Files.move(
            temporary, storagePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (java.io.IOException error) {
      log.warn("Cannot persist Working Set: {}", storagePath, error);
    }
  }

  /** 活動中検索識別子。 */
  private final ThreadLocal<String> activeSearchId = new ThreadLocal<>();

  /** 作業中の情報管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WorkingSet() {
    this(DEFAULT_MAX_FILES, Clock.systemDefaultZone());
  }

  /** 作業中の情報管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WorkingSet(int maxFiles, Clock clock) {
    this(maxFiles, clock, null, null);
  }

  /** 指定された値や利用先を使って、WorkingSetを初期化する。 */
  public WorkingSet(int maxFiles, Clock clock, AgentEventFactory events, AgentEventBus eventBus) {
    this.maxFiles = Math.max(1, maxFiles);
    this.clock = clock;
    this.events = events;
    this.eventBus = eventBus;
  }

  /** ファイルを読み込んだことを記録する。 */
  public void recordRead(Path path) {
    addOrTouch(normalize(path), FileReference.read(normalize(path), now()));
  }

  /** ファイルを書き込んだ（作成・上書き）ことを記録する。 */
  public void recordWrite(Path path) {
    String normalized = normalize(path);
    addOrTouch(normalized, FileReference.write(normalized, now(), now()));
  }

  /** ファイルを編集したことを記録する。 */
  public void recordEdit(Path path) {
    String normalized = normalize(path);
    addOrTouch(normalized, FileReference.edit(normalized, now(), now()));
  }

  /** 新規ファイルを作成したことを記録する。 */
  public void recordCreate(Path path) {
    String normalized = normalize(path);
    addOrTouch(normalized, FileReference.created(normalized, now(), now()));
  }

  /** 指定パスを Working Set から削除する。 */
  public void remove(Path path) {
    remove(normalize(path), "explicit removal");
  }

  /** Working Set を空にする。 */
  public void clear() {
    getFiles().forEach(reference -> remove(reference.path(), "clear"));
  }

  /** 現在の Working Set をアクセス順（新しい順）で返す。 */
  public List<FileReference> getFiles() {
    synchronized (files) {
      return files.values().stream()
          .sorted(Comparator.comparing(FileReference::lastAccessedAt).reversed())
          .toList();
    }
  }

  /** 指定パスが Working Set に存在するかどうか。 */
  public boolean contains(Path path) {
    return files.containsKey(normalize(path));
  }

  /** 指定パスが Working Set に存在する場合、その参照を返す。 */
  public Optional<FileReference> find(Path path) {
    return Optional.ofNullable(files.get(normalize(path)));
  }

  /** Working Set が空かどうか。 */
  public boolean isEmpty() {
    return files.isEmpty();
  }

  /** Working Set の最大件数。 */
  public int maxFiles() {
    return maxFiles;
  }

  /** 新たな対象を管理できるよう、または最終利用日時の更新を追加する。 */
  private void addOrTouch(String normalized, FileReference reference) {
    FileReference existing = files.get(normalized);
    if (existing != null) {
      FileReference updated = existing.withAccessedAt(reference.lastAccessedAt());
      if (reference.lastModifiedAt() != null) {
        updated = updated.withModifiedAt(reference.lastModifiedAt());
      }
      files.put(normalized, updated);
      persist();
      log.debug("Working set: touched {} ({})", normalized, updated.accessType());
      return;
    }
    files.put(normalized, reference);
    log.debug("Working set: added {} ({})", normalized, reference.accessType());
    publishAdded(reference);
    evictIfNeeded();
    persist();
  }

  /** 作業中の情報管理に関する保持対象からの除外条件付きNeededを処理する。 */
  private void evictIfNeeded() {
    while (files.size() > maxFiles) {
      String oldest =
          getFiles().stream()
              .min(Comparator.comparing(FileReference::lastAccessedAt))
              .map(FileReference::path)
              .orElse(null);
      if (oldest == null) {
        return;
      }
      remove(oldest, "capacity eviction");
      log.debug("Working set: evicted {}", oldest);
    }
  }

  /** 同じ意味の指定を共通に扱えるよう、表記や形式をそろえる。 */
  private String normalize(Path path) {
    return path.toAbsolutePath().normalize().toString();
  }

  /** 作業中の情報管理で用いる現在日時を返す。 */
  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  /** LLM コンテキストに渡す簡潔な表現を組み立てる。 */
  public String renderForPrompt() {
    List<FileReference> refs = getFiles();
    if (refs.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("## Current Working Set\n\n");
    sb.append("The following files were recently used in the current task:\n\n");
    for (FileReference ref : refs) {
      sb.append("- ").append(ref.path()).append(" [").append(ref.accessType()).append("]\n");
    }
    sb.append("\n");
    sb.append(
        "Before searching for files, check whether the required file is already present in this working set.\n");
    sb.append("Do not search again merely to rediscover a file whose path is already known.\n");
    sb.append("If the contents are required, read the known file directly.\n");
    sb.append("Search for files only when:\n");
    sb.append("- the required file is not known;\n");
    sb.append("- additional related files must be discovered;\n");
    sb.append("- the known path is no longer valid;\n");
    sb.append("- the current working set is insufficient for the task.\n");
    return sb.toString();
  }

  /** ファイルが存在しなくなった場合に Working Set から除去する。 */
  public void removeIfMissing(Path path) {
    if (!java.nio.file.Files.exists(path)) {
      remove(normalize(path), "missing file");
      log.debug("Working set: removed missing file {}", normalize(path));
    }
  }

  /** 指定された対象を管理から取り除く。 */
  private void remove(String normalized, String reason) {
    FileReference removed = files.remove(normalized);
    persist();
    if (removed == null) {
      return;
    }
    log.debug("Working set: removed {}", normalized);
    if (events != null && eventBus != null) {
      eventBus.publish(events.workingSetItemRemoved(normalized, reason));
    }
  }

  /** 実行状況を利用先で把握できるよう、追加済みを通知する。 */
  private void publishAdded(FileReference reference) {
    if (events != null && eventBus != null) {
      eventBus.publish(
          events.workingSetItemAdded(
              reference.path(),
              "file",
              reference.path(),
              reference.path(),
              reference.accessType(),
              activeSearchId.get()));
    }
  }

  /**
   * 作業中の情報管理で用いる開始検索を返す。
   *
   * <p>Begins the observable lifecycle around the existing search-and-select path.
   */
  public SearchObservation beginSearch(String query, String strategy) {
    String searchId = "ws-search-" + UUID.randomUUID();
    SearchObservation observation =
        new SearchObservation(searchId, System.nanoTime(), files.size());
    activeSearchId.set(searchId);
    if (events != null && eventBus != null) {
      eventBus.publish(
          events.workingSetSearchStarted(searchId, bounded(query, 500), strategy, files.size()));
    }
    return observation;
  }

  /**
   * 実行結果を後続の処理へ引き渡すため、検索を完了させる。
   *
   * <p>Completes a search using aggregate values already produced by the search algorithm.
   */
  public void completeSearch(
      SearchObservation observation,
      int hitCount,
      int candidateCount,
      int selectedCount,
      int alreadyPresentCount) {
    long durationMs = Math.max(0L, (System.nanoTime() - observation.startedAtNanos()) / 1_000_000L);
    if (events != null && eventBus != null) {
      eventBus.publish(
          events.workingSetSearchCompleted(
              observation.searchId(),
              durationMs,
              hitCount,
              candidateCount,
              selectedCount,
              alreadyPresentCount,
              observation.workingSetSizeBefore(),
              files.size()));
    }
    activeSearchId.remove();
  }

  /**
   * 作業中の情報管理に関するabandon検索を処理する。
   *
   * <p>Clears correlation state if the underlying search aborts before producing metrics.
   */
  public void abandonSearch(SearchObservation observation) {
    activeSearchId.remove();
  }

  /** 利用先の上限を超えない量へ、指定された内容を調整する。 */
  private String bounded(String value, int maxLength) {
    if (value == null) return "";
    String redacted = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1[REDACTED]");
    String safe = redacted.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
    return safe.length() <= maxLength ? safe : safe.substring(0, maxLength - 1) + "…";
  }

  /**
   * 作業中の情報管理で受け渡す検索観測内容を表す。
   *
   * @param searchId 検索識別子
   * @param startedAtNanos 開始日時ナノ秒数
   * @param workingSetSizeBefore 作業中集合大きさ変更前
   */
  public record SearchObservation(String searchId, long startedAtNanos, int workingSetSizeBefore) {}
}
