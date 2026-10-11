package dev.mikoto2000.rei.slack;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Small channel-specific SQLite ledger. No network or runner calls occur in transactions. */
public final class SlackStore {
  private final String url;
  private final ObjectMapper json=new ObjectMapper();
  public record ThreadBinding(String project,String session) {}
  public record Receipt(SlackMessage message,String state,String run,String project,String session) {}
  public record Outgoing(String id,String team,String channel,String thread,String text,String requester) {
    public Outgoing(String id,String team,String channel,String thread,String text){this(id,team,channel,thread,text,null);}
  }
  private static final String RUN_ROWS="SELECT e.payload,r.state,r.run,r.project,r.session FROM slack_runs r JOIN slack_events e ON e.message_key=r.source_key";
  @FunctionalInterface private interface Work<T>{T run(Connection c)throws Exception;}
  public SlackStore(Path root) {
    try{Files.createDirectories(root);}catch(java.io.IOException e){throw new IllegalStateException("Slack storage unavailable");}
    url="jdbc:sqlite:"+root.toAbsolutePath().resolve("slack.db");
    tx(c->{try(var s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS slack_events(event_id TEXT PRIMARY KEY,message_key TEXT UNIQUE NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,run TEXT,project TEXT,session TEXT)");
      s.execute("CREATE TABLE IF NOT EXISTS slack_threads(team TEXT,channel TEXT,thread TEXT,project TEXT NOT NULL,session TEXT NOT NULL,PRIMARY KEY(team,channel,thread))");
      s.execute("CREATE TABLE IF NOT EXISTS slack_outbox(id TEXT PRIMARY KEY,team TEXT NOT NULL,channel TEXT NOT NULL,thread TEXT NOT NULL,text TEXT NOT NULL,state TEXT NOT NULL,due INTEGER NOT NULL,posted_ts TEXT)");
      s.execute("CREATE INDEX IF NOT EXISTS slack_outbox_pending ON slack_outbox(state,due)");
      s.execute("CREATE TABLE IF NOT EXISTS slack_runs(run TEXT PRIMARY KEY,source_key TEXT NOT NULL,project TEXT NOT NULL,session TEXT NOT NULL,state TEXT NOT NULL)");
      s.execute("CREATE TABLE IF NOT EXISTS slack_current(team TEXT,channel TEXT,thread TEXT,run TEXT NOT NULL,PRIMARY KEY(team,channel,thread))");
      boolean requester=false;try(var rows=s.executeQuery("PRAGMA table_info(slack_outbox)")){while(rows.next())requester|="requester".equals(rows.getString("name"));}
      if(!requester)s.execute("ALTER TABLE slack_outbox ADD COLUMN requester TEXT");
      s.execute("INSERT OR IGNORE INTO slack_runs SELECT run,message_key,project,session,state FROM slack_events WHERE run IS NOT NULL");
      s.execute("INSERT OR IGNORE INTO slack_current SELECT json_extract(payload,'$.team'),json_extract(payload,'$.channel'),COALESCE(NULLIF(json_extract(payload,'$.threadTs'),''),json_extract(payload,'$.ts')),run FROM slack_events WHERE run IS NOT NULL ORDER BY rowid DESC");
    }return null;});
  }
  private <T>T tx(Work<T> work) {
    try(var c=DriverManager.getConnection(url)) {
      try(var s=c.createStatement()){s.execute("PRAGMA busy_timeout=1500");s.execute("PRAGMA synchronous=FULL");}
      c.setAutoCommit(false);
      try{var result=work.run(c);c.commit();return result;}catch(Exception|Error e){c.rollback();throw e;}
    }catch(Exception e){throw new IllegalStateException("Slack storage operation failed");}
  }
  private static int update(Connection c,String sql,Object... values)throws SQLException {
    try(var s=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);return s.executeUpdate();}
  }
  public boolean accept(SlackMessage m) {
    return tx(c->update(c,"INSERT OR IGNORE INTO slack_events(event_id,message_key,payload,state) VALUES(?,?,?,'ACCEPTED')",m.eventId(),m.key(),json.writeValueAsString(m))==1);
  }
  public Optional<ThreadBinding> thread(SlackMessage m) {
    return tx(c->{try(var s=c.prepareStatement("SELECT project,session FROM slack_threads WHERE team=? AND channel=? AND thread=?")) {
      s.setString(1,m.team());s.setString(2,m.channel());s.setString(3,m.thread());try(var r=s.executeQuery()){return r.next()?Optional.of(new ThreadBinding(r.getString(1),r.getString(2))):Optional.empty();}
    }});
  }
  public boolean knownThread(SlackMessage m) {
    if(thread(m).isPresent())return true;
    return tx(c->{try(var s=c.prepareStatement("SELECT 1 FROM slack_events WHERE state='ACCEPTED' AND json_extract(payload,'$.team')=? AND json_extract(payload,'$.channel')=? AND COALESCE(NULLIF(json_extract(payload,'$.threadTs'),''),json_extract(payload,'$.ts'))=? LIMIT 1")) {
      s.setString(1,m.team());s.setString(2,m.channel());s.setString(3,m.thread());try(var r=s.executeQuery()){return r.next();}
    }});
  }
  public ThreadBinding bind(SlackMessage m,String project,String session) {
    tx(c->{update(c,"INSERT OR IGNORE INTO slack_threads VALUES(?,?,?,?,?)",m.team(),m.channel(),m.thread(),project,session);return null;});
    return thread(m).orElseThrow();
  }
  private Receipt receipt(ResultSet r)throws Exception {
    return new Receipt(json.readValue(r.getString("payload"),SlackMessage.class),r.getString("state"),r.getString("run"),r.getString("project"),r.getString("session"));
  }
  public List<Receipt> pending(){return receipts("state='ACCEPTED'",256);}
  public List<Receipt> active(){return tx(c->{var rows=new ArrayList<Receipt>();try(var s=c.createStatement();var r=s.executeQuery(RUN_ROWS+" WHERE r.state IN ('DISPATCHING','ACTIVE','CANCEL_REQUESTED') ORDER BY r.rowid LIMIT 256")){while(r.next())rows.add(receipt(r));}return rows;});}
  private List<Receipt> receipts(String where,int limit) {
    return tx(c->{var result=new ArrayList<Receipt>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM slack_events WHERE "+where+" ORDER BY rowid LIMIT "+limit)){while(r.next())result.add(receipt(r));}return result;});
  }
  public Optional<Receipt> byRun(String run) {
    return tx(c->{try(var s=c.prepareStatement(RUN_ROWS+" WHERE r.run=?")){s.setString(1,run);try(var r=s.executeQuery()){return r.next()?Optional.of(receipt(r)):Optional.empty();}}});
  }
  public Optional<SlackMessage> forSession(String project,String session) {
    return tx(c->{try(var s=c.prepareStatement("SELECT payload FROM slack_events WHERE project=? AND session=? AND run IS NOT NULL ORDER BY rowid LIMIT 1")) {
      s.setString(1,project);s.setString(2,session);try(var r=s.executeQuery()){return r.next()?Optional.of(json.readValue(r.getString(1),SlackMessage.class)):Optional.empty();}
    }});
  }
  public boolean transition(SlackMessage m,String from,String to) {
    return tx(c->update(c,"UPDATE slack_events SET state=? WHERE message_key=? AND state=?",to,m.key(),from)==1);
  }
  public Optional<Receipt> latest(SlackMessage m) {
    return tx(c->{try(var s=c.prepareStatement(RUN_ROWS+" JOIN slack_current t ON t.run=r.run WHERE t.team=? AND t.channel=? AND t.thread=?")) {
      s.setString(1,m.team());s.setString(2,m.channel());s.setString(3,m.thread());try(var r=s.executeQuery()){return r.next()?Optional.of(receipt(r)):Optional.empty();}
    }});
  }
  /** Durable uncertainty fence before any enqueue: replay never creates another Run. */
  public boolean prepare(SlackMessage m,String run,ThreadBinding binding) {
    return tx(c->{
      if(update(c,"UPDATE slack_events SET state='DISPATCHED',run=?,project=?,session=? WHERE message_key=? AND state='ACCEPTED'",run,binding.project(),binding.session(),m.key())!=1)return false;
      update(c,"INSERT INTO slack_runs VALUES(?,?,?,?,'DISPATCHING')",run,m.key(),binding.project(),binding.session());current(c,m,run);return true;
    });
  }
  private static void current(Connection c,SlackMessage m,String run)throws SQLException {
    update(c,"INSERT INTO slack_current VALUES(?,?,?,?) ON CONFLICT(team,channel,thread) DO UPDATE SET run=excluded.run",m.team(),m.channel(),m.thread(),run);
  }
  public void adoptRun(SlackMessage m,String run,String project,String session) {
    tx(c->{update(c,"UPDATE slack_runs SET state='RESUMED' WHERE run=(SELECT run FROM slack_current WHERE team=? AND channel=? AND thread=?) AND state='WAITING_APPROVAL'",m.team(),m.channel(),m.thread());
      update(c,"INSERT OR IGNORE INTO slack_runs VALUES(?,?,?,?,'ACTIVE')",run,m.key(),project,session);current(c,m,run);return null;});
  }
  public void activateRun(String run){tx(c->{update(c,"UPDATE slack_runs SET state='ACTIVE' WHERE run=? AND state='DISPATCHING'",run);return null;});}
  public void stateRun(String run,String state){tx(c->{update(c,"UPDATE slack_runs SET state=? WHERE run=?",state,run);return null;});}
  public void state(SlackMessage m,String state) {tx(c->{update(c,"UPDATE slack_events SET state=? WHERE message_key=?",state,m.key());return null;});}
  public void reply(String id,SlackMessage m,String text) {
    replyParts(id,m,List.of(text));
  }
  private static void insertReply(Connection c,String id,SlackMessage m,String text)throws SQLException {
    update(c,"INSERT OR IGNORE INTO slack_outbox(id,team,channel,thread,text,state,due,requester) VALUES(?,?,?,?,?,'PENDING',0,?)",id,m.team(),m.channel(),m.thread(),text,m.user());
  }
  public void replyParts(String id,SlackMessage m,List<String> parts) {
    tx(c->{for(int i=0;i<parts.size();i++)insertReply(c,parts.size()==1?id:id+":"+i,m,parts.get(i));return null;});
  }
  public void notifyRun(String run,String state,String id,SlackMessage m,List<String> parts) {
    tx(c->{update(c,"UPDATE slack_runs SET state=? WHERE run=?",state,run);
      for(int i=0;i<parts.size();i++)insertReply(c,parts.size()==1?id:id+":"+i,m,parts.get(i));return null;});
  }
  private static Outgoing outgoing(ResultSet r)throws SQLException {return new Outgoing(r.getString("id"),r.getString("team"),r.getString("channel"),r.getString("thread"),r.getString("text"),r.getString("requester"));}
  public List<Outgoing> replies() {
    return tx(c->{var rows=new ArrayList<Outgoing>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM slack_outbox ORDER BY rowid LIMIT 256")){while(r.next())rows.add(outgoing(r));}return rows;});
  }
  public void recoverSending(){tx(c->{update(c,"UPDATE slack_outbox SET state='UNKNOWN' WHERE state='SENDING'");return null;});}
  public void suppress(String id){tx(c->{update(c,"UPDATE slack_outbox SET state='SUPPRESSED' WHERE id=? AND state='PENDING'",id);return null;});}
  public List<Outgoing> outbox(long now) {
    return tx(c->{var rows=new ArrayList<Outgoing>();try(var s=c.prepareStatement("SELECT * FROM slack_outbox a WHERE state='PENDING' AND due<=? AND NOT EXISTS (SELECT 1 FROM slack_outbox b WHERE b.team=a.team AND b.channel=a.channel AND b.rowid<a.rowid AND b.state IN ('PENDING','SENDING')) ORDER BY rowid LIMIT 32")) {
      s.setLong(1,now);try(var r=s.executeQuery()){while(r.next())rows.add(outgoing(r));}
    }return rows;});
  }
  public boolean claimSend(String id){return tx(c->update(c,"UPDATE slack_outbox SET state='SENDING' WHERE id=? AND state='PENDING'",id)==1);}
  public void sent(String id,String ts){tx(c->{update(c,"UPDATE slack_outbox SET state='SENT',posted_ts=? WHERE id=? AND state='SENDING'",ts,id);return null;});}
  public void retry(String id,long due){tx(c->{update(c,"UPDATE slack_outbox SET state='PENDING',due=? WHERE id=? AND state='SENDING'",due,id);return null;});}
  public void sendFailed(String id,boolean unknown){tx(c->{update(c,"UPDATE slack_outbox SET state=? WHERE id=? AND state='SENDING'",unknown?"UNKNOWN":"FAILED",id);return null;});}
}
