package dev.mikoto2000.rei.activity;
import java.util.*;
/** Short structured prose; uncertainty notices are owned by the formatter, not repeated by the model. */
public record DailySummary(String overview,Map<String,String> timeOfDay,List<String> workThemes,List<String> nonWorkActivities,String trend) {
  public DailySummary {
    timeOfDay=timeOfDay==null?Map.of():Collections.unmodifiableMap(new LinkedHashMap<>(timeOfDay));
    workThemes=workThemes==null?List.of():List.copyOf(workThemes);
    nonWorkActivities=nonWorkActivities==null?List.of():List.copyOf(nonWorkActivities);
  }
  public DailySummary validated(DailySummaryAggregate a) {
    text(overview,400);text(trend,240);
    if(!timeOfDay.keySet().equals(a.timeOfDay().keySet()) || workThemes.size()>5 || nonWorkActivities.size()>3
        || !a.dominantThemes().containsAll(workThemes) || !a.leisureActivities().containsAll(nonWorkActivities)
        || new HashSet<>(workThemes).size()!=workThemes.size() || new HashSet<>(nonWorkActivities).size()!=nonWorkActivities.size())
      throw new IllegalArgumentException("Invalid daily summary structure");
    if(timeOfDay.values().stream().anyMatch(s->s!=null && s.contains("AI支援"))
        || countAi(overview)+countAi(trend)>(a.significantAiAssistance()?1:0))
      throw new IllegalArgumentException("Repeated or incidental AI assistance");
    timeOfDay.values().forEach(s->text(s,200));workThemes.forEach(s->text(s,90));nonWorkActivities.forEach(s->text(s,90));
    return this;
  }
  private static int countAi(String s){return s==null?0:s.split("AI支援",-1).length-1;}
  private static void text(String s,int max) {
    if(s==null || s.isBlank() || s.length()>max || s.chars().anyMatch(c->Character.isISOControl(c))
        || s.matches(".*(集中して|生産性|締切|締め切り|主活動を判定|判定でき|観測に基づく振り返り).*"))
      throw new IllegalArgumentException("Invalid daily summary text");
  }
  public static DailySummary fallback(DailySummaryAggregate a) {
    var dominant=DailySummaryAggregator.top(a.categorySeconds(),3).stream().filter(w->!w.name().equals("unknown")).limit(2)
        .map(w->DailySummaryAggregate.categoryLabel(w.name())).toList();
    String overview=dominant.isEmpty()?"活動内容を特定するための情報が限られていました。":
        "観測された主活動では、"+ThemeActivityFormatter.join(dominant)+"が多く見られました。";
    if(!a.dominantThemes().isEmpty())overview+="作業テーマには"+String.join("、",a.dominantThemes().stream().limit(2).toList())+"が見られました。";
    else if(!dominant.isEmpty())overview+="具体的な作業対象を示す情報は限られています。";
    var sections=new LinkedHashMap<String,String>();
    for(var key:DailySummaryAggregate.BUCKET_ORDER) {
      var b=a.timeOfDay().get(key);if(b==null || b.dominant().isEmpty() && b.workThemes().isEmpty())continue;
      String text=new DailySummaryBucketFormatter().format(a,b);
      sections.put(key,text);
    }
    String trend=a.frequentProjectSwitches()?"観測された作業対象の切り替えが多い日でした。":
        !a.majorWorkBlocks().isEmpty()?"開発・調査に関する表示が続く区間がありました。":
        !a.majorLeisureBlocks().isEmpty()?"娯楽と分類された表示が続く区間がありました。":"記録された範囲での振り返りです。";
    if(a.significantAiAssistance())trend+="AI支援の表示も一日を通じた特徴でした。";
    return new DailySummary(overview,sections,a.dominantThemes(),a.leisureActivities(),trend);
  }
}
