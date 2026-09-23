package dev.mikoto2000.rei.activity;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Separate indexed tables, one transaction for raw evidence and its daily session projection. */
public final class SqliteActivityStore implements ActivityStore {
  private final DataSource dataSource;
  private final SessionMergePolicy policy;
  private final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
  private boolean initialized;
  public SqliteActivityStore(DataSource dataSource,SessionMergePolicy policy) { this.dataSource=dataSource; this.policy=policy; }
  private synchronized void initialize() throws SQLException {
    if(initialized) return;
    try(var connection=dataSource.getConnection();var s=connection.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS activity_records (id TEXT PRIMARY KEY, captured_at INTEGER NOT NULL, payload TEXT NOT NULL)");
      s.execute("CREATE INDEX IF NOT EXISTS activity_records_time ON activity_records(captured_at)");
      s.execute("CREATE TABLE IF NOT EXISTS activity_sessions (id TEXT PRIMARY KEY, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL, payload TEXT NOT NULL)");
      s.execute("CREATE INDEX IF NOT EXISTS activity_sessions_time ON activity_sessions(started_at, ended_at)");
      initialized=true;
    }
  }
  @Override public synchronized void append(ActivityRecord record) {
    try {
      initialize();
      try(var c=dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          try(var s=c.prepareStatement("INSERT INTO activity_records VALUES(?,?,?)")) {
            s.setString(1,record.id());s.setLong(2,record.capturedAt().toEpochMilli());s.setString(3,mapper.writeValueAsString(record));s.executeUpdate();
          }
          var date=record.capturedAt().atZone(policy.zone()).toLocalDate();
          var start=date.atStartOfDay(policy.zone()).toInstant(); var end=date.plusDays(1).atStartOfDay(policy.zone()).toInstant();
          var records=records(c,start,end);
          try(var s=c.prepareStatement("DELETE FROM activity_sessions WHERE started_at>=? AND started_at<?")) {
            s.setLong(1,start.toEpochMilli());s.setLong(2,end.toEpochMilli());s.executeUpdate();
          }
          try(var s=c.prepareStatement("INSERT INTO activity_sessions VALUES(?,?,?,?)")) {
            for(var session:policy.aggregate(records)) {
              s.setString(1,session.id());s.setLong(2,session.startedAt().toEpochMilli());s.setLong(3,session.endedAt().toEpochMilli());s.setString(4,mapper.writeValueAsString(session));s.addBatch();
            }
            s.executeBatch();
          }
          c.commit();
        } catch(Exception e) { c.rollback();throw e; }
      }
    } catch(Exception e) { throw new IllegalStateException("Activity persistence failed",e); }
  }
  private List<ActivityRecord> records(Connection c,Instant start,Instant end) throws Exception {
    var records=new ArrayList<ActivityRecord>();
    try(var s=c.prepareStatement("SELECT payload FROM activity_records WHERE captured_at>=? AND captured_at<? ORDER BY captured_at,id")) {
      s.setLong(1,start.toEpochMilli());s.setLong(2,end.toEpochMilli());
      try(var r=s.executeQuery()) { while(r.next()) records.add(mapper.readValue(r.getString(1),ActivityRecord.class)); }
    }
    return records;
  }
  @Override public synchronized List<ActivitySession> findBetween(Instant start,Instant end) {
    if (!start.isBefore(end)) throw new IllegalArgumentException("start must precede end");
    try {
      initialize();
      try(var c=dataSource.getConnection();var s=c.prepareStatement("SELECT payload FROM activity_sessions WHERE started_at<? AND ended_at>? ORDER BY started_at,id")) {
        s.setLong(1,end.toEpochMilli());s.setLong(2,start.toEpochMilli());
        var result=new ArrayList<ActivitySession>();
        try(var r=s.executeQuery()) {
          while(r.next()) {
            var session=mapper.readValue(r.getString(1),ActivitySession.class);
            var from=start.isAfter(session.startedAt())?start:session.startedAt();
            var until=end.isBefore(session.endedAt())?end:session.endedAt();
            long seconds=0; Instant covered=from;
            for(var record:records(c,session.startedAt(),session.endedAt())) {
              var a=record.capturedAt().isAfter(covered)?record.capturedAt():covered;
              var b=record.capturedAt().plusSeconds(record.durationEstimate()); if(b.isAfter(until))b=until;
              if(b.isAfter(a)) {seconds+=Duration.between(a,b).getSeconds();covered=b;}
            }
            result.add(new ActivitySession(session.id(),from,until,seconds,session.recordIds(),session.inference(),session.primaryApplication(),session.confidence()));
          }
        }
        return List.copyOf(result);
      }
    } catch(Exception e) { throw new IllegalStateException("Activity query failed",e); }
  }
  /** Read-only overlap query; original payloads are retained even when their estimated intervals are clipped. */
  @Override public synchronized List<ActivityRecord> findRecordsBetween(Instant start,Instant end) {
    if(!start.isBefore(end)) throw new IllegalArgumentException("start must precede end");
    try {
      initialize();
      try(var c=dataSource.getConnection()) {
        // Estimates cannot cross the journal midnight, so only this day's preceding observations can overlap.
        var lower=start.atZone(policy.zone()).toLocalDate().atStartOfDay(policy.zone()).toInstant();
        var candidates=records(c,lower,end);var result=new ArrayList<ActivityRecord>();
        for(int i=0;i<candidates.size();i++) {
          var record=candidates.get(i);
          var until=record.capturedAt().plusSeconds(record.durationEstimate());
          var midnight=record.capturedAt().atZone(policy.zone()).toLocalDate().plusDays(1).atStartOfDay(policy.zone()).toInstant();
          if(until.isAfter(midnight)) until=midnight;
          if(i+1<candidates.size() && until.isAfter(candidates.get(i+1).capturedAt())) until=candidates.get(i+1).capturedAt();
          if(until.isAfter(start)) result.add(record);
        }
        return List.copyOf(result);
      }
    } catch(Exception e) {throw new IllegalStateException("Activity evidence query failed",e);}
  }
}
