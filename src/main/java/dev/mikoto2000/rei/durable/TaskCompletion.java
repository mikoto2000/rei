package dev.mikoto2000.rei.durable;

import java.sql.Connection;
import java.sql.SQLException;

/** Deterministic structural gate; independent of model completion claims. */
final class TaskCompletion {
  private TaskCompletion() {}

  static DurableTaskStore.Verification verify(Connection db, DurableTaskStore.Task task) throws SQLException {
    if (!task.state().equals("ACTIVE") && !task.state().equals("COMPLETE")) return no(task.state());
    if (!task.enumeration().equals("SEALED") || task.unscannedDirectories() != 0) return no("ENUMERATION_" + task.enumeration());
    if (task.errors() != 0) return no("ENUMERATION_ERRORS");
    if (task.files() == 0) return no("EMPTY_TARGET_SET");
    if (DurableTaskStore.number(db,
        "SELECT COUNT(*) FROM durable_files f WHERE f.snapshot=? AND f.reason='' AND f.error='' AND NOT EXISTS(SELECT 1 FROM durable_work w WHERE w.task=? AND w.snapshot=f.snapshot AND w.path=f.path AND w.version=f.version AND w.kind='LOCAL' AND w.criterion='correctness' AND w.required=1)", task.snapshot(), task.id()) != 0)
      return no("WORK_REGISTRATION_INCOMPLETE");
    if (DurableTaskStore.number(db,
        "SELECT COUNT(*) FROM durable_work w LEFT JOIN durable_results r ON r.work=w.id WHERE w.task=? AND w.required=1 AND (w.state<>'COMPLETE' OR r.work IS NULL OR w.result_id IS NULL OR w.result_id<>r.work OR w.snapshot<>? OR r.snapshot<>w.snapshot OR r.path<>w.path OR r.start<>w.start OR r.end<>w.end OR r.version<>w.version OR r.criterion<>w.criterion)", task.id(), task.snapshot()) != 0)
      return no("WORK_PENDING");
    if (DurableTaskStore.number(db,
        "SELECT COUNT(*) FROM durable_work w LEFT JOIN durable_files f ON f.snapshot=w.snapshot AND f.path=w.path WHERE w.task=? AND w.required=1 AND (f.path IS NULL OR f.version<>w.version OR w.start<0 OR w.end<w.start OR w.end>f.chars)", task.id()) != 0)
      return no("WORK_RANGE_INVALID");
    if (DurableTaskStore.number(db,
        "WITH spans AS (SELECT w.start,w.end,MAX(w.end) OVER(PARTITION BY w.path,w.criterion ORDER BY w.start,w.end,w.id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS previous FROM durable_work w WHERE w.task=? AND w.required=1 AND w.kind='LOCAL') SELECT COUNT(*) FROM spans WHERE (previous IS NULL AND start<>0) OR start>previous", task.id()) != 0)
      return no("COVERAGE_GAP");
    if (DurableTaskStore.number(db,
        "SELECT COUNT(*) FROM (SELECT w.path,w.criterion,MAX(w.end) AS covered,f.chars FROM durable_work w JOIN durable_files f ON f.snapshot=w.snapshot AND f.path=w.path WHERE w.task=? AND w.required=1 AND w.kind='LOCAL' GROUP BY w.path,w.criterion HAVING covered<>f.chars)", task.id()) != 0)
      return no("COVERAGE_GAP");
    return new DurableTaskStore.Verification(true, "WORK_VERIFIED");
  }

  private static DurableTaskStore.Verification no(String reason) {
    return new DurableTaskStore.Verification(false, reason);
  }
}
