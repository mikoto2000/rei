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
  public record Outgoing(String id,String team,String channel,String thread,String text) {}
  @FunctionalInterface private interface Work<T>{T run(Connection c)throws Exception;}
  public SlackStore(Path root) {
    try{Files.createDirectories(root);}catch(java.io.IOException e){throw new IllegalStateException("Slack storage unavailable");}
    url="jdbc:sqlite:"+root.toAbsolutePath().resolve("slack.db");
    tx(c->{try(var s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS slack_events(event_id TEXT PRIMARY KEY,message_key TEXT UNIQUE NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,run TEXT,project TEXT,session TEXT)");
      s.execute("CREATE TABLE IF NOT EXISTS slack_threads(team TEXT,channel TEXT,thread TEXT,project TEXT NOT NULL,session TEXT NOT NULL,PRIMARY KEY(team,channel,thread))");
      s.execute("CREATE TABLE IF NOT EXISTS slack_outbox(id TEXT PRIMARY KEY,team TEXT NOT NULL,channel TEXT NOT NULL,thread TEXT NOT NULL,text TEXT NOT NULL,state TEXT NOT NULL,due INTEGER NOT NULL,posted_ts TEXT)");
      s.execute("CREATE INDEX IF NOT EXISTS slack_outbox_pending ON slack_outbox(state,due)");
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
  public ThreadBinding bind(SlackMessage m,String project,String session) {
    tx(c->{update(c,"INSERT OR IGNORE INTO slack_threads VALUES(?,?,?,?,?)",m.team(),m.channel(),m.thread(),project,session);return null;});
    return thread(m).orElseThrow();
  }
  private Receipt receipt(ResultSet r)throws Exception {
    return new Receipt(json.readValue(r.getString("payload"),SlackMessage.class),r.getString("state"),r.getString("run"),r.getString("project"),r.getString("session"));
  }
  public List<Receipt> pending(){return receipts("state='ACCEPTED'",256);}
  public List<Receipt> active(){return receipts("state IN ('DISPATCHING','ACTIVE','WAITING_APPROVAL','CANCEL_REQUESTED')",256);}
  private List<Receipt> receipts(String where,int limit) {
    return tx(c->{var result=new ArrayList<Receipt>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM slack_events WHERE "+where+" ORDER BY rowid LIMIT "+limit)){while(r.next())result.add(receipt(r));}return result;});
  }
  public Optional<Receipt> byRun(String run) {
    return tx(c->{try(var s=c.prepareStatement("SELECT * FROM slack_events WHERE run=?")){s.setString(1,run);try(var r=s.executeQuery()){return r.next()?Optional.of(receipt(r)):Optional.empty();}}});
  }
  public Optional<Receipt> latest(SlackMessage m) {
    return tx(c->{try(var s=c.prepareStatement("SELECT * FROM slack_events WHERE run IS NOT NULL AND json_extract(payload,'$.team')=? AND json_extract(payload,'$.channel')=? AND COALESCE(NULLIF(json_extract(payload,'$.threadTs'),''),json_extract(payload,'$.ts'))=? ORDER BY rowid DESC LIMIT 1")) {
      s.setString(1,m.team());s.setString(2,m.channel());s.setString(3,m.thread());try(var r=s.executeQuery()){return r.next()?Optional.of(receipt(r)):Optional.empty();}
    }});
  }
  /** Durable uncertainty fence before any enqueue: replay never creates another Run. */
  public boolean prepare(SlackMessage m,String run,ThreadBinding binding) {
    return tx(c->update(c,"UPDATE slack_events SET state='DISPATCHING',run=?,project=?,session=? WHERE message_key=? AND state='ACCEPTED'",run,binding.project(),binding.session(),m.key())==1);
  }
  public void state(SlackMessage m,String state) {tx(c->{update(c,"UPDATE slack_events SET state=? WHERE message_key=?",state,m.key());return null;});}
  public void reply(String id,SlackMessage m,String text) {
    tx(c->{update(c,"INSERT OR IGNORE INTO slack_outbox VALUES(?,?,?,?,?,'PENDING',0,NULL)",id,m.team(),m.channel(),m.thread(),text);return null;});
  }
  public List<Outgoing> outbox(long now) {
    return tx(c->{var rows=new ArrayList<Outgoing>();try(var s=c.prepareStatement("SELECT * FROM slack_outbox WHERE state='PENDING' AND due<=? ORDER BY rowid LIMIT 32")) {
      s.setLong(1,now);try(var r=s.executeQuery()){while(r.next())rows.add(new Outgoing(r.getString("id"),r.getString("team"),r.getString("channel"),r.getString("thread"),r.getString("text")));}
    }return rows;});
  }
  public boolean claimSend(String id){return tx(c->update(c,"UPDATE slack_outbox SET state='SENDING' WHERE id=? AND state='PENDING'",id)==1);}
  public void sent(String id,String ts){tx(c->{update(c,"UPDATE slack_outbox SET state='SENT',posted_ts=? WHERE id=? AND state='SENDING'",ts,id);return null;});}
  public void retry(String id,long due){tx(c->{update(c,"UPDATE slack_outbox SET state='PENDING',due=? WHERE id=? AND state='SENDING'",due,id);return null;});}
  public void sendFailed(String id,boolean unknown){tx(c->{update(c,"UPDATE slack_outbox SET state=? WHERE id=? AND state='SENDING'",unknown?"UNKNOWN":"FAILED",id);return null;});}
}
