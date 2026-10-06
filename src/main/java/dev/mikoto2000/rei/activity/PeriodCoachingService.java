package dev.mikoto2000.rei.activity;

import java.time.Clock;

/** Deterministic period assessment and shared durable reservation. Callers own delivery. */
public final class PeriodCoachingService {
  private final ActivityTimeline timeline;
  private final PeriodCoachingStore store;
  private final Clock clock;
  public PeriodCoachingService(ActivityTimeline timeline,PeriodCoachingStore store,Clock clock){this.timeline=timeline;this.store=store;this.clock=clock;}
  public PeriodCoachingStore.Snapshot status(){return store.load();}
  public PeriodCoachingStore.Snapshot setEnabled(boolean enabled){return store.setEnabled(enabled);}
  public PeriodCoachingStore.Snapshot configure(PeriodCoaching.Settings settings){return store.configure(settings.withEnabled(false));}
  public String evaluate(ActivityPeriodAnalysis.Period period,String day) {
    var prepared=prepare(period,day);
    if(!prepared.advice())return prepared.message();
    String reservation=reserve(prepared);
    if(!reservation.equals("RESERVED"))return "助言を抑制しました: "+reservation;
    return "手動"+prepared.message();
  }
  public record Prepared(PeriodCoachingStore.Snapshot settings,String periodKey,String reason,String message,boolean advice) {}
  /** No reservation or side effect; allows automatic callers to recheck their delivery context. */
  public Prepared prepare(ActivityPeriodAnalysis.Period period,String day) {
    var settings=store.load();
    if(!settings.settings().enabled())return new Prepared(settings,null,"DISABLED","助言を抑制しました: DISABLED",false);
    var comparison=timeline.periodComparison(period,day);
    var current=comparison.current();var previous=comparison.previous();
    var assessment=new PeriodCoaching().evaluate(settings.settings(),current,previous);
    if(!assessment.advice())return new Prepared(settings,null,assessment.reason(),assessment.message(),false);
    String key=period+":"+current.range().firstDate()+":"+comparison.zone();
    String message="Coaching: "+period+" "+current.range().firstDate()+" / zone="+comparison.zone()+"\n"
        +"指定分類="+new java.util.TreeSet<>(settings.settings().categories())+"\n"
        +String.format(java.util.Locale.ROOT,"観測推定量: 対象 %.1f分 / 前期間 %.1f分\n",current.observedSeconds()/60.0,previous.observedSeconds()/60.0)
        +assessment.message();
    return new Prepared(settings,key,assessment.reason(),message,true);
  }
  public String reserve(Prepared prepared) {
    return prepared.advice()?store.reserve(prepared.settings(),prepared.periodKey(),prepared.reason(),clock.instant()):prepared.reason();
  }
  public boolean current(Prepared prepared){return store.load().equals(prepared.settings());}
}
