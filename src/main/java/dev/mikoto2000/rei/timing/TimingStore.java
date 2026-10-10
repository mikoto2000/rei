package dev.mikoto2000.rei.timing;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Bounded process-local observation. Missing/late records never change execution results. */
@Service
public class TimingStore implements TimingRecorder {
  private final boolean enabled;
  private final int maxRuns,maxPerRun,maxTotal;
  private final long ttlNanos;
  private final Clock wall;
  private final LongSupplier nanos;
  private final LinkedHashMap<String,Entry> runs=new LinkedHashMap<>();
  private int spanCount;
  private long evictions,drops;
  private static final class Entry {
    final String id,project,session;final Instant wall;final long start;
    Long end;Status status=Status.INCOMPLETE;boolean missing;
    final LinkedHashMap<String,Child> children=new LinkedHashMap<>();
    final EnumMap<Metric,Long> milestones=new EnumMap<>(Metric.class);
    Entry(String id,String project,String session,Instant wall,long start){this.id=id;this.project=project;this.session=session;this.wall=wall;this.start=start;}
  }
  private static final class Child {
    final String id,parent,request,attempt;final Category category;final Instant wall;final long start;
    Long end;Status status=Status.INCOMPLETE;Long input,output,generated,generation;
    final EnumMap<Metric,Long> milestones=new EnumMap<>(Metric.class);
    Child(String id,String parent,String request,String attempt,Category category,Instant wall,long start){this.id=id;this.parent=parent;this.request=request;this.attempt=attempt;this.category=category;this.wall=wall;this.start=start;}
    Span snapshot(){return new Span(id,parent,request,attempt,category,status,wall,start,end,milestones,input,output,generated,generation);}
  }
  @org.springframework.beans.factory.annotation.Autowired
  public TimingStore(Environment environment,Clock wall) {
    this(environment.getProperty("rei.timing.enabled",Boolean.class,true),
        environment.getProperty("rei.timing.enabled",Boolean.class,true)?environment.getProperty("rei.timing.max-runs",Integer.class,100):100,
        environment.getProperty("rei.timing.enabled",Boolean.class,true)?environment.getProperty("rei.timing.max-spans-per-run",Integer.class,2000):2000,
        environment.getProperty("rei.timing.enabled",Boolean.class,true)?environment.getProperty("rei.timing.max-total-spans",Integer.class,10000):10000,
        environment.getProperty("rei.timing.enabled",Boolean.class,true)?org.springframework.boot.convert.DurationStyle.detectAndParse(environment.getProperty("rei.timing.ttl","1h")):Duration.ofHours(1),wall,System::nanoTime);
  }
  public TimingStore(boolean enabled,int maxRuns,int maxPerRun,int maxTotal,Duration ttl,Clock wall,LongSupplier nanos) {
    if(maxRuns<1||maxRuns>500||maxPerRun<1||maxPerRun>10000||maxTotal<maxPerRun||maxTotal>50000
        ||ttl==null||ttl.compareTo(Duration.ofSeconds(1))<0||ttl.compareTo(Duration.ofDays(1))>0)
      throw new IllegalArgumentException("Timing limits out of range");
    this.enabled=enabled;this.maxRuns=maxRuns;this.maxPerRun=maxPerRun;this.maxTotal=maxTotal;this.ttlNanos=ttl.toNanos();
    this.wall=Objects.requireNonNull(wall);this.nanos=Objects.requireNonNull(nanos);
  }
  @Override public boolean enabled(){return enabled;}
  private static final java.util.regex.Pattern ID=java.util.regex.Pattern.compile("[A-Za-z0-9._:-]+");
  private static boolean id(String value){return value!=null&&value.length()<=128&&ID.matcher(value).matches();}
  private static boolean optionalId(String value){return value==null||id(value);}
  private void purge(long now){
    var iterator=runs.values().iterator();
    while(iterator.hasNext()) {var entry=iterator.next();if(now-entry.start>=ttlNanos){spanCount-=entry.children.size();iterator.remove();evictions++;}}
  }
  private void evict(String runId){var removed=runs.remove(runId);if(removed!=null){spanCount-=removed.children.size();evictions++;}}
  @Override public synchronized boolean beginRun(String runId,String projectId,String sessionId) {
    if(!enabled)return false;long now=nanos.getAsLong();purge(now);
    if(!id(runId)||!id(projectId)||!id(sessionId)||runs.containsKey(runId))return false;
    while(runs.size()>=maxRuns)evict(runs.keySet().iterator().next());
    runs.put(runId,new Entry(runId,projectId,sessionId,wall.instant(),now));return true;
  }
  private boolean drop(Entry entry){drops++;if(entry!=null)entry.missing=true;return false;}
  @Override public synchronized boolean beginSpan(String runId,String spanId,String parentId,String requestId,String attemptId,Category category) {
    if(!enabled)return false;long now=nanos.getAsLong();purge(now);var entry=runs.get(runId);
    if(entry==null)return false;
    if(!id(spanId)||spanId.equals(runId)||!optionalId(parentId)||!optionalId(requestId)||!optionalId(attemptId)||category==null)return drop(entry);
    if(entry.children.containsKey(spanId))return false;
    String parent=parentId==null?runId:parentId;
    if(!parent.equals(runId)&&!entry.children.containsKey(parent))return drop(entry);
    if(entry.end!=null||entry.children.size()>=maxPerRun)return drop(entry);
    while(spanCount>=maxTotal) {
      String victim=runs.keySet().stream().filter(key->!key.equals(runId)).findFirst().orElse(null);
      if(victim==null)return drop(entry);evict(victim);
    }
    entry.children.put(spanId,new Child(spanId,parent,requestId,attemptId,category,wall.instant(),Math.max(0,now-entry.start)));spanCount++;return true;
  }
  @Override public synchronized boolean endSpan(String runId,String spanId,Status status) {
    if(!enabled)return false;long now=nanos.getAsLong();purge(now);var entry=runs.get(runId);
    var child=entry==null?null:entry.children.get(spanId);
    if(child==null||child.end!=null||status==null||status==Status.INCOMPLETE)return false;
    child.end=Math.max(child.start,now-entry.start);child.status=status;return true;
  }
  @Override public synchronized boolean finishRun(String runId,Status status) {
    if(!enabled)return false;long now=nanos.getAsLong();purge(now);var entry=runs.get(runId);
    if(entry==null||entry.end!=null||status==null||status==Status.INCOMPLETE)return false;
    entry.end=Math.max(0,now-entry.start);entry.status=status;return true;
  }
  @Override public synchronized void markSpan(String runId,String spanId,Metric metric) {
    if(!enabled||metric==null)return;long now=nanos.getAsLong();purge(now);var entry=runs.get(runId);
    var child=entry==null?null:entry.children.get(spanId);
    if(child!=null&&child.end==null)child.milestones.putIfAbsent(metric,Math.max(0,now-entry.start-child.start));
  }
  @Override public synchronized void markRun(String runId,Metric metric) {
    if(!enabled||metric==null)return;long now=nanos.getAsLong();purge(now);var entry=runs.get(runId);
    if(entry!=null&&entry.end==null)entry.milestones.putIfAbsent(metric,Math.max(0,now-entry.start));
  }
  private static Long valid(Long value){return value!=null&&value>=0?value:null;}
  @Override public synchronized void usage(String runId,String spanId,Long input,Long output,Long generated,Long generation) {
    if(!enabled)return;purge(nanos.getAsLong());var entry=runs.get(runId);var child=entry==null?null:entry.children.get(spanId);
    if(child==null)return;child.input=valid(input);child.output=valid(output);
    child.generated=valid(generated);child.generation=valid(generation);
    if(child.generated!=null&&child.output!=null&&child.generated>child.output){child.generated=null;entry.missing=true;}
  }
  @Override public synchronized Optional<Run> snapshot(String projectId,String sessionId,String runId) {
    if(!enabled)return Optional.empty();long now=nanos.getAsLong();purge(now);var entry=runs.get(runId);
    return entry!=null&&Objects.equals(entry.project,projectId)&&Objects.equals(entry.session,sessionId)?Optional.of(snapshot(entry,now)):Optional.empty();
  }
  @Override public synchronized Optional<Run> latest(String projectId,String sessionId) {
    if(!enabled)return Optional.empty();long now=nanos.getAsLong();purge(now);Entry latest=null;
    for(var entry:runs.values())if(Objects.equals(entry.project,projectId)&&Objects.equals(entry.session,sessionId))latest=entry;
    return latest==null?Optional.empty():Optional.of(snapshot(latest,now));
  }
  @Override public synchronized Statistics statistics(){if(enabled)purge(nanos.getAsLong());return new Statistics(runs.size(),spanCount,evictions,drops);}
  private Run snapshot(Entry entry,long now) {
    long elapsed=entry.end==null?Math.max(0,now-entry.start):entry.end;
    var spans=entry.children.values().stream().map(Child::snapshot).toList();
    boolean incomplete=entry.missing||entry.end==null||spans.stream().anyMatch(span->span.endNanos()==null);
    return new Run(entry.id,entry.project,entry.session,entry.status,entry.wall,elapsed,incomplete,spans,entry.milestones,aggregate(spans,elapsed));
  }
  private record Boundary(long at,Category category,int delta) {}
  private static Summary aggregate(List<Span> spans,long elapsed) {
    var boundaries=new ArrayList<Boundary>();long work=0;
    for(var span:spans) {
      long start=Math.min(elapsed,Math.max(0,span.startNanos()));
      long end=Math.min(elapsed,span.endNanos()==null?elapsed:Math.max(start,span.endNanos()));
      if(end>start){boundaries.add(new Boundary(start,span.category(),1));boundaries.add(new Boundary(end,span.category(),-1));work+=end-start;}
    }
    boundaries.sort(Comparator.comparingLong(Boundary::at));
    var occupancy=new EnumMap<Category,Long>(Category.class);
    for(var category:Category.values())occupancy.put(category,0L);
    int[] counts=new int[Category.values().length];int active=0;
    long previous=0,occupied=0,overlap=0,cross=0;
    int cursor=0;
    // Group boundaries at the same instant: zero-width transitions create no elapsed time.
    while(cursor<boundaries.size()) {
      long at=boundaries.get(cursor).at(),width=at-previous;int categories=0;
      for(var category:Category.values())if(counts[category.ordinal()]>0){categories++;occupancy.merge(category,width,Long::sum);}
      if(active>0)occupied+=width;if(active>=2)overlap+=width;if(categories>=2)cross+=width;
      while(cursor<boundaries.size()&&boundaries.get(cursor).at()==at){var boundary=boundaries.get(cursor++);active+=boundary.delta();counts[boundary.category().ordinal()]+=boundary.delta();}
      previous=at;
    }
    return new Summary(work,occupied,occupancy,overlap,cross,Math.max(0,elapsed-occupied));
  }
}
