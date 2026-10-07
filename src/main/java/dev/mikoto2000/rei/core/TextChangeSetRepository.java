package dev.mikoto2000.rei.core;

import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Durable single-file proposals and one-way application claims. No automatic recovery writes. */
@Repository
public class TextChangeSetRepository {
  record Saved(String id,String projectId,String root,String path,String baseline,String proposed,
      String baselineHash,String proposedHash,String proposalHash,String status,Instant createdAt) {}
  private final JdbcClient db;
  public TextChangeSetRepository(@Qualifier("memoryConsolidationDataSource") DataSource source) {
    db=JdbcClient.create(source);
    db.sql("CREATE TABLE IF NOT EXISTS text_change_sets(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,root TEXT NOT NULL,path TEXT NOT NULL,baseline TEXT NOT NULL,proposed TEXT NOT NULL,baseline_hash TEXT NOT NULL,proposed_hash TEXT NOT NULL,proposal_hash TEXT NOT NULL,status TEXT NOT NULL,created_at TEXT NOT NULL)").update();
    db.sql("CREATE TABLE IF NOT EXISTS text_change_set_apply_guards(project_id TEXT NOT NULL,id TEXT NOT NULL,guard TEXT NOT NULL,PRIMARY KEY(project_id,id))").update();
    db.sql("CREATE TABLE IF NOT EXISTS text_document_change_sets(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,payload TEXT NOT NULL,sha TEXT NOT NULL,status TEXT NOT NULL,phase TEXT NOT NULL DEFAULT 'PROPOSED',stages TEXT NOT NULL DEFAULT '[]',warnings TEXT NOT NULL DEFAULT '[]',pid INTEGER,process_start TEXT,heartbeat TEXT,recoveries INTEGER NOT NULL DEFAULT 0)").update();
    db.sql("CREATE UNIQUE INDEX IF NOT EXISTS text_document_active_root ON text_document_change_sets(root) WHERE status IN ('APPLYING','ROLLING_BACK','UNKNOWN')").update();
  }
  org.springframework.jdbc.core.simple.JdbcClient documentsDb(){return db;}
  void protectDiagnosed(String project,String id){db.sql("INSERT INTO text_change_set_apply_guards VALUES(?,?,'DIAGNOSED_REPAIR')").params(project,id).update();}
  String applyGuard(String project,String id){return db.sql("SELECT guard FROM text_change_set_apply_guards WHERE project_id=? AND id=?").params(project,id).query(String.class).optional().orElse(null);}
  void save(Saved saved) {
    // Bounded per-owner history. Pending/uncertain proposals are never silently evicted.
    db.sql("DELETE FROM text_change_sets WHERE project_id=? AND status IN ('APPLIED','STALE','DISCARDED') AND id NOT IN (SELECT id FROM text_change_sets WHERE project_id=? ORDER BY created_at DESC,id DESC LIMIT 100)")
        .params(saved.projectId(),saved.projectId()).update();
    int added=db.sql("INSERT INTO text_change_sets SELECT ?,?,?,?,?,?,?,?,?,?,? WHERE (SELECT count(*) FROM text_change_sets WHERE project_id=?)<128")
        .params(saved.id(),saved.projectId(),saved.root(),saved.path(),saved.baseline(),saved.proposed(),saved.baselineHash(),saved.proposedHash(),saved.proposalHash(),saved.status(),saved.createdAt().toString(),saved.projectId()).update();
    if(added!=1)throw new IllegalStateException("Change Set capacity reached; inspect existing proposals");
  }
  Saved get(String project,String id) {
    return db.sql("SELECT * FROM text_change_sets WHERE project_id=? AND id=?").params(project,id).query((row,n)->new Saved(
        row.getString("id"),row.getString("project_id"),row.getString("root"),row.getString("path"),row.getString("baseline"),row.getString("proposed"),
        row.getString("baseline_hash"),row.getString("proposed_hash"),row.getString("proposal_hash"),row.getString("status"),Instant.parse(row.getString("created_at"))))
        .optional().orElseThrow(()->new IllegalArgumentException("Unknown Change Set in this Project"));
  }
  boolean transition(String project,String id,String before,String after) {
    if(before.equals("PROPOSED")&&after.equals("APPLYING"))return db.sql("UPDATE text_change_sets SET status=? WHERE project_id=? AND id=? AND status=? AND NOT EXISTS (SELECT 1 FROM text_document_change_sets d WHERE d.root=text_change_sets.root AND d.status IN ('APPLYING','ROLLING_BACK','UNKNOWN'))").params(after,project,id,before).update()==1;
    return db.sql("UPDATE text_change_sets SET status=? WHERE project_id=? AND id=? AND status=?").params(after,project,id,before).update()==1;
  }
}
