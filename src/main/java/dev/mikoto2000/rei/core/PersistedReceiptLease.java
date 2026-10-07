package dev.mikoto2000.rei.core;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Process identity and in-process liveness for the existing review, repair and single-file receipt tables. */
final class PersistedReceiptLease {
  private static final Set<String> ACTIVE=java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final JdbcClient db;private final String table,namespace,activeStatus;private final Clock clock;
  PersistedReceiptLease(DataSource source,String table,Clock clock){
    if(!Set.of("patch_requirement_reviews","diagnosed_repairs","text_change_sets").contains(table))throw new IllegalArgumentException("Receipt table required");this.table=table;this.activeStatus=table.equals("text_change_sets")?"APPLYING":"STARTED";this.clock=clock;db=JdbcClient.create(source);
    try(var connection=source.getConnection()){namespace=connection.getMetaData().getURL()+":"+table+":";}catch(java.sql.SQLException failure){throw new IllegalStateException("Receipt storage identity unavailable",failure);}
    var columns=db.sql("PRAGMA table_info("+table+")").query((rs,n)->rs.getString("name")).list();for(String column:List.of("pid INTEGER","process_start TEXT","heartbeat INTEGER"))if(!columns.contains(column.split(" ")[0]))db.sql("ALTER TABLE "+table+" ADD COLUMN "+column).update();reconcile(null);
  }
  AutoCloseableLease activate(String id){String key=namespace+id;if(!ACTIVE.add(key))throw new IllegalStateException("Receipt operation already active");try{if(db.sql("UPDATE "+table+" SET pid=?,process_start=?,heartbeat=? WHERE id=? AND status='"+activeStatus+"'").params(ProcessHandle.current().pid(),start(),clock.millis(),id).update()!=1)throw new IllegalStateException("Receipt claim lost; inspect without replay");return new AutoCloseableLease(key);}catch(RuntimeException failure){ACTIVE.remove(key);throw failure;}}
  final class AutoCloseableLease implements AutoCloseable{private final String key;AutoCloseableLease(String key){this.key=key;}public void close(){ACTIVE.remove(key);}}
  void heartbeat(String id){if(db.sql("UPDATE "+table+" SET heartbeat=? WHERE id=? AND status='"+activeStatus+"' AND pid=? AND process_start=?").params(clock.millis(),id,ProcessHandle.current().pid(),start()).update()!=1)throw new IllegalStateException("Receipt execution lease lost");}
  void reconcile(String id){record Row(String id,long pid,String start){}String query="SELECT id,COALESCE(pid,0) AS pid,COALESCE(process_start,'') AS process_start FROM "+table+" WHERE status='"+activeStatus+"'";var statement=db.sql(query+(id==null?" LIMIT 4096":" AND id=?"));if(id!=null)statement=statement.param(id);for(var row:statement.query((rs,n)->new Row(rs.getString("id"),rs.getLong("pid"),rs.getString("process_start"))).list()){boolean alive=false;try{alive=row.pid()==ProcessHandle.current().pid()?start().equals(row.start())&&ACTIVE.contains(namespace+row.id()):ProcessHandle.of(row.pid()).filter(ProcessHandle::isAlive).flatMap(process->process.info().startInstant()).map(instant->instant.toString().equals(row.start())).orElse(false);}catch(RuntimeException unavailable){alive=false;}if(!alive)db.sql("UPDATE "+table+" SET status='UNKNOWN',heartbeat=? WHERE id=? AND status='"+activeStatus+"' AND COALESCE(pid,0)=? AND COALESCE(process_start,'')=?").params(clock.millis(),row.id(),row.pid(),row.start()).update();}}
  private static String start(){return ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("unknown");}
}
