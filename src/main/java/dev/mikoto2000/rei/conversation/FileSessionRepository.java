package dev.mikoto2000.rei.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.application.session.CursorKey;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.session.SessionRepository;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * ファイル会話を保存・検索するための窓口。
 *
 * <p>One application writer, atomic replacement, and monitor-protected admission/read operations.
 */
public final class FileSessionRepository implements SessionRepository {
  /** ファイル。 */
  private final Path file;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  /** 入力補完状態の記録。 */
  private volatile List<SessionMetadata> completionSnapshot = List.of();

  /** 指定された値や利用先を使って、FileSessionRepositoryを初期化する。 */
  public FileSessionRepository(Path file) {
    this.file = file.toAbsolutePath().normalize();
    // Warm the optional input aid at startup; normal repository reads still report corrupt data.
    try {
      read();
    } catch (RuntimeException ignored) {
    }
  }

  /** 会話履歴と受付記録の管理で用いる入力補完状態の記録を返す。 */
  @Override
  public List<SessionMetadata> completionSnapshot() {
    return completionSnapshot;
  }

  /** 対象を特定できるよう、別識別子を探す。 */
  @Override
  public synchronized Optional<SessionMetadata> findById(String id) {
    return read().stream().filter(row -> row.sessionId().equals(id)).findFirst();
  }

  /** 対象を特定できるよう、ページを探す。 */
  @Override
  public synchronized List<SessionMetadata> findPage(
      String projectId, CursorKey after, int fetchLimit) {
    if (fetchLimit < 1 || fetchLimit > 101)
      throw new IllegalArgumentException("Invalid fetch limit");
    return read().stream()
        .filter(row -> projectId == null || row.projectId().equals(projectId))
        .filter(
            row ->
                after == null
                    || row.updatedAt().isBefore(after.time())
                    || row.updatedAt().equals(after.time())
                        && row.sessionId().compareTo(after.id()) > 0)
        .sorted(
            Comparator.comparing(SessionMetadata::updatedAt)
                .reversed()
                .thenComparing(SessionMetadata::sessionId))
        .limit(fetchLimit)
        .toList();
  }

  /** 要求を後続の処理へ引き渡すため、ファイル会話保存と検索の窓口を受け付ける。 */
  @Override
  public synchronized void accept(SessionMetadata metadata, Runnable enqueue) {
    var previous = read();
    var next = new ArrayList<>(previous);
    var existing =
        previous.stream().filter(row -> row.sessionId().equals(metadata.sessionId())).findFirst();
    if (existing.isPresent()) {
      var row = existing.get();
      // 別のプロジェクトの情報を、この作業の対象として扱わせない。
      if (!row.projectId().equals(metadata.projectId())
          || !row.title().equals(metadata.title())
          || !row.createdAt().equals(metadata.createdAt()))
        throw new IllegalArgumentException("Immutable session metadata");
      next.set(next.indexOf(row), metadata.touched(row.updatedAt()));
    } else next.add(metadata);
    save(next);
    try {
      enqueue.run();
    } catch (RuntimeException | Error error) {
      try {
        save(previous);
      } catch (RuntimeException rollback) {
        error.addSuppressed(rollback);
      }
      throw error;
    }
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  private List<SessionMetadata> read() {
    if (!Files.exists(file)) {
      completionSnapshot = List.of();
      return completionSnapshot;
    }
    try {
      completionSnapshot =
          List.of(mapper.readValue(Files.readString(file), SessionMetadata[].class));
      return completionSnapshot;
    } catch (IOException error) {
      throw new IllegalStateException("Cannot read session metadata", error);
    }
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  private void save(List<SessionMetadata> rows) {
    Path temporary = null;
    try {
      Files.createDirectories(file.getParent());
      temporary = Files.createTempFile(file.getParent(), "sessions-", ".tmp");
      Files.writeString(temporary, mapper.writeValueAsString(rows));
      try {
        Files.move(
            temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException error) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
      completionSnapshot = List.copyOf(rows);
    } catch (IOException error) {
      throw new IllegalStateException("Cannot persist session metadata", error);
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException error) {
          org.slf4j.LoggerFactory.getLogger(getClass())
              .warn("Cannot remove session temporary file", error);
        }
    }
  }
}
