package dev.mikoto2000.rei.activity;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import static dev.mikoto2000.rei.activity.DailySummaryAggregate.*;
/** Summary-only candidates from saved text. A small explicit vocabulary avoids interpreting code identifiers. */
final class WorkThemeAggregation {
  private static final Map<String,Pattern> TOPICS=new LinkedHashMap<>();
  static {
    TOPICS.put("Activity分類",Pattern.compile("activity[\\s_-]*(分類|classification)",Pattern.CASE_INSENSITIVE));
    TOPICS.put("音声入力",Pattern.compile("音声入力|\\bvoice input\\b",Pattern.CASE_INSENSITIVE));
    TOPICS.put("文字起こし",Pattern.compile("文字起こし|音声認識|\\b(speech recognition|speech-to-text|transcription)\\b",Pattern.CASE_INSENSITIVE));
    TOPICS.put("音声翻訳",Pattern.compile("音声翻訳|\\b(speech|voice) translation\\b",Pattern.CASE_INSENSITIVE));
    TOPICS.put("翻訳",Pattern.compile("翻訳|\\btranslation\\b",Pattern.CASE_INSENSITIVE));
    TOPICS.put("Visionモデル",Pattern.compile("視覚モデル|画像認識|\\bvision[ -]model\\b",Pattern.CASE_INSENSITIVE));
    TOPICS.put("フィード要約",Pattern.compile("フィード要約|\\bfeed summary\\b",Pattern.CASE_INSENSITIVE));
  }
  record Candidate(String theme,ProjectThemeAssociation.Origin origin,double confidence) {}
  static List<Candidate> topics(ActivityRecord.Activity primary,ActivityRecord record) {
    // Record-wide prose can refer to another monitor. Only use it for an unambiguous single activity.
    var fields=fields(primary,record);
    String title=primary.contentTitle()==null || fields!=null && fields.content()<.5?"":primary.contentTitle();
    String summary=record.inference().activities().size()==1 && record.inference().summary()!=null?record.inference().summary():"";
    var result=new ArrayList<Candidate>();
    double confidence=fields==null?record.confidence():fields.content();
    for(var e:TOPICS.entrySet()) {
      if(e.getValue().matcher(title).find())result.add(new Candidate(e.getKey(),ProjectThemeAssociation.Origin.SAME_ACTIVITY_TITLE,confidence));
      else if(e.getValue().matcher(summary).find())result.add(new Candidate(e.getKey(),ProjectThemeAssociation.Origin.SINGLE_ACTIVITY_SUMMARY,record.confidence()*.5));
    }
    if(result.stream().anyMatch(c->c.theme().equals("音声翻訳")))result.removeIf(c->c.theme().equals("翻訳"));
    return result.stream().limit(2).toList();
  }
  private static boolean mentions(String title,String value) {
    String token=ActivityRolePolicy.normalize(value);
    return !token.isBlank() && Pattern.compile("(?<![\\p{L}\\p{N}])"+Pattern.quote(token)+"(?![\\p{L}\\p{N}])").matcher(title).find();
  }
  static ActivityFieldConfidence fields(ActivityRecord.Activity primary,ActivityRecord record) {
    var d=record.detection();
    if(primary==null || d==null)return null;
    if(ActivityVocabulary.canonical(record.inference().activities().getFirst()).equals(primary))return d.fieldConfidence();
    return d.secondaryConfidence().stream().filter(s->ActivityVocabulary.canonical(s.activity()).equals(primary))
        .map(ActivityClassification.Secondary::confidence).findFirst()
        .orElse(ActivityFieldConfidence.from(primary,Math.min(.5,record.confidence())));
  }
  private static final class Stat {
    final String project;final Map<String,Long> categories=new HashMap<>();
    final Map<String,Map<String,ProjectThemeAssociation.Support>> supports=new HashMap<>();
    final Set<String> observations=new HashSet<>();
    long seconds,run,longest;Instant end;String continuity;
    Stat(String project){this.project=project;}
  }
  private final Map<String,Stat> values=new HashMap<>();
  private long total;
  void add(String project,String category,List<Candidate> topics,ActivityRecord record,ActivityRecord.Activity primary,Instant start,Instant end) {
    long seconds=Duration.between(start,end).getSeconds();if(seconds<=0)return;
    String key=!project.isBlank()?"project:"+project:"category:"+category;
    var s=values.computeIfAbsent(key,k->new Stat(project));total+=seconds;s.seconds+=seconds;
    s.categories.merge(category,seconds,Long::sum);s.observations.add(record.id());
    var d=record.detection();var fields=fields(primary,record);
    double pc=project.isBlank()?1:fields==null?record.confidence():fields.project();
    var sources=new ActivityEvidenceDisplayFormatter().sources(record);
    boolean background=sources.contains(ActivityEvidenceDisplayFormatter.Source.BACKGROUND_VISION)
        || d!=null && d.classificationSources().contains("VISION_BACKGROUND");
    for(var candidate:topics) {
      // A record may carry background Vision diagnostics as well as a primary candidate.
      // In that case require the foreground title itself to corroborate this exact pair.
      String title=record.foreground()==null?"":ActivityRolePolicy.normalize(record.foreground().windowTitle());
      boolean titlePair=primary!=null && !ActivityRolePolicy.normalize(primary.contentTitle()).isBlank()
          && mentions(title,primary.contentTitle())
          && (project.isBlank() || !ActivityRolePolicy.normalize(primary.projectCandidate()).isBlank()
              && mentions(title,primary.projectCandidate()));
      boolean foreground=primary!=null && (!background || titlePair);
      var evidence=java.util.EnumSet.noneOf(ActivityEvidenceDisplayFormatter.Source.class);evidence.addAll(sources);
      if(titlePair)evidence.add(ActivityEvidenceDisplayFormatter.Source.WINDOW_METADATA);
      var support=new ProjectThemeAssociation.Support(record.id(),primary==null?"":primary.monitor(),record.continuityId(),
          start,end,candidate.origin(),foreground,pc,candidate.confidence(),Set.copyOf(evidence));
      s.supports.computeIfAbsent(candidate.theme(),k->new LinkedHashMap<>()).putIfAbsent(record.id(),support);
    }
    boolean joins=s.end!=null && Objects.equals(s.continuity,record.continuityId()) && Duration.between(s.end,start).getSeconds()<=120;
    s.run=joins?s.run+seconds:seconds;s.longest=Math.max(s.longest,s.run);s.end=end;s.continuity=record.continuityId();
  }
  List<ProjectThemeStat> ranked(int limit) {
    // Relative floor for short days, capped at five minutes so social-heavy periods retain real work.
    double floor=Math.min(total,Math.max(120,Math.min(300,total*.02)));
    var candidates=new ArrayList<ProjectThemeStat>();
    for(var s:values.values()) {
      if(s.seconds<floor)continue;
      var categories=DailySummaryAggregator.top(s.categories,3).stream()
          .filter(c->c.seconds()>=Math.min(120,s.seconds*.2)).map(Weighted::name).toList();
      var associations=associations(s);
      var topics=associations.stream().filter(ProjectThemeAssociation::strong).limit(2).map(ProjectThemeAssociation::theme).toList();
      int specificity=!s.project.isBlank()?(!topics.isEmpty()?3:2):!topics.isEmpty()?1:0;
      String detail=topics.isEmpty()?String.join("・",categories.stream().map(DailySummaryAggregate::categoryLabel).toList()):String.join("・",topics);
      String label=(s.project.isBlank()?"":s.project+" の")+detail;
      // All terms use observed seconds; gaps never increase duration or continuity.
      double score=(s.seconds+Math.min(s.seconds*.2,s.observations.size()*30.0)+Math.min(s.seconds*.2,s.longest*.2))*(1+specificity*.25);
      candidates.add(new ProjectThemeStat(s.project,categories,topics,s.seconds,s.observations.size(),s.longest,specificity,score,label,associations.stream().filter(ProjectThemeAssociation::strong).limit(2).toList()));
    }
    boolean specific=candidates.stream().anyMatch(s->s.specificity()>0);
    return candidates.stream().filter(s->!specific || s.specificity()>0)
        .sorted(Comparator.comparingDouble(ProjectThemeStat::score).reversed().thenComparing(ProjectThemeStat::label)).limit(limit).toList();
  }
  private static List<ProjectThemeAssociation> associations(Stat s) {
    return s.supports.entrySet().stream().map(e->ProjectThemeAssociation.summarize(s.project,e.getKey(),e.getValue().values()))
        .sorted(Comparator.comparingDouble(ProjectThemeAssociation::associationConfidence).reversed()
            .thenComparing(Comparator.comparingLong(ProjectThemeAssociation::supportingDuration).reversed())
            .thenComparing(ProjectThemeAssociation::theme)).toList();
  }
  List<ProjectThemeAssociation> associations() {
    return values.values().stream().flatMap(s->associations(s).stream())
        .sorted(Comparator.comparing(ProjectThemeAssociation::canonicalProject).thenComparing(ProjectThemeAssociation::theme)).toList();
  }
  List<String> labels(int limit){return ranked(limit).stream().map(ProjectThemeStat::label).toList();}
}
