package dev.mikoto2000.rei.activity;

import java.sql.*;
import java.time.Instant;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Activity journal-wide preferences; no Project ownership is inferred from screen candidates. */
public final class SqlitePeriodCoachingStore implements PeriodCoachingStore {
  private final DataSource source;
  private final ObjectMapper mapper=new ObjectMapper();
  private boolean initialized;
  public SqlitePeriodCoachingStore(DataSource source){this.source=source;}
  private synchronized void initialize() throws Exception {
    if(initialized)return;
    try(var c=source.getConnection();var s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS activity_period_coaching_settings (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, payload TEXT NOT NULL)");
      s.execute("CREATE TABLE IF NOT EXISTS activity_period_coaching_receipts (period_key TEXT PRIMARY KEY, reason TEXT NOT NULL, shown_at INTEGER NOT NULL, settings_revision INTEGER NOT NULL)");
      s.execute("CREATE INDEX IF NOT EXISTS activity_period_coaching_receipts_time ON activity_period_coaching_receipts(shown_at)");
      try(var insert=c.prepareStatement("INSERT OR IGNORE INTO activity_period_coaching_settings VALUES(1,0,?)")) {insert.setString(1,mapper.writeValueAsString(PeriodCoaching.Settings.defaults()));insert.executeUpdate();}
      initialized=true;
    }
  }
  private Snapshot read(Connection c) throws Exception {
    try(var s=c.createStatement();var r=s.executeQuery("SELECT revision,payload FROM activity_period_coaching_settings WHERE id=1")) {
      if(!r.next())throw new IllegalStateException("Missing coaching settings");
      return new Snapshot(r.getLong(1),mapper.readValue(r.getString(2),PeriodCoaching.Settings.class));
    }
  }
  @Override public Snapshot load(){try {initialize();try(var c=source.getConnection()){return read(c);}}catch(Exception e){throw failure(e);}}
  private interface Operation<T>{T apply(Connection c) throws Exception;}
  private <T> T transaction(Operation<T> operation) {
    try {initialize();try(var c=source.getConnection();var s=c.createStatement()) {
      s.execute("PRAGMA busy_timeout=5000");s.execute("BEGIN IMMEDIATE");
      try {var value=operation.apply(c);s.execute("COMMIT");return value;}
      catch(Exception e){s.execute("ROLLBACK");throw e;}
    }}catch(Exception e){throw failure(e);}
  }
  private Snapshot write(Connection c,PeriodCoaching.Settings settings) throws Exception {
    try(var s=c.prepareStatement("UPDATE activity_period_coaching_settings SET revision=revision+1,payload=? WHERE id=1")){s.setString(1,mapper.writeValueAsString(settings));s.executeUpdate();}
    return read(c);
  }
  @Override public Snapshot configure(PeriodCoaching.Settings settings){return transaction(c->write(c,settings));}
  @Override public Snapshot setEnabled(boolean enabled){return transaction(c->write(c,read(c).settings().withEnabled(enabled)));}
  @Override public String reserve(Snapshot expected,String key,String reason,Instant now) {
    if(key==null || key.isBlank() || key.length()>200 || !java.util.Set.of("BELOW_TARGET","BELOW_TARGET_DECLINING").contains(reason))throw new IllegalArgumentException("Invalid coaching reservation");
    return transaction(c->{
      var current=read(c);
      if(!current.equals(expected))return "SETTINGS_CHANGED";
      if(!current.settings().enabled())return "DISABLED";
      try(var s=c.prepareStatement("SELECT 1 FROM activity_period_coaching_receipts WHERE period_key=?")){s.setString(1,key);try(var r=s.executeQuery()){if(r.next())return "ALREADY_SHOWN";}}
      try(var s=c.createStatement();var r=s.executeQuery("SELECT MAX(shown_at) FROM activity_period_coaching_receipts")) {
        if(r.next()) {long last=r.getLong(1);if(!r.wasNull() && now.getEpochSecond()<last+current.settings().cooldownDays()*86400L)return "COOLDOWN";}
      }
      try(var s=c.prepareStatement("INSERT INTO activity_period_coaching_receipts VALUES(?,?,?,?)")) {
        s.setString(1,key);s.setString(2,reason);s.setLong(3,now.getEpochSecond());s.setLong(4,current.revision());s.executeUpdate();
      }
      return "RESERVED";
    });
  }
  private static IllegalStateException failure(Exception e){return new IllegalStateException("Coaching persistence unavailable",e);}
}
