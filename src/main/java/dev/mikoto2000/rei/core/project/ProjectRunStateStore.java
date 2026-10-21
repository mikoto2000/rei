package dev.mikoto2000.rei.core.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventListener;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * プロジェクト実行状態の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Independent last-run snapshot, never reconstructed by replaying the event audit log.
 */
@Component
public class ProjectRunStateStore implements AgentEventListener {
  /**
   * プロジェクトごとの作業環境で受け渡す状態を表す。
   *
   * @param runId 実行の識別子
   * @param status 状態
   * @param updatedAt 更新日時
   */
  public record State(String runId, String status, Instant updatedAt) {}

  /** 基準。 */
  private final Path base;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  /** 購読。 */
  private AgentEventBus.Subscription subscription;

  /** 結果排他制御一覧。 */
  private final java.util.concurrent.ConcurrentMap<String, Object> resultLocks =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 後から参照できるよう、完了を保存する。 */
  public void saveCompleted(dev.mikoto2000.rei.core.execution.CompletedExecution result) {
    synchronized (
        resultLocks.computeIfAbsent(
            result.projectId() + ":" + result.type(), ignored -> new Object())) {
      var previous = latestCompleted(result.projectId(), result.type());
      if (previous.isPresent() && previous.get().completedAt().isAfter(result.completedAt()))
        return;
      Path file = resultFile(result.projectId(), result.type());
      Path temporary = null;
      try {
        Files.createDirectories(file.getParent());
        temporary = Files.createTempFile(file.getParent(), "completed-", ".tmp");
        Files.writeString(temporary, mapper.writeValueAsString(result));
        try {
          Files.move(
              temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (java.io.IOException e) {
        throw new IllegalStateException("Cannot save completed execution", e);
      } finally {
        if (temporary != null)
          try {
            Files.deleteIfExists(temporary);
          } catch (java.io.IOException ignored) {
          }
      }
    }
  }

  /** プロジェクトごとの作業環境で用いる最新完了を返す。 */
  public Optional<dev.mikoto2000.rei.core.execution.CompletedExecution> latestCompleted(
      String projectId, dev.mikoto2000.rei.core.execution.ExecutionType type) {
    Path file = resultFile(projectId, type);
    if (!Files.exists(file)) return Optional.empty();
    try {
      return Optional.of(
          mapper.readValue(
              Files.readString(file), dev.mikoto2000.rei.core.execution.CompletedExecution.class));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot read completed execution", e);
    }
  }

  /** プロジェクトごとの作業環境で用いる結果ファイルを返す。 */
  private Path resultFile(String projectId, dev.mikoto2000.rei.core.execution.ExecutionType type) {
    java.util.UUID.fromString(projectId);
    return base.resolve("projects")
        .resolve(projectId)
        .resolve("state/latest-" + type.name().toLowerCase(java.util.Locale.ROOT) + ".json");
  }

  /** プロジェクトごとの作業環境を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public ProjectRunStateStore(AgentEventBus bus) {
    this(dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current());
    subscription = bus.subscribe(this);
  }

  /** 指定された値や利用先を使って、ProjectRunStateStoreを初期化する。 */
  public ProjectRunStateStore(Path base) {
    this.base = base;
  }

  /** プロジェクトごとの作業環境で利用した資源や登録を解放し、利用を終了する。 */
  @jakarta.annotation.PreDestroy
  public void close() {
    if (subscription != null) subscription.unsubscribe();
  }

  /** 通知に応じて状態や表示を更新するため、イベントを受け付ける。 */
  public synchronized void onEvent(AgentEvent event) {
    if (event.projectId() == null) return;
    String status =
        switch (event.type()) {
          case AGENT_RUN_STARTED -> "RUNNING";
          case AGENT_RUN_COMPLETED -> "COMPLETED";
          case AGENT_RUN_FAILED ->
              event.payload() instanceof AgentRunFailedPayload failed
                      && failed.error() != null
                      && "cancelled".equals(failed.error().code())
                  ? "CANCELLED"
                  : "FAILED";
          default -> null;
        };
    if (status == null) return;
    Path file = file(event.projectId());
    try {
      Files.createDirectories(file.getParent());
      Path temporary = file.resolveSibling("last-run.tmp");
      Files.writeString(
          temporary,
          mapper.writeValueAsString(new State(event.runId(), status, event.timestamp())));
      try {
        Files.move(
            temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (java.io.IOException e) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cannot save run state: {}", file, e);
    }
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  public synchronized Optional<State> read(String projectId) {
    Path file = file(projectId);
    if (!Files.exists(file)) return Optional.empty();
    try {
      return Optional.of(mapper.readValue(Files.readString(file), State.class));
    } catch (java.io.IOException e) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cannot restore run state: {}", file, e);
      return Optional.empty();
    }
  }

  /** プロジェクトごとの作業環境で用いるファイルを返す。 */
  private Path file(String id) {
    java.util.UUID.fromString(id);
    return base.resolve("projects").resolve(id).resolve("state/last-run.json");
  }
}
