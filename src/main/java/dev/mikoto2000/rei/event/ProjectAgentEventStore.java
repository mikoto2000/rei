package dev.mikoto2000.rei.event;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * プロジェクトエージェントイベントの記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Typed JSONL audit log. Project sequences are durable; the bus retains its process sequence
 * API.
 */
public class ProjectAgentEventStore implements AgentEventListener {
  /**
   * 対象を特定できるよう、イベントを探す。
   *
   * <p>Legacy file backend is bounded. The SQLite backend provides indexed lookup across retained
   * events.
   */
  public Optional<AgentEvent> findEvent(String project, String id) {
    return recent(project, 1000).stream().filter(e -> e.id().equals(id)).findFirst();
  }

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ProjectAgentEventStore.class);

  /** 基準。 */
  private final Path base;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  /** 発生順序一覧。 */
  private final Map<String, Long> sequences = new HashMap<>();

  /** 実行状況の通知に必要な通知内容種別を扱う。 */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "_payloadType")
  private interface PayloadType {}

  /** 実行状況の通知を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ProjectAgentEventStore() {
    this(dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current());
  }

  /** 指定された値や利用先を使って、ProjectAgentEventStoreを初期化する。 */
  public ProjectAgentEventStore(Path base) {
    this.base = base;
    mapper.addMixIn(AgentEventPayload.class, PayloadType.class);
    for (Class<?> type : AgentEventPayload.class.getPermittedSubclasses())
      mapper.registerSubtypes(new NamedType(type, type.getSimpleName()));
  }

  /** 通知に応じて状態や表示を更新するため、イベントを受け付ける。 */
  @Override
  public void onEvent(AgentEvent event) {
    if (event.projectId() != null) append(event);
  }

  /** 既存の記録へ経緯を残すため、プロジェクトエージェントイベント保存先を追記する。 */
  public synchronized AgentEvent append(AgentEvent event) {
    if (event.projectId() == null)
      throw new IllegalArgumentException("ProjectId is required for persisted agent events");
    long next = lastSequence(event.projectId()) + 1;
    var stored =
        new AgentEvent(
            event.id(),
            next,
            event.timestamp(),
            event.type(),
            event.version(),
            event.sessionId(),
            event.turnId(),
            event.runId(),
            event.correlationId(),
            event.parentEventId(),
            event.payload(),
            event.projectId());
    Path file = file(event.projectId());
    try {
      Files.createDirectories(file.getParent());
      String line = mapper.writeValueAsString(stored);
      // Separate an interrupted final line before appending a new complete record.
      String separator = "";
      if (Files.exists(file) && Files.size(file) > 0)
        try (var input = new RandomAccessFile(file.toFile(), "r")) {
          input.seek(input.length() - 1);
          if (input.read() != '\n') separator = "\n";
        }
      Files.writeString(
          file,
          separator + line + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      sequences.put(event.projectId(), next);
      return stored;
    } catch (IOException error) {
      throw new IllegalStateException("Cannot persist agent event: " + file, error);
    }
  }

  /** 実行状況の通知で用いる直近発生順序を返す。 */
  public synchronized long lastSequence(String projectId) {
    return sequences.computeIfAbsent(
        projectId, id -> recent(id, 1).stream().mapToLong(AgentEvent::sequence).max().orElse(0));
  }

  /**
   * 実行状況の通知で用いる直近を返す。
   *
   * <p>Reads from the end of the file; project switching never scans or replays the complete log.
   */
  public synchronized List<AgentEvent> recent(String projectId, int limit) {
    if (limit < 1 || limit > 1000)
      throw new IllegalArgumentException("Event limit must be between 1 and 1000");
    Path file = file(projectId);
    if (!Files.exists(file)) return List.of();
    List<AgentEvent> result = new ArrayList<>();
    try (var input = new RandomAccessFile(file.toFile(), "r")) {
      var reversed = new ByteArrayOutputStream();
      long position = input.length();
      byte[] block = new byte[8192];
      while (position > 0 && result.size() < limit) {
        int length = (int) Math.min(block.length, position);
        position -= length;
        input.seek(position);
        input.readFully(block, 0, length);
        for (int i = length - 1; i >= 0 && result.size() < limit; i--) {
          if (block[i] == '\n') {
            addReversedLine(reversed, result, file);
            reversed.reset();
          } else reversed.write(block[i]);
        }
      }
      if (result.size() < limit) addReversedLine(reversed, result, file);
    } catch (IOException error) {
      log.warn("Cannot restore recent agent events: {}", file, error);
    }
    Collections.reverse(result);
    return List.copyOf(result);
  }

  /**
   * 実行状況の通知で受け渡すページを表す。
   *
   * @param events イベント一覧
   * @param nextOffset 次開始位置
   * @param discardingLine discarding行
   */
  public record Page(List<AgentEvent> events, long nextOffset, boolean discardingLine) {}

  /**
   * 必要な内容を参照できるよう、ページを読み取る。
   *
   * <p>Append-only replay: at most 256 KiB / 128 lines per call, with a 64 KiB line cap.
   */
  public synchronized Page readPage(String projectId, long offset, boolean discardingLine) {
    if (offset < 0) throw new IllegalArgumentException("Negative event cursor");
    Path file = file(projectId);
    if (!Files.exists(file)) return new Page(List.of(), 0, false);
    try (var input = new RandomAccessFile(file.toFile(), "r")) {
      if (offset > input.length()) {
        offset = 0;
        discardingLine = false;
      }
      input.seek(offset);
      byte[] bytes = new byte[(int) Math.min(256 * 1024, input.length() - offset)];
      input.readFully(bytes);
      var events = new ArrayList<AgentEvent>();
      int lineStart = 0, lines = 0;
      for (int i = 0; i < bytes.length; i++) {
        if (i - lineStart >= 64 * 1024) discardingLine = true;
        if (bytes[i] == '\n') {
          if (!discardingLine) {
            var event =
                decode(new String(bytes, lineStart, i - lineStart, StandardCharsets.UTF_8), file);
            if (event != null) events.add(event);
          }
          discardingLine = false;
          lineStart = i + 1;
          if (++lines >= 128) return new Page(List.copyOf(events), offset + lineStart, false);
        }
      }
      // Preserve an incomplete normal line; oversized lines are skipped across page boundaries.
      return new Page(
          List.copyOf(events),
          offset + (discardingLine ? bytes.length : lineStart),
          discardingLine);
    } catch (IOException error) {
      throw new IllegalStateException("Cannot page persisted agent events", error);
    }
  }

  /**
   * 実行状況の通知で用いる参照先状態を返す。
   *
   * <p>Forward pagination is separate from normal Shell restoration.
   */
  public Map<String, String> referenceStatus(String project, Set<String> ids) {
    var result = new HashMap<String, String>();
    ids.forEach(id -> result.put(id, "not-found"));
    if (ids.isEmpty()) return result;
    Path path = file(project);
    if (!Files.exists(path)) return result;
    try {
      if (Files.size(path) > 8 * 1024 * 1024) {
        ids.forEach(id -> result.put(id, "requires-history-lookup"));
        for (var event : recent(project, 1000))
          if (ids.contains(event.id())) result.put(event.id(), "available");
      } else
        try (var reader = Files.newBufferedReader(path)) {
          String line;
          while ((line = reader.readLine()) != null) {
            if (Thread.currentThread().isInterrupted())
              throw new java.util.concurrent.CancellationException();
            var event = decode(line, path);
            if (event != null && ids.contains(event.id())) result.put(event.id(), "available");
          }
        }
    } catch (IOException error) {
      ids.forEach(id -> result.put(id, "unavailable"));
    }
    return Map.copyOf(result);
  }

  /** 必要な内容を参照できるよう、変更後を読み取る。 */
  public List<AgentEvent> readAfter(String projectId, long afterSequence, int limit) {
    if (limit < 1 || limit > 1000)
      throw new IllegalArgumentException("Event limit must be between 1 and 1000");
    Path file = file(projectId);
    if (!Files.exists(file)) return List.of();
    List<AgentEvent> result = new ArrayList<>();
    try (var reader = Files.newBufferedReader(file)) {
      String line;
      while ((line = reader.readLine()) != null && result.size() < limit) {
        var event = decode(line, file);
        if (event != null && event.sequence() > afterSequence) result.add(event);
      }
    } catch (IOException error) {
      log.warn("Cannot replay agent events: {}", file, error);
    }
    return List.copyOf(result);
  }

  /** 新たな対象を管理できるよう、Reversed行を追加する。 */
  private void addReversedLine(ByteArrayOutputStream reversed, List<AgentEvent> result, Path file) {
    byte[] bytes = reversed.toByteArray();
    for (int i = 0, j = bytes.length - 1; i < j; i++, j--) {
      byte value = bytes[i];
      bytes[i] = bytes[j];
      bytes[j] = value;
    }
    var event = decode(new String(bytes, StandardCharsets.UTF_8), file);
    if (event != null) result.add(event);
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  private AgentEvent decode(String line, Path file) {
    if (line.isBlank()) return null;
    try {
      return mapper.readValue(line, AgentEvent.class);
    } catch (IOException error) {
      log.warn(
          "Skipping malformed or unsupported agent event in {} ({})",
          file,
          error.getClass().getSimpleName());
      return null;
    }
  }

  /** 実行状況の通知で用いるファイルを返す。 */
  private Path file(String id) {
    UUID.fromString(id);
    return base.resolve("projects").resolve(id).resolve("events/events.jsonl");
  }
}
