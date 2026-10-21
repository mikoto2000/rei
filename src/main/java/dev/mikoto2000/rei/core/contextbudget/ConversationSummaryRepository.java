package dev.mikoto2000.rei.core.contextbudget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会話要約を保存・検索するための窓口。
 *
 * <p>Atomic project-local snapshots. Bounded striped locks serialize read/generate/commit across
 * runs.
 */
public class ConversationSummaryRepository {
  /** 基準。 */
  private final Path base;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  /** 排他制御一覧。 */
  private final ReentrantLock[] locks =
      java.util.stream.IntStream.range(0, 64)
          .mapToObj(i -> new ReentrantLock())
          .toArray(ReentrantLock[]::new);

  /** 指定された値や利用先を使って、ConversationSummaryRepositoryを初期化する。 */
  public ConversationSummaryRepository(Path base) {
    this.base = base;
  }

  /** モデルへ渡す文脈の管理で用いる排他制御を返す。 */
  public ReentrantLock lock(String id) {
    return locks[Math.floorMod(id.hashCode(), locks.length)];
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  public ConversationSummary read(String id) {
    Path file = file(id);
    if (!Files.exists(file)) return ConversationSummary.empty();
    try {
      return mapper.readValue(Files.readString(file), ConversationSummary.class);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot read context summary", e);
    }
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  public void save(String id, ConversationSummary summary) {
    if (summary.throughSequence() <= read(id).throughSequence()) return;
    try {
      ContextFiles.write(file(id), mapper.writeValueAsString(summary));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot encode context summary", e);
    }
  }

  /** モデルへ渡す文脈の管理で用いるファイルを返す。 */
  private Path file(String id) {
    return ContextFiles.directory(base, id).resolve("summaries/" + ContextFiles.key(id) + ".json");
  }

  /** 依頼された操作を進めるため、照合キーを実行する。 */
  public static String runKey(String conversation, String run) {
    return conversation + "/run/" + run;
  }
}
