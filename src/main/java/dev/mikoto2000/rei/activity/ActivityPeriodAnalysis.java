package dev.mikoto2000.rei.activity;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Deterministic read-only arithmetic over saved observation estimates, with no model calls. */
public final class ActivityPeriodAnalysis {
  public enum Period { WEEK, MONTH }
  public record Range(Period period,LocalDate firstDate,Instant fromInclusive,Instant toExclusive,boolean partial) {}
  public record Metrics(Set<String> criteria,Double score,Map<LocalDate,Double> dayScores,long classifiedSeconds,
      long scoredSeconds,int focusCandidates,long focusSeconds,long longestStableSeconds,int interruptionCandidates) {
    public Metrics {criteria=Set.copyOf(criteria);dayScores=Map.copyOf(dayScores);}
    static Metrics empty(){return new Metrics(Set.of(),null,Map.of(),0,0,0,0,0,0);}
  }
  public record Aggregate(Range range,long observedSeconds,long unobservedSeconds,
      Map<String,Long> categorySeconds,Map<String,Long> projectCandidateSeconds,Map<Integer,Long> hourSeconds,
      Map<LocalDate,Long> daySeconds,int observedProjectTransitions,Metrics metrics) {
    public Aggregate(Range range,long observedSeconds,long unobservedSeconds,Map<String,Long> categorySeconds,
        Map<String,Long> projectCandidateSeconds,Map<Integer,Long> hourSeconds,Map<LocalDate,Long> daySeconds,int observedProjectTransitions) {
      this(range,observedSeconds,unobservedSeconds,categorySeconds,projectCandidateSeconds,hourSeconds,daySeconds,observedProjectTransitions,Metrics.empty());
    }
    public Aggregate {
      categorySeconds=Map.copyOf(categorySeconds);projectCandidateSeconds=Map.copyOf(projectCandidateSeconds);
      hourSeconds=Map.copyOf(hourSeconds);daySeconds=Map.copyOf(daySeconds);
    }
  }
  private final Clock clock;
  private final ActivityRolePolicy roles;
  private final ProjectNameNormalizer names;
  private final Set<String> criteria;
  public ActivityPeriodAnalysis(Clock clock,double minimumConfidence) {
    this(clock,minimumConfidence,new ProjectNameNormalizer(Map.of()));
  }
  public ActivityPeriodAnalysis(Clock clock,double minimumConfidence,ProjectNameNormalizer names) {
    this(clock,minimumConfidence,names,Set.of());
  }
  public ActivityPeriodAnalysis(Clock clock,double minimumConfidence,ProjectNameNormalizer names,Set<String> criteria) {
    if(criteria==null||!ActivityVocabulary.CATEGORIES.containsAll(criteria)||!Collections.disjoint(criteria,Set.of("unknown","other")))throw new IllegalArgumentException("Invalid score criteria");
    this.criteria=Set.copyOf(criteria);
    this.clock=Clock.fixed(clock.instant(),clock.getZone());roles=new ActivityRolePolicy(minimumConfidence);this.names=Objects.requireNonNull(names);
  }
  public Range range(Period period,LocalDate anchor) {
    if(anchor.isAfter(LocalDate.now(clock)))throw new DateTimeException("未来の日付は指定できません。");
    var first=period==Period.WEEK?anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)):anchor.withDayOfMonth(1);
    var start=first.atStartOfDay(clock.getZone()).toInstant();
    var end=(period==Period.WEEK?first.plusWeeks(1):first.plusMonths(1)).atStartOfDay(clock.getZone()).toInstant();
    boolean partial=end.isAfter(clock.instant());
    return new Range(period,first,start,partial?clock.instant():end,partial);
  }
  public Range previous(Range range) {
    var first=range.period()==Period.WEEK?range.firstDate().minusWeeks(1):range.firstDate().minusMonths(1);
    return range(range.period(),first);
  }
  public Aggregate aggregate(Range range,List<ActivityRecord> input) {
    var unique=new LinkedHashMap<String,ActivityRecord>();
    input.stream().sorted(Comparator.comparing(ActivityRecord::capturedAt).thenComparing(ActivityRecord::id))
        .forEach(r->unique.putIfAbsent(r.id(),r));
    var records=List.copyOf(unique.values());
    var categories=new HashMap<String,Long>();var projects=new HashMap<String,Long>();
    var hours=new HashMap<Integer,Long>();var days=new HashMap<LocalDate,Long>();
    var classifiedDays=new HashMap<LocalDate,Long>();var selectedDays=new HashMap<LocalDate,Long>();
    long classifiedSeconds=0,selectedSeconds=0,stableSeconds=0,focusSeconds=0,longest=0;
    int focusCandidates=0,interruptions=0;String stableKey="",stableContinuity="";Instant stableEnd=null;
    long observed=0;int transitions=0;String previousProject="",previousContinuity="";Instant previousEnd=null;
    for(int i=0;i<records.size();i++) {
      var r=records.get(i);var start=max(r.capturedAt(),range.fromInclusive());
      var midnight=r.capturedAt().atZone(clock.getZone()).toLocalDate().plusDays(1).atStartOfDay(clock.getZone()).toInstant();
      var end=min(min(r.capturedAt().plusSeconds(r.durationEstimate()),midnight),range.toExclusive());
      if(i+1<records.size())end=min(end,records.get(i+1).capturedAt());
      if(!start.isBefore(end))continue;
      long seconds=Duration.between(start,end).getSeconds();if(seconds<=0)continue;
      var classified=roles.classify(r);String category=classified.category();if(category.equals("other"))category="unknown";
      categories.merge(category,seconds,Long::sum);observed+=seconds;
      var primary=classified.primary();var fields=WorkThemeAggregation.fields(primary,r);
      String project=primary==null || fields!=null && fields.project()<.5?"":names.project(primary);
      if(!project.isBlank())projects.merge(project,seconds,Long::sum);
      boolean known=!category.equals("unknown");boolean selected=known&&criteria.contains(category);
      if(known)classifiedSeconds+=seconds;if(selected)selectedSeconds+=seconds;
      String key=known&&!project.isBlank()?project+"\u0000"+category:"";
      boolean adjacent=stableEnd!=null&&Objects.equals(stableContinuity,r.continuityId())&&Duration.between(stableEnd,start).getSeconds()<=120;
      if(key.isEmpty()||!key.equals(stableKey)||!adjacent) {
        longest=Math.max(longest,stableSeconds);if(stableSeconds>=1500){focusCandidates++;focusSeconds+=stableSeconds;}
        if(adjacent&&!key.isEmpty()&&!stableKey.isEmpty()&&!key.equals(stableKey))interruptions++;
        stableSeconds=0;
      }
      if(!key.isEmpty())stableSeconds+=seconds;
      stableKey=key;stableContinuity=r.continuityId();stableEnd=end;
      if(!project.isBlank() && !previousProject.isBlank() && !project.equals(previousProject)
          && Objects.equals(previousContinuity,r.continuityId()) && previousEnd!=null
          && Duration.between(previousEnd,start).getSeconds()<=120)transitions++;
      previousProject=project;previousEnd=end;previousContinuity=r.continuityId();
      for(var cursor=start;cursor.isBefore(end);) {
        var local=cursor.atZone(clock.getZone());
        var boundary=local.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1).toInstant();
        var until=min(boundary,end);long weight=Duration.between(cursor,until).getSeconds();
        hours.merge(local.getHour(),weight,Long::sum);days.merge(local.toLocalDate(),weight,Long::sum);
        if(known)classifiedDays.merge(local.toLocalDate(),weight,Long::sum);
        if(selected)selectedDays.merge(local.toLocalDate(),weight,Long::sum);
        cursor=until;
      }
    }
    longest=Math.max(longest,stableSeconds);if(stableSeconds>=1500){focusCandidates++;focusSeconds+=stableSeconds;}
    Double score=criteria.isEmpty()||classifiedSeconds==0?null:100.0*selectedSeconds/classifiedSeconds;
    var dailyScores=new HashMap<LocalDate,Double>();
    if(!criteria.isEmpty())classifiedDays.forEach((date,seconds)->{if(seconds>0)dailyScores.put(date,100.0*selectedDays.getOrDefault(date,0L)/seconds);});
    var metrics=new Metrics(criteria,score,dailyScores,classifiedSeconds,selectedSeconds,focusCandidates,focusSeconds,longest,interruptions);
    long span=Duration.between(range.fromInclusive(),range.toExclusive()).getSeconds();
    return new Aggregate(range,observed,Math.max(0,span-observed),categories,projects,hours,days,transitions,metrics);
  }
  public String format(Aggregate current,Aggregate previous) {
    var out=new StringBuilder();
    out.append(current.range().period()==Period.WEEK?"週次":"月次").append(" Activity 分析: ").append(current.range().firstDate())
        .append(" / zone=").append(clock.getZone()).append("\n");
    appendRange(out,"対象",current);appendRange(out,"直前期間",previous);
    if(current.range().partial())out.append("途中期間と直前の全期間の比較です。観測量の増減は成果の増減を意味しません。\n");
    out.append(String.format(Locale.ROOT,"観測推定時間の差: %+.1f分\n",(current.observedSeconds()-previous.observedSeconds())/60.0));
    out.append("推定分類（観測推定時間、前期間との差）:\n");
    var keys=new TreeSet<>(current.categorySeconds().keySet());keys.addAll(previous.categorySeconds().keySet());
    for(var key:keys)out.append(String.format(Locale.ROOT,"  %s: %.1f分 (%+.1f分)\n",DailySummaryAggregate.categoryLabel(key),current.categorySeconds().getOrDefault(key,0L)/60.0,
        (current.categorySeconds().getOrDefault(key,0L)-previous.categorySeconds().getOrDefault(key,0L))/60.0));
    out.append("Project候補（推定、上位10）:\n");
    DailySummaryAggregator.top(current.projectCandidateSeconds(),10).forEach(v->out.append(String.format(Locale.ROOT,"  %s: %.1f分\n",v.name(),v.seconds()/60.0)));
    out.append("時間帯（現地時刻、観測推定分）:\n");
    new TreeMap<>(current.hourSeconds()).forEach((hour,seconds)->out.append(String.format(Locale.ROOT,"  %02d時: %.1f分\n",hour,seconds/60.0)));
    out.append("日別観測推定時間:\n");
    new TreeMap<>(current.daySeconds()).forEach((date,seconds)->out.append(String.format(Locale.ROOT,"  %s: %.1f分\n",date,seconds/60.0)));
    out.append("近接観測間のProject候補切替: ").append(current.observedProjectTransitions()).append("回（直前期間 ").append(previous.observedProjectTransitions()).append("回）\n");
    appendMetrics(out,current,previous);
    out.append("観測事実: 保存された標本とその時刻。時間・分類・Projectは推定です。\n未観測は不活動ではありません。成果・集中・中断の実測ではありません。\n生産性スコア: 未測定（成果の実測）。基準適合指数は推定分類に対する指定基準の適合度です。テーマは分類カテゴリとして集計しています。\n");
    return out.toString();
  }
  private static void appendMetrics(StringBuilder out,Aggregate current,Aggregate previous) {
    var now=current.metrics();var before=previous.metrics();
    out.append("基準適合指数（生産性スコアの推定指標、0〜100）: ");
    if(now.score()==null)out.append(now.criteria().isEmpty()?"基準未設定":"分類可能な観測なし");
    else out.append(String.format(Locale.ROOT,"%.1f / 指定分類=%s / 分類可能な推定時間 %.1f分",now.score(),new TreeSet<>(now.criteria()),now.classifiedSeconds()/60.0));
    out.append("\n");
    if(now.score()!=null&&before.score()!=null&&now.criteria().equals(before.criteria()))
      out.append(String.format(Locale.ROOT,"指数の前期間差: %+.1fポイント\n",now.score()-before.score()));
    else out.append("指数の前期間差: 比較可能な分類・基準が不足\n");
    new TreeMap<>(now.dayScores()).forEach((day,score)->out.append(String.format(Locale.ROOT,"  日別指数 %s: %.1f\n",day,score)));
    out.append(String.format(Locale.ROOT,"集中候補（同一Project・分類の25分以上の連続観測）: %d回 / 推定 %.1f分 / 最長 %.1f分（前期間 %d回）\n",
        now.focusCandidates(),now.focusSeconds()/60.0,now.longestStableSeconds()/60.0,before.focusCandidates()));
    out.append("中断候補（近接した同一観測系列のProject・分類切替）: ").append(now.interruptionCandidates())
        .append("回（前期間 ").append(before.interruptionCandidates()).append("回）\n");
    out.append("連続候補は標本間の空白を時間に加えず、120秒を超える空白・観測系列変更・不明分類で区切ります。未知分類は指数の分母から除外し、未観測は採点しません。\n");
  }
  private static void appendRange(StringBuilder out,String label,Aggregate a) {
    out.append(label).append(": [").append(a.range().fromInclusive()).append(", ").append(a.range().toExclusive()).append(")\n");
    out.append(String.format(Locale.ROOT,"  観測推定 %.1f分 / 未観測 %.1f分%s\n",a.observedSeconds()/60.0,a.unobservedSeconds()/60.0,a.observedSeconds()==0?"（観測なし）":""));
  }
  private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
  private static Instant max(Instant a,Instant b){return a.isAfter(b)?a:b;}
}
