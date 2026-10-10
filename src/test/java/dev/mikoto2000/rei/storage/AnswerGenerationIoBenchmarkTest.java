package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import dev.mikoto2000.rei.event.*;
import com.sun.jna.*;

/** Opt-in, real SQLite and managed Activity log; no LLM or user data. */
@Tag("integration")
@EnabledIfSystemProperty(named="rei.io.benchmark", matches="true")
class AnswerGenerationIoBenchmarkTest {
  @TempDir Path temporary;
  static final String PROJECT="00000000-0000-0000-0000-000000000001";
  public interface WindowsIo extends Library {
    WindowsIo INSTANCE=Native.load("kernel32", WindowsIo.class);
    Pointer GetCurrentProcess();
    boolean GetProcessIoCounters(Pointer process, IoCounters counters);
  }
  @Structure.FieldOrder({"readOperations","writeOperations","otherOperations","readBytes","writeBytes","otherBytes"})
  public static class IoCounters extends Structure {
    public long readOperations,writeOperations,otherOperations,readBytes,writeBytes,otherBytes;
  }
  static IoCounters io() {
    if(!System.getProperty("os.name").startsWith("Windows")) return null;
    var counters=new IoCounters();
    if(!WindowsIo.INSTANCE.GetProcessIoCounters(WindowsIo.INSTANCE.GetCurrentProcess(),counters)) return null;
    return counters;
  }
  static final class Probe implements Driver {
    final Driver delegate; final AtomicBoolean active=new AtomicBoolean();
    final AtomicLong transactions=new AtomicLong(),commits=new AtomicLong(),opens=new AtomicLong();
    Probe(Driver delegate){this.delegate=delegate;}
    public Connection connect(String url,Properties properties)throws SQLException {
      var connection=delegate.connect(url,properties); if(connection==null)return null;
      if(active.get())opens.incrementAndGet();
      return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
        try {
          Object result=method.invoke(connection,args);
          if(active.get()) {
            if(method.getName().equals("commit"))commits.incrementAndGet();
            if(method.getName().equals("setAutoCommit")&&Boolean.FALSE.equals(args[0]))transactions.incrementAndGet();
          }
          return result;
        }catch(InvocationTargetException error){throw error.getCause();}
      });
    }
    public boolean acceptsURL(String url)throws SQLException{return delegate.acceptsURL(url);}
    public DriverPropertyInfo[] getPropertyInfo(String url,Properties p)throws SQLException{return delegate.getPropertyInfo(url,p);}
    public int getMajorVersion(){return delegate.getMajorVersion();}
    public int getMinorVersion(){return delegate.getMinorVersion();}
    public boolean jdbcCompliant(){return delegate.jdbcCompliant();}
    public Logger getParentLogger(){return Logger.getGlobal();}
  }
  AgentEvent event(int index,int run,boolean thinking,int length) {
    AgentEventPayload payload=thinking?new ThinkingDeltaPayload("thought-"+run,"考".repeat(length)):new MessageDeltaPayload("answer-"+run,"あ".repeat(length));
    return new AgentEvent("event-"+run+"-"+index,0,Instant.EPOCH.plusMillis(index),thinking?AgentEventType.THINKING_DELTA:AgentEventType.MESSAGE_DELTA,1,"session-"+run,null,"run-"+run,null,null,payload,PROJECT);
  }
  @Test void measure()throws Exception {
    int offeredRate=Integer.parseInt(System.getProperty("rei.io.events-per-second","50"));
    if(offeredRate!=0&&(offeredRate<10||offeredRate>10_000))throw new IllegalArgumentException("Benchmark rate must be 0 (maximum) or 10..10000 events/second");
    Class.forName("org.sqlite.JDBC"); var original=DriverManager.getDriver("jdbc:sqlite:");
    var probe=new Probe(original); DriverManager.deregisterDriver(original); DriverManager.registerDriver(probe);
    var results=new ArrayList<Map<String,Object>>();
    boolean optimized=Boolean.getBoolean("rei.io.optimized");
    try {
      for(int repeat=0;repeat<3;repeat++)for(String scenario:List.of("short","long","high-frequency","parallel")) {
        Path root=temporary.resolve(scenario+repeat);Files.createDirectories(root);
        AnnotationConfigApplicationContext context=null;
        StorageMigrationCoordinator coordinator=null;
        try {
          if(optimized) {
            context=new AnnotationConfigApplicationContext();
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("rei.data-dir",root.toString())));
            context.register(StorageMigrationConfiguration.class);context.refresh();
          }else {coordinator=new StorageMigrationCoordinator(root);coordinator.prepare();}
          var registry=new StorageObjectRegistry(root);var project=new SqliteProjectAgentEventStore(root);
          var persistenceLatency=new ConcurrentLinkedQueue<Long>();
          var measuredProject=new ProjectAgentEventStore(root) {
            @Override public AgentEvent append(AgentEvent event) {
              long start=System.nanoTime();
              try{return project.append(event);}finally{persistenceLatency.add(System.nanoTime()-start);}
            }
          };
          var activity=new ManagedActivityLog(registry);
          var profile=new ProfileEventLogStore() {
            @Override public void append(AgentEvent event) {
              long start=System.nanoTime();
              try{super.append(event);}finally{persistenceLatency.add(System.nanoTime()-start);}
            }
          };
          profile.setManagedActivityLog(activity);
          var bus=new InMemoryAgentEventBus();var displayed=new AtomicLong();
          try(var subscription=new ProjectAgentEventSubscriber(bus,measuredProject)) {
            bus.subscribe(profile);bus.subscribe(e->displayed.incrementAndGet());
            int runs=scenario.equals("parallel")?4:1;
            int count=scenario.equals("short")?20:scenario.equals("parallel")?250:1000;
            long[] latency=new long[runs*count];
            var applicationIo=new StorageIoObservation();
            probe.transactions.set(0);probe.commits.set(0);probe.opens.set(0);
            var sampler=new WindowsDiskSampler();
            var before=io();long started=System.nanoTime();probe.active.set(true);
            try(var executor=Executors.newFixedThreadPool(runs)) {
              var futures=new ArrayList<Future<?>>();
              for(int run=0;run<runs;run++) {final int r=run;futures.add(executor.submit(()->{
                try(var observation=applicationIo.observe()) {
                for(int i=0;i<count;i++) {
                  if(offeredRate>0)await(started+(long)(i*runs+r)*(1_000_000_000L/offeredRate));
                  long start=System.nanoTime();bus.publish(event(i,r,scenario.equals("long")&&i<count/2,scenario.equals("long")?32:1));
                  latency[r*count+i]=System.nanoTime()-start;
                }
                }
              }));}
              for(var future:futures)future.get(120,TimeUnit.SECONDS);
            }finally{probe.active.set(false);sampler.close();}
            long elapsed=System.nanoTime()-started;var after=io();
            long wal=Files.exists(root.resolve("storage.db-wal"))?Files.size(root.resolve("storage.db-wal")):0;
            Arrays.sort(latency);var row=new LinkedHashMap<String,Object>();
            row.put("scenario",scenario);row.put("repeat",repeat);row.put("mode",optimized?"optimized":"baseline");
            row.put("offeredEventsPerSecond",offeredRate);
            row.put("events",runs*count);row.put("displayed",displayed.get());row.put("persistenceCalls",runs*count*2);
            row.put("transactions",probe.transactions.get());row.put("commits",probe.commits.get());row.put("connectionOpens",probe.opens.get());
            row.put("explicitForceCalls",applicationIo.forces.sum());row.put("activityWrites",applicationIo.writes.sum());row.put("activityWriteBytes",applicationIo.bytes.sum());
            row.put("elapsedMs",elapsed/1e6);row.put("publishP95Ms",latency[(int)Math.ceil(latency.length*.95)-1]/1e6);
            row.put("publishMeanMs",Arrays.stream(latency).average().orElseThrow()/1e6);row.put("walBytes",wal);
            var persistenceTimes=persistenceLatency.stream().mapToLong(Long::longValue).sorted().toArray();
            row.put("persistenceP95Ms",persistenceTimes[(int)Math.ceil(persistenceTimes.length*.95)-1]/1e6);
            row.put("persistenceMeanMs",Arrays.stream(persistenceTimes).average().orElseThrow()/1e6);
            if(before!=null&&after!=null){row.put("processWriteOperations",after.writeOperations-before.writeOperations);row.put("processWriteBytes",after.writeBytes-before.writeBytes);}
            row.put("queueDepth",0);row.putAll(sampler.summary());results.add(row);
            assertThat(displayed.get()).isEqualTo(runs*count);assertThat(probe.commits.get()).isEqualTo(runs*count*2);
            assertThat(activity.read(PROJECT)).hasSize(runs*count);
            assertThat(project.lastSequence(PROJECT)).isEqualTo(runs*count);
          }
        }finally {if(context!=null)context.close();if(coordinator!=null)coordinator.close();}
      }
    }finally {DriverManager.deregisterDriver(probe);DriverManager.registerDriver(original);}
    Path output=Path.of("target/answer-generation-io-"+(optimized?"optimized":"baseline")+".json");Files.createDirectories(output.getParent());
    Files.writeString(output,StorageDatabase.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results));
  }
  private static void await(long deadline) {
    long remaining;
    while((remaining=deadline-System.nanoTime())>0) {
      if(Thread.currentThread().isInterrupted())throw new CancellationException("Benchmark interrupted");
      java.util.concurrent.locks.LockSupport.parkNanos(remaining);
    }
  }
}
