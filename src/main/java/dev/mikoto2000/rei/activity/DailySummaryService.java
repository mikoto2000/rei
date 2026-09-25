package dev.mikoto2000.rei.activity;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
public final class DailySummaryService {
  private final Supplier<SummaryThemeConfiguration> configuration;
  private final DailySummaryWriter writer;
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DailySummaryService.class);
  public DailySummaryService(Supplier<ProjectNameNormalizer> aliases,DailySummaryWriter writer) {
    this.configuration=()->new SummaryThemeConfiguration(aliases.get(),SummaryThemeGroups.empty());this.writer=writer;
  }
  public DailySummaryService(ProjectAliasStore store,DailySummaryWriter writer){this.configuration=store::snapshot;this.writer=writer;}
  public static DailySummaryService local(){return new DailySummaryService(()->new ProjectNameNormalizer(Map.of()),null);}
  public String summarize(LocalDate date,ActivityQueryRange range,ZoneId zone,double minimumConfidence,List<SummarySegment> segments) {
    if(segments.isEmpty())return date+" の Activity は記録されていません。";
    log.debug("[summary-trace] source-segments date={} count={}",date,segments.size());
    var config=configuration.get();
    var aggregate=new DailySummaryAggregator(config.projects(),minimumConfidence,config.groups()).aggregate(date,range,zone,segments);
    if(aggregate.observedSeconds()==0)return date+" の Activity は記録されていません。";
    var result=DailySummary.fallback(aggregate);
    String mode="FALLBACK_DISABLED";
    if(writer!=null)try {result=writer.write(aggregate).validated(aggregate);mode="LLM_SUCCESS";}
    catch(Exception error){
      mode="FALLBACK";
      log.debug("[summary-trace] writer-failure type={} cause={} timeout={}",error.getClass().getSimpleName(),
          error.getCause()==null?"none":error.getCause().getClass().getSimpleName(),
          error.getMessage()!=null && error.getMessage().startsWith("Timeout on blocking read"));
      log.warn("Daily summary writing failed; using deterministic fallback ({})",error.getClass().getSimpleName());
    }
    log.debug("[summary-trace] writer-mode={}",mode);
    log.debug("[summary-trace] formatter-input result={}",result);
    return new DailySummaryFormatter().format(aggregate,result);
  }
}
