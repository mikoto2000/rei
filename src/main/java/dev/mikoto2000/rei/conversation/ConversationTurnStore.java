package dev.mikoto2000.rei.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.application.session.ConversationHistory;
import dev.mikoto2000.rei.application.session.CursorKey;
import dev.mikoto2000.rei.application.session.SessionTurn;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 会話一回のやり取りの記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Conversation lifecycle metadata, independent of the bounded chat memory window.
 */
public class ConversationTurnStore implements ConversationHistory {
  /** 会話履歴と受付記録の管理で用いる一回のやり取り件数を返す。 */
  public synchronized long turnCount(String conversation) {
    return read(conversation).size();
  }

  /**
   * 必要な内容を参照できるよう、対象範囲を読み取る。
   *
   * <p>Ordinal range for bounded background processing; SQLite overrides without reading earlier
   * turns.
   */
  public synchronized List<Turn> readRange(String conversation, long from, int limit) {
    if (from < 0 || limit < 1 || limit > 100)
      throw new IllegalArgumentException("Invalid turn range");
    return read(conversation).stream().skip(from).limit(limit).toList();
  }

  /** 対象を特定できるよう、実行を探す。 */
  public synchronized Optional<Turn> findRun(String conversation, String run) {
    return read(conversation).stream().filter(t -> t.runId().equals(run)).findFirst();
  }

  /** 会話履歴と受付記録の管理における状態の選択肢。 */
  public enum Status {
    /** 実行中。 */
    RUNNING,
    /** 完了。 */
    COMPLETED,
    /** 失敗。 */
    FAILED,
    /** キャンセル済み。 */
    CANCELLED
  }

  /**
   * 会話履歴と受付記録の管理で受け渡す一回のやり取りを表す。
   *
   * @param runId 実行の識別子
   * @param request 要求
   * @param status 状態
   * @param assistantMessage アシスタントメッセージ
   * @param createdAt 作成日時
   * @param source 取得元
   * @param sourceId 取得元識別子
   * @param metadata 付随情報
   */
  public record Turn(
      String runId,
      String request,
      Status status,
      String assistantMessage,
      java.time.Instant createdAt,
      String source,
      String sourceId,
      Map<String, String> metadata) {
    /** 受け取った情報からTurnを作成する。 */
    public Turn {
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 会話履歴と受付記録の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Turn(
        String runId,
        String request,
        Status status,
        String assistantMessage,
        java.time.Instant createdAt) {
      this(runId, request, status, assistantMessage, createdAt, null, null, Map.of());
    }
  }

  /** 既存の記録へ経緯を残すため、アシスタント通知を追記する。 */
  public synchronized void appendAssistantNotification(ConversationLogEntry entry) {
    var rows = new ArrayList<>(read(entry.conversationId()));
    if (rows.stream()
        .anyMatch(
            t ->
                ConversationLogStore.BEHAVIOR_NOTIFICATION.equals(t.source())
                    && entry.sourceId().equals(t.sourceId()))) return;
    rows.add(
        new Turn(
            "behavior:" + entry.sourceId(),
            "",
            Status.COMPLETED,
            entry.content(),
            entry.timestamp().toInstant(),
            entry.source(),
            entry.sourceId(),
            entry.metadata()));
    save(entry.conversationId(), rows);
  }

  /** 基準。 */
  private final Path base;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper mapper =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 会話一覧。 */
  private final Map<String, List<Turn>> conversations = new HashMap<>();

  /** 会話履歴と受付記録の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ConversationTurnStore() {
    this(ReiDataDirectory.current());
  }

  /** 指定された値や利用先を使って、ConversationTurnStoreを初期化する。 */
  public ConversationTurnStore(Path base) {
    this.base = base;
  }

  /** 会話履歴と受付記録の管理で用いる入力先記憶を返す。 */
  public static ConversationTurnStore inMemory() {
    return new ConversationTurnStore(null);
  }

  /** 要求された処理を開始する。 */
  public synchronized void start(AgentRunContext context, String request) {
    start(context, request, java.time.Instant.now());
  }

  /**
   * 要求された処理を利用できるよう、Orderedを開始する。
   *
   * <p>Execution starts are serialized by the project queue. Preserve that order even on clock
   * ties/rollback.
   */
  public synchronized void startOrdered(
      AgentRunContext context, String request, java.time.Instant observedAt) {
    var last =
        read(context.conversationId()).stream()
            .map(Turn::createdAt)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder());
    var createdAt =
        last.isPresent() && !observedAt.isAfter(last.get()) ? last.get().plusNanos(1) : observedAt;
    start(context, request, createdAt);
  }

  /** 要求された処理を開始する。 */
  public synchronized void start(
      AgentRunContext context, String request, java.time.Instant createdAt) {
    var turns = new ArrayList<>(read(context.conversationId()));
    turns.add(new Turn(context.runId(), request, Status.RUNNING, null, createdAt));
    save(context.conversationId(), turns);
  }

  /** 終了時に必要な状態を反映し、処理を完了させる。 */
  public synchronized void finish(AgentRunContext context, Status status) {
    finish(context, status, null);
  }

  /** 終了時に必要な状態を反映し、処理を完了させる。 */
  public synchronized void finish(AgentRunContext context, Status status, String assistantMessage) {
    if (status == Status.RUNNING)
      throw new IllegalArgumentException("Expected a terminal turn status");
    if (read(context.conversationId()).stream()
        .noneMatch(t -> t.runId().equals(context.runId()) && t.status() == Status.RUNNING)) return;
    var turns =
        read(context.conversationId()).stream()
            .map(
                turn ->
                    turn.runId().equals(context.runId()) && turn.status() == Status.RUNNING
                        ? new Turn(
                            turn.runId(),
                            turn.request(),
                            status,
                            assistantMessage,
                            turn.createdAt(),
                            turn.source(),
                            turn.sourceId(),
                            turn.metadata())
                        : turn)
            .toList();
    save(context.conversationId(), turns);
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  public synchronized List<Turn> read(String conversationId) {
    return conversations.computeIfAbsent(
        conversationId,
        id -> {
          if (base == null || !Files.exists(file(id))) return List.of();
          try {
            return List.of(mapper.readValue(Files.readString(file(id)), Turn[].class));
          } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read conversation turns", error);
          }
        });
  }

  /**
   * 後から経緯や使用量を確認できるよう、付随情報を記録する。
   *
   * <p>Optional observed work-time metadata, captured by the owner without changing turn identity.
   */
  public synchronized void recordMetadata(AgentRunContext context, Map<String, String> metadata) {
    var rows =
        read(context.conversationId()).stream()
            .map(
                t -> {
                  if (!t.runId().equals(context.runId())) return t;
                  var combined = new HashMap<>(t.metadata());
                  combined.putAll(metadata);
                  return new Turn(
                      t.runId(),
                      t.request(),
                      t.status(),
                      t.assistantMessage(),
                      t.createdAt(),
                      t.source(),
                      t.sourceId(),
                      combined);
                })
            .toList();
    save(context.conversationId(), rows);
  }

  /** 対象を特定できるよう、やり取り一覧を探す。 */
  @Override
  public synchronized List<SessionTurn> findTurns(
      String sessionId, CursorKey after, int fetchLimit) {
    if (fetchLimit < 1 || fetchLimit > 101)
      throw new IllegalArgumentException("Invalid fetch limit");
    // Legacy lifecycle-only records have no timestamp; do not invent one or misassociate log
    // messages.
    return read(sessionId).stream()
        .filter(turn -> turn.createdAt() != null)
        .filter(
            turn ->
                after == null
                    || turn.createdAt().isAfter(after.time())
                    || turn.createdAt().equals(after.time())
                        && turn.runId().compareTo(after.id()) > 0)
        .sorted(Comparator.comparing(Turn::createdAt).thenComparing(Turn::runId))
        .limit(fetchLimit)
        .map(
            turn ->
                new SessionTurn(
                    turn.runId(),
                    turn.request(),
                    turn.assistantMessage(),
                    turn.createdAt(),
                    turn.source(),
                    turn.sourceId(),
                    turn.metadata()))
        .toList();
  }

  /** 会話履歴と受付記録の管理で用いるキャンセル済み文脈を返す。 */
  public synchronized String cancelledContext(String conversationId) {
    if (!hasCancelled(conversationId)) return "";
    return """
    Conversation lifecycle: CANCELLED task attempts are archived, not active work.
    Respond to the current user input. Do not retrieve or resume archived tasks automatically.
    Only if the user explicitly requests resumption or a retrospective, retrieve the original
    conversation with getConversationHistory/searchConversationHistory and, when needed,
    workContextGet. Check actual files and running processes before repeating an operation.
    Historical records and side effects have been retained; cancellation did not undo them.
    """;
  }

  protected boolean hasCancelled(String conversationId) {
    return read(conversationId).stream().anyMatch(t -> t.status() == Status.CANCELLED);
  }

  /** Builds an immutable model-context policy without changing the persistent conversation. */
  public dev.mikoto2000.rei.context.CancelledTaskHistory promptHistory(String conversationId) {
    return promptHistoryFor(read(conversationId));
  }

  /** Reuses an already loaded lifecycle snapshot without another storage read. */
  public static dev.mikoto2000.rei.context.CancelledTaskHistory promptHistoryFor(List<Turn> history) {
    return dev.mikoto2000.rei.context.CancelledTaskHistory.from(
        history.stream()
            .map(
                t ->
                    new dev.mikoto2000.rei.context.CancelledTaskHistory.Attempt(
                        t.runId(), t.status().name(), t.createdAt()))
            .toList());
  }

  /** 会話履歴と受付記録の管理で用いるファイルを返す。 */
  private Path file(String conversationId) {
    String projectId = dev.mikoto2000.rei.core.project.ProjectStorage.projectId(conversationId);
    Path directory = projectId == null ? base : base.resolve("projects").resolve(projectId);
    String key =
        UUID.nameUUIDFromBytes(conversationId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    return directory.resolve("state/turns").resolve(key + ".json");
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  private void save(String id, List<Turn> turns) {
    if (base != null) {
      Path target = file(id);
      Path temporary = null;
      try {
        Files.createDirectories(target.getParent());
        temporary = Files.createTempFile(target.getParent(), "turns-", ".tmp");
        Files.writeString(temporary, mapper.writeValueAsString(turns));
        try {
          Files.move(
              temporary,
              target,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
          Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (java.io.IOException error) {
        throw new IllegalStateException("Cannot save conversation turns", error);
      } finally {
        if (temporary != null)
          try {
            Files.deleteIfExists(temporary);
          } catch (java.io.IOException error) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                .warn("Cannot remove turn temporary file", error);
          }
      }
    }
    conversations.put(id, List.copyOf(turns));
  }
}
