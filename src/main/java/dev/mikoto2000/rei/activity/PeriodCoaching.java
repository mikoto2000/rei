package dev.mikoto2000.rei.activity;

import java.util.*;

/** User-defined category-share comparison, not a productivity or entertainment classifier. */
public final class PeriodCoaching {
  public record Settings(boolean enabled,Set<String> categories,double targetShare,int minimumObservedMinutes,
      double minimumCoverage,double maximumUnknownShare,int cooldownDays) {
    public Settings {
      if(categories==null || categories.isEmpty() || !ActivityVocabulary.CATEGORIES.containsAll(categories)
          || !Collections.disjoint(categories,Set.of("unknown","other")) || !Double.isFinite(targetShare) || targetShare<=0 || targetShare>1
          || minimumObservedMinutes<1 || minimumObservedMinutes>44640 || !Double.isFinite(minimumCoverage) || minimumCoverage<=0 || minimumCoverage>1
          || !Double.isFinite(maximumUnknownShare) || maximumUnknownShare<0 || maximumUnknownShare>1 || cooldownDays<1 || cooldownDays>366)
        throw new IllegalArgumentException("Invalid coaching criteria");
      categories=Set.copyOf(categories);
    }
    public static Settings defaults(){return new Settings(false,Set.of("development","research","documentation"),.6,120,.1,.25,7);}
    public Settings withEnabled(boolean enabled){return new Settings(enabled,categories,targetShare,minimumObservedMinutes,minimumCoverage,maximumUnknownShare,cooldownDays);}
  }
  public record Assessment(boolean advice,String reason,double currentShare,double previousShare,String message) {}
  public Assessment evaluate(Settings settings,ActivityPeriodAnalysis.Aggregate current,ActivityPeriodAnalysis.Aggregate previous) {
    if(!settings.enabled())return suppressed("DISABLED");
    if(current.range().partial() || previous.range().partial())return suppressed("PARTIAL_PERIOD");
    for(var a:List.of(current,previous)) {
      long span=a.observedSeconds()+a.unobservedSeconds();
      if(a.observedSeconds()<settings.minimumObservedMinutes()*60L || span<=0
          || a.observedSeconds()/(double)span<settings.minimumCoverage())return suppressed("INSUFFICIENT_OBSERVATION");
      if(unknown(a)/(double)a.observedSeconds()>settings.maximumUnknownShare() || a.observedSeconds()<=unknown(a))return suppressed("UNCERTAIN_CLASSIFICATION");
    }
    double share=share(settings,current),before=share(settings,previous);
    if(share>=settings.targetShare())return new Assessment(false,"WITHIN_USER_CRITERIA",share,before,"指定基準の範囲内です。成果・生産性の測定ではありません。");
    String reason=before-share>=.1?"BELOW_TARGET_DECLINING":"BELOW_TARGET";
    String message=String.format(Locale.ROOT,"指定分類の推定比率 %.1f%%（直前期間 %.1f%%、指定基準 %.1f%%）。分類可能な観測推定時間が分母です。\n"
        +"基準より低いので、分類の妥当性と今の優先事項を確認し、必要なら指定分類に対応する短い作業枠を試してみてください。\n"
        +"成果・生産性・集中の低下を示す測定ではありません。未観測時間の行動は不明です。",share*100,before*100,settings.targetShare()*100);
    return new Assessment(true,reason,share,before,message);
  }
  private static long unknown(ActivityPeriodAnalysis.Aggregate a){return a.categorySeconds().getOrDefault("unknown",0L)+a.categorySeconds().getOrDefault("other",0L);}
  private static double share(Settings settings,ActivityPeriodAnalysis.Aggregate a){return settings.categories().stream().mapToLong(c->a.categorySeconds().getOrDefault(c,0L)).sum()/(double)(a.observedSeconds()-unknown(a));}
  private static Assessment suppressed(String reason){return new Assessment(false,reason,0,0,"助言を抑制しました: "+reason);}
}
