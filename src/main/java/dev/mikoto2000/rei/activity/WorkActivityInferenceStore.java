package dev.mikoto2000.rei.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * 作業作業状況推定の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Additive indexed supplement table. Does not rebuild sessions or mutate raw evidence.
 */
public class WorkActivityInferenceStore {
  /** データベースへの接続元。 */
  private final DataSource ds;

  /** 初期化済み。 */
  private boolean initialized;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

  /** 指定された値や利用先を使って、WorkActivityInferenceStoreを初期化する。 */
  public WorkActivityInferenceStore(DataSource ds) {
    this.ds = ds;
  }

  /** 作業状況の把握に関する初期化を処理する。 */
  private synchronized void initialize() throws Exception {
    if (initialized) return;
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS activity_work_inferences(id TEXT PRIMARY KEY, window_end INTEGER NOT NULL, payload TEXT NOT NULL)");
      s.execute(
          "CREATE INDEX IF NOT EXISTS activity_work_inferences_time ON activity_work_inferences(window_end)");
    }
    initialized = true;
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  public synchronized void save(WorkActivityInference inference) {
    try {
      initialize();
      String payload = json.writeValueAsString(inference);
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (payload.length() > 65536)
        throw new IllegalArgumentException("Inference payload too large");
      try (var c = ds.getConnection();
          var s =
              c.prepareStatement(
                  "INSERT INTO activity_work_inferences VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload")) {
        s.setString(1, inference.id());
        s.setLong(2, inference.windowEnd().toEpochMilli());
        s.setString(3, payload);
        s.executeUpdate();
      }
    } catch (Exception e) {
      throw new IllegalStateException("Temporal inference persistence failed", e);
    }
  }

  /** 作業状況の把握で用いる最新を返す。 */
  public synchronized Optional<WorkActivityInference> latest() {
    try {
      initialize();
      try (var c = ds.getConnection();
          var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT payload FROM activity_work_inferences ORDER BY window_end DESC,id DESC LIMIT 1")) {
        return r.next() ? Optional.of(decode(r.getString(1))) : Optional.empty();
      }
    } catch (Exception e) {
      throw new IllegalStateException("Temporal inference query failed", e);
    }
  }

  /**
   * 対象を特定できるよう、期間を探す。
   *
   * <p>Each rolling inference belongs to its windowEnd instant, so midnight overlap never
   * duplicates it.
   */
  public synchronized List<WorkActivityInference> findBetween(
      Instant start, Instant end, int limit) {
    if (!start.isBefore(end) || limit < 1 || limit > 500)
      throw new IllegalArgumentException("Invalid inference range");
    try {
      initialize();
      var result = new ArrayList<WorkActivityInference>();
      try (var c = ds.getConnection();
          var s =
              c.prepareStatement(
                  "SELECT payload FROM activity_work_inferences WHERE window_end>=? AND window_end<? ORDER BY window_end DESC,id DESC LIMIT ?")) {
        s.setLong(1, start.toEpochMilli());
        s.setLong(2, end.toEpochMilli());
        s.setInt(3, limit);
        try (var r = s.executeQuery()) {
          while (r.next()) result.add(decode(r.getString(1)));
        }
      }
      Collections.reverse(result);
      return List.copyOf(result);
    } catch (Exception e) {
      throw new IllegalStateException("Temporal inference query failed", e);
    }
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  private WorkActivityInference decode(String value) throws Exception {
    if (value == null || value.length() > 65536)
      throw new IllegalArgumentException("Inference payload too large");
    return json.readValue(value, WorkActivityInference.class);
  }
}
