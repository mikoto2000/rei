package dev.mikoto2000.rei.activity;
import java.time.*;
import java.util.*;
/** Bounded, evidence-free writer input. Durations are seconds of observations, not wall-clock engagement. */
public record DailySummaryAggregate(LocalDate targetDate,long observedSeconds,long unobservedSeconds,
    Map<String,Long> categorySeconds,Map<EntertainmentDisposition,Long> entertainmentSeconds,
    List<Weighted> topProjects,List<Weighted> services,Map<String,Bucket> timeOfDay,
    @com.fasterxml.jackson.annotation.JsonIgnore List<ProjectThemeStat> projectThemeStats,List<SummaryThemeCandidate> mainWorkThemeCandidates,@com.fasterxml.jackson.annotation.JsonIgnore DailySummaryThemeConsolidator.Metrics consolidationMetrics,boolean significantAiAssistance,List<String> dominantThemes,List<String> leisureActivities,List<Block> majorWorkBlocks,List<Block> majorLeisureBlocks,
    int projectSwitchCount,boolean frequentProjectSwitches,double unknownRatio,int sourceSegmentCount) {
  public record Weighted(String name,long seconds) {}
  public record ProjectThemeStat(String canonicalProject,List<String> categories,List<String> themeCandidates,
      long observedSeconds,int observationCount,long longestContinuousSeconds,int specificity,double score,String label,List<ProjectThemeAssociation> strongAssociations,@com.fasterxml.jackson.annotation.JsonIgnore Map<String,Long> categorySeconds) {}
  public record Bucket(long observedSeconds,Map<String,Long> categorySeconds,List<String> dominant,List<String> secondary,
      List<String> background,List<String> workThemes,List<String> dominantProjects,List<String> secondaryThemes,
      List<ProjectThemeAssociation> strongAssociations,List<SummaryThemeCandidate> timeOfDayThemeCandidates) {}
  public record Block(String start,String end,long observedSeconds,String theme) {}
  public static final Map<String,String> BUCKET_LABELS=Collections.unmodifiableMap(new LinkedHashMap<>(Map.of(
      "lateNight","深夜","morning","午前","afternoon","午後","evening","夜")));
  public static final List<String> BUCKET_ORDER=List.of("lateNight","morning","afternoon","evening");
  public static String categoryLabel(String category) {
    return switch(category) {
      case "development"->"開発";case "research"->"調査";case "documentation"->"文書作業";
      case "social"->"SNS閲覧";case "media"->"動画・音楽の閲覧";case "shopping"->"ショッピング";
      case "gaming"->"ゲーム";case "communication"->"コミュニケーション";case "monitoring"->"監視";
      case "navigation"->"予定・経路確認";case "idle"->"待機";default->"判定不能";
    };
  }
}
