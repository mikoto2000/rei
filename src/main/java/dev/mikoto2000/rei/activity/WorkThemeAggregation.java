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
    TOPICS.put("文字起こし",Pattern.compile("文字起こし|音声認識|\\b(speech recognition|transcription)\\b",Pattern.CASE_INSENSITIVE));
    TOPICS.put("音声翻訳",Pattern.compile("音声翻訳|\\b(speech|voice) translation\\b",Pattern.CASE_INSENSITIVE));
    TOPICS.put("翻訳",Pattern.compile("翻訳|\\btranslation\\b",Pattern.CASE_INSENSITIVE));
    TOPICS.put("Visionモデル",Pattern.compile("視覚モデル|画像認識|\\bvision[ -]model\\b",Pattern.CASE_INSENSITIVE));
    TOPICS.put("フィード要約",Pattern.compile("フィード要約|\\bfeed summary\\b",Pattern.CASE_INSENSITIVE));
  }
  static List<String> topics(ActivityRecord.Activity primary,ActivityRecord record) {
    // Record-wide prose can refer to another monitor. Only use it for an unambiguous single activity.
    var fields=record.detection()==null?null:record.detection().fieldConfidence();
    String title=primary.contentTitle()==null || fields!=null && fields.content()<.5?"":primary.contentTitle();
    String summary=record.inference().activities().size()==1 && record.inference().summary()!=null?record.inference().summary():"";
    var result=new ArrayList<String>();
    for(var e:TOPICS.entrySet())if(e.getValue().matcher(title+" "+summary).find())result.add(e.getKey());
    if(result.contains("音声翻訳"))result.remove("翻訳");
    return result.stream().limit(2).toList();
  }
  private static final class Stat {
    final String project;final Map<String,Long> categories=new HashMap<>(),topics=new HashMap<>();
    final Set<String> observations=new HashSet<>();
    long seconds,run,longest;Instant end;String continuity;
    Stat(String project){this.project=project;}
  }
  private final Map<String,Stat> values=new HashMap<>();
  private long total;
  void add(String project,String category,List<String> topics,ActivityRecord record,Instant start,Instant end) {
    long seconds=Duration.between(start,end).getSeconds();if(seconds<=0)return;
    String key=!project.isBlank()?"project:"+project:!topics.isEmpty()?"topic:"+topics.getFirst():"category:"+category;
    var s=values.computeIfAbsent(key,k->new Stat(project));total+=seconds;s.seconds+=seconds;
    s.categories.merge(category,seconds,Long::sum);topics.forEach(t->s.topics.merge(t,seconds,Long::sum));s.observations.add(record.id());
    boolean joins=s.end!=null && Objects.equals(s.continuity,record.continuityId()) && Duration.between(s.end,start).getSeconds()<=120;
    s.run=joins?s.run+seconds:seconds;s.longest=Math.max(s.longest,s.run);s.end=end;s.continuity=record.continuityId();
  }
  List<ProjectThemeStat> ranked(int limit) {
    // Relative floor for short days, capped at five minutes so social-heavy periods retain real work.
    double floor=Math.min(total,Math.max(120,Math.min(300,total*.02)));
    var candidates=new ArrayList<ProjectThemeStat>();
    for(var s:values.values()) {
      if(s.seconds<floor)continue;
      var categories=DailySummaryAggregator.top(s.categories,3).stream().map(Weighted::name).toList();
      var topics=DailySummaryAggregator.top(s.topics,2).stream().filter(t->t.seconds()>=Math.min(120,s.seconds*.2)).map(Weighted::name).toList();
      int specificity=!s.project.isBlank()?(!topics.isEmpty()?3:2):!topics.isEmpty()?1:0;
      String detail=topics.isEmpty()?String.join("・",categories.stream().map(DailySummaryAggregate::categoryLabel).toList()):String.join("・",topics);
      String label=(s.project.isBlank()?"":s.project+" の")+detail;
      // All terms use observed seconds; gaps never increase duration or continuity.
      double score=(s.seconds+Math.min(s.seconds*.2,s.observations.size()*30.0)+Math.min(s.seconds*.2,s.longest*.2))*(1+specificity*.25);
      candidates.add(new ProjectThemeStat(s.project,categories,topics,s.seconds,s.observations.size(),s.longest,specificity,score,label));
    }
    boolean specific=candidates.stream().anyMatch(s->s.specificity()>0);
    return candidates.stream().filter(s->!specific || s.specificity()>0)
        .sorted(Comparator.comparingDouble(ProjectThemeStat::score).reversed().thenComparing(ProjectThemeStat::label)).limit(limit).toList();
  }
  List<String> labels(int limit){return ranked(limit).stream().map(ProjectThemeStat::label).toList();}
}
