package dev.mikoto2000.rei.activity;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
public final class DailySummaryService {
  private final Supplier<ProjectNameNormalizer> aliases;
  private final DailySummaryWriter writer;
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DailySummaryService.class);
  public DailySummaryService(Supplier<ProjectNameNormalizer> aliases,DailySummaryWriter writer){this.aliases=aliases;this.writer=writer;}
  public static DailySummaryService local(){return new DailySummaryService(()->new ProjectNameNormalizer(Map.of()),null);}
  public String summarize(LocalDate date,ActivityQueryRange range,ZoneId zone,double minimumConfidence,List<SummarySegment> segments) {
    if(segments.isEmpty())return date+" の Activity は記録されていません。";
    var aggregate=new DailySummaryAggregator(aliases.get(),minimumConfidence).aggregate(date,range,zone,segments);
    if(aggregate.observedSeconds()==0)return date+" の Activity は記録されていません。";
    var result=DailySummary.fallback(aggregate);
    if(writer!=null)try {result=writer.write(aggregate).validated(aggregate);}
    catch(Exception error){log.warn("Daily summary writing failed; using deterministic fallback ({})",error.getClass().getSimpleName());}
    return new DailySummaryFormatter().format(aggregate,result);
  }
}
