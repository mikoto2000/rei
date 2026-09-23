package dev.mikoto2000.rei.activity;

import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Metadata only, separate from ActivityRecord. Observation IDs make enrichment idempotent. */
public final class ClassificationTelemetryRepository {
  public record Row(String id,Instant at,String process,String title,String application,String service,String content,String category,
      ClassificationDiagnostics diagnostics,boolean visionAttempted,boolean visionSuccess,String visionFailure,boolean initialVisionRequired) {}
  public record Candidate(String key,String process,String title,String application,String service,String content,String category,
      long count,Instant firstSeen,Instant lastSeen,long open,String state,long visionAttempted,long visionSuccess,
      Map<String,Long> outcomes,List<String> reasons,ActivityFieldConfidence confidence,String visionFailure,
      EntertainmentDisposition disposition,double entertainmentConfidence,String entertainmentRule) {}
  private final DataSource ds;
  private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
  private boolean initialized;
  public ClassificationTelemetryRepository(DataSource ds){this.ds=ds;}
  private void initialize() throws Exception {
    if(initialized)return;
    try(var c=ds.getConnection();var s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS activity_classification_telemetry (id TEXT PRIMARY KEY, at INTEGER NOT NULL, unknown_key TEXT, uncertain_key TEXT, payload TEXT NOT NULL)");
      s.execute("CREATE INDEX IF NOT EXISTS activity_classification_telemetry_time ON activity_classification_telemetry(at)");
      s.execute("CREATE INDEX IF NOT EXISTS activity_classification_unknown_key ON activity_classification_telemetry(unknown_key)");
      s.execute("CREATE INDEX IF NOT EXISTS activity_classification_uncertain_key ON activity_classification_telemetry(uncertain_key)");
      s.execute("CREATE TABLE IF NOT EXISTS activity_classification_candidates (kind TEXT NOT NULL, candidate_key TEXT NOT NULL, last_at INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(kind,candidate_key))");
      initialized=true;
    }
  }
  public synchronized void save(Row row,ActivityProperties.Classification settings,Instant now) throws Exception {
    initialize();String unknown=!row.diagnostics().classificationUsable() && settings.getUnknownRegistry().isEnabled()?key(row,false):null;
    String uncertain=row.diagnostics().entertainmentDisposition()==EntertainmentDisposition.UNCERTAIN && settings.getEntertainmentRegistry().isEnabled()?key(row,true):null;
    try(var c=ds.getConnection()) {
      c.setAutoCommit(false);
      try {
        var dirty=new LinkedHashMap<String,Set<String>>();dirty.put("unknown",new HashSet<>());dirty.put("uncertain",new HashSet<>());
        try(var previous=c.prepareStatement("SELECT payload,unknown_key,uncertain_key FROM activity_classification_telemetry WHERE id=?")) {
          previous.setString(1,row.id());try(var rs=previous.executeQuery()){if(rs.next()) {
            unknown=Objects.requireNonNullElse(rs.getString(2),Objects.requireNonNullElse(unknown,""));if(unknown.isEmpty())unknown=null;
            uncertain=Objects.requireNonNullElse(rs.getString(3),Objects.requireNonNullElse(uncertain,""));if(uncertain.isEmpty())uncertain=null;
            var old=json.readValue(rs.getString(1),Row.class);var d=row.diagnostics();
            var reasons=new LinkedHashSet<>(old.diagnostics().unknownReasons());reasons.addAll(d.unknownReasons());
            var merged=new ClassificationDiagnostics(d.matchedRuleIds(),d.winningRuleId(),d.source(),d.confidence(),d.classificationUsable(),d.visionRequired(),d.visionRequiredReason(),List.copyOf(reasons),d.entertainmentDisposition(),d.entertainmentConfidence(),d.matchedEntertainmentRuleId(),d.entertainmentSource(),d.entertainmentReason());
            row=new Row(row.id(),row.at(),row.process(),row.title(),row.application(),row.service(),row.content(),row.category(),merged,old.visionAttempted()||row.visionAttempted(),old.visionSuccess()||row.visionSuccess(),row.visionFailure(),old.initialVisionRequired());
          }}
        }
        if(unknown!=null)dirty.get("unknown").add(unknown);if(uncertain!=null)dirty.get("uncertain").add(uncertain);
        try(var s=c.prepareStatement("INSERT INTO activity_classification_telemetry VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload, unknown_key=COALESCE(activity_classification_telemetry.unknown_key,excluded.unknown_key), uncertain_key=COALESCE(activity_classification_telemetry.uncertain_key,excluded.uncertain_key)")) {
          s.setString(1,row.id());s.setLong(2,row.at().toEpochMilli());s.setString(3,unknown);s.setString(4,uncertain);s.setString(5,json.writeValueAsString(row));s.executeUpdate();
        }
        for(String kind:List.of("unknown","uncertain")) {
          var p=kind.equals("unknown")?settings.getUnknownRegistry():settings.getEntertainmentRegistry();String column=kind+"_key";
          String expired="at<? OR "+column+" NOT IN (SELECT "+column+" FROM activity_classification_telemetry WHERE "+column+" IS NOT NULL GROUP BY "+column+" ORDER BY MAX(at) DESC,"+column+" LIMIT ?)";
          long cutoff=p.isEnabled()?now.minus(Duration.ofDays(p.getRetentionDays())).toEpochMilli():Long.MAX_VALUE;
          try(var s=c.prepareStatement("SELECT DISTINCT "+column+" FROM activity_classification_telemetry WHERE "+column+" IS NOT NULL AND ("+expired+")")) {
            s.setLong(1,cutoff);s.setInt(2,p.getMaxEntries());try(var rs=s.executeQuery()){while(rs.next())dirty.get(kind).add(rs.getString(1));}
          }
          try(var s=c.prepareStatement("UPDATE activity_classification_telemetry SET "+column+"=NULL WHERE "+column+" IS NOT NULL AND ("+expired+")")) {
            s.setLong(1,cutoff);s.setInt(2,p.getMaxEntries());s.executeUpdate();
          }
        }
        int days=Math.max(settings.getUnknownRegistry().getRetentionDays(),settings.getEntertainmentRegistry().getRetentionDays());
        try(var s=c.prepareStatement("SELECT unknown_key,uncertain_key FROM activity_classification_telemetry WHERE at<? OR id IN (SELECT id FROM activity_classification_telemetry ORDER BY at DESC,id LIMIT -1 OFFSET 100000)")) {
          s.setLong(1,now.minus(Duration.ofDays(days)).toEpochMilli());try(var rs=s.executeQuery()){while(rs.next()){if(rs.getString(1)!=null)dirty.get("unknown").add(rs.getString(1));if(rs.getString(2)!=null)dirty.get("uncertain").add(rs.getString(2));}}
        }
        try(var s=c.prepareStatement("DELETE FROM activity_classification_telemetry WHERE at<? OR id IN (SELECT id FROM activity_classification_telemetry ORDER BY at DESC,id LIMIT -1 OFFSET 100000)")) {s.setLong(1,now.minus(Duration.ofDays(days)).toEpochMilli());s.executeUpdate();}
        for(var kind:dirty.entrySet())for(String key:kind.getValue())refresh(c,kind.getKey(),key);
        c.commit();
      }catch(Exception e){c.rollback();throw e;}
    }
  }
  public synchronized List<Row> rows(Instant since) throws Exception {
    initialize();try(var c=ds.getConnection();var s=c.prepareStatement("SELECT payload FROM activity_classification_telemetry WHERE at>=? ORDER BY at,id")) {
      s.setLong(1,since.toEpochMilli());var result=new ArrayList<Row>();try(var rs=s.executeQuery()){while(rs.next())result.add(json.readValue(rs.getString(1),Row.class));}return result;
    }
  }
  public synchronized List<Candidate> candidates(String kind,Instant since) throws Exception {
    if(!Set.of("unknown","uncertain").contains(kind))throw new IllegalArgumentException("Unknown registry");
    initialize();var result=new ArrayList<Candidate>();
    try(var c=ds.getConnection();var s=c.prepareStatement("SELECT payload FROM activity_classification_candidates WHERE kind=? AND last_at>=?")) {
      s.setString(1,kind);s.setLong(2,since.toEpochMilli());try(var rs=s.executeQuery()) {
        while(rs.next()){var candidate=json.readValue(rs.getString(1),Candidate.class);if(candidate.firstSeen().isBefore(since))candidate=aggregate(candidate.key(),members(c,kind,candidate.key(),since),kind);if(candidate!=null)result.add(candidate);}
      }
    }
    return result.stream().sorted(Comparator.comparingLong(Candidate::count).reversed().thenComparing(Candidate::key)).toList();
  }
  private List<Row> members(java.sql.Connection c,String kind,String key,Instant since) throws Exception {
    var rows=new ArrayList<Row>();
    try(var s=c.prepareStatement("SELECT payload FROM activity_classification_telemetry WHERE "+kind+"_key=? AND at>=? ORDER BY at,id")) {
      s.setString(1,key);s.setLong(2,since.toEpochMilli());try(var rs=s.executeQuery()){while(rs.next())rows.add(json.readValue(rs.getString(1),Row.class));}
    }
    return rows;
  }
  private void refresh(java.sql.Connection c,String kind,String key) throws Exception {
    var candidate=aggregate(key,members(c,kind,key,Instant.EPOCH),kind);
    if(candidate==null) {
      try(var s=c.prepareStatement("DELETE FROM activity_classification_candidates WHERE kind=? AND candidate_key=?")){s.setString(1,kind);s.setString(2,key);s.executeUpdate();}
    }else {
      try(var s=c.prepareStatement("INSERT INTO activity_classification_candidates VALUES(?,?,?,?) ON CONFLICT(kind,candidate_key) DO UPDATE SET last_at=excluded.last_at,payload=excluded.payload")) {
        s.setString(1,kind);s.setString(2,key);s.setLong(3,candidate.lastSeen().toEpochMilli());s.setString(4,json.writeValueAsString(candidate));s.executeUpdate();
      }
    }
  }
  private static Candidate aggregate(String key,List<Row> rows,String kind) {
      if(rows.isEmpty())return null;
      var last=rows.getLast();var d=last.diagnostics();
      long open=rows.stream().filter(r->kind.equals("unknown")?!r.diagnostics().classificationUsable():r.diagnostics().entertainmentDisposition()==EntertainmentDisposition.UNCERTAIN).count();
      var outcomes=new TreeMap<String,Long>();var reasons=new TreeSet<String>();
      for(var r:rows){if(kind.equals("unknown")?r.visionSuccess():true)outcomes.merge(r.category()+" / "+r.service(),1L,Long::sum);reasons.addAll(r.diagnostics().unknownReasons());if(kind.equals("uncertain"))reasons.add(r.diagnostics().entertainmentReason());}
      return new Candidate(key,last.process(),last.title(),last.application(),last.service(),last.content(),last.category(),rows.size(),rows.getFirst().at(),last.at(),open,open>0?"OPEN":last.visionSuccess()?"RESOLVED_BY_VISION":"RESOLVED_BY_RULE",rows.stream().filter(Row::visionAttempted).count(),rows.stream().filter(Row::visionSuccess).count(),Map.copyOf(outcomes),List.copyOf(reasons),d.confidence(),last.visionFailure(),d.entertainmentDisposition(),d.entertainmentConfidence(),d.matchedEntertainmentRuleId());
  }
  private static String key(Row r,boolean entertainment){return normalize(r.process())+"\n"+normalize(r.title())+(entertainment?"\n"+normalize(r.service())+"\n"+normalize(r.content()):"");}
  private static String normalize(String s){return s.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").strip();}
}
