package dev.mikoto2000.rei.storage;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.event.*;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class StorageEventMigrationTest {
  @TempDir Path root;
  final String project=UUID.randomUUID().toString();
  final Instant now=Instant.parse("2026-10-01T00:00:00.123456789Z");
  void prepare()throws Exception{try(var migration=new StorageMigrationCoordinator(root)){migration.prepare();}}
  AgentEvent event(String id,String run){return new AgentEvent(id,999,now,AgentEventType.AGENT_RUN_COMPLETED,1,"session",null,run,null,null,new AgentRunCompletedPayload(run,1),project);}
  Path source(){return root.resolve("projects").resolve(project).resolve("events/events.jsonl");}
  void legacyCursor(long offset,boolean discard,long generation)throws Exception {
    try(var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("memory-consolidation.db"));var query=db.createStatement()) {
      query.execute("CREATE TABLE agent_schedule_event_cursors(project TEXT PRIMARY KEY,offset INTEGER NOT NULL,discard INTEGER NOT NULL,generation INTEGER NOT NULL)");
      try(var insert=db.prepareStatement("INSERT INTO agent_schedule_event_cursors VALUES(?,?,?,?)")){insert.setString(1,project);insert.setLong(2,offset);insert.setBoolean(3,discard);insert.setLong(4,generation);insert.executeUpdate();}
    }
  }
  @Test void importPreservesTypedFactsIdsSequenceTimestampAndOriginalBytesAndNewWritesUseOnlySqlite()throws Exception {
    var legacy=new ProjectAgentEventStore(root);var one=legacy.append(event("イベント一","run1"));var two=legacy.append(event("イベント二","run2"));
    String hash=StorageBackup.hash(source());prepare();var store=new SqliteProjectAgentEventStore(root);
    assertThat(store.recent(project,10)).containsExactly(one,two);assertThat(store.readAfter(project,1,10)).containsExactly(two);
    var next=store.append(event("third","run3"));assertThat(next.sequence()).isEqualTo(3);assertThat(StorageBackup.hash(source())).isEqualTo(hash);
    prepare();assertThat(new SqliteProjectAgentEventStore(root).recent(project,10)).hasSize(3);assertThat(store.referenceStatus(project,Set.of(one.id(),"missing"))).containsEntry(one.id(),"available").containsEntry("missing","not-found");
  }
  @Test void byteCursorAtUnicodeBoundaryMapsToSequenceAndKeepsGenerationCas()throws Exception {
    var legacy=new ProjectAgentEventStore(root);legacy.append(event("日本語一","run1"));long offset=Files.size(source());var two=legacy.append(event("日本語二","run2"));legacyCursor(offset,false,7);
    prepare();var cursors=new SqliteEventCursorStore(root);var cursor=cursors.get(project);
    assertThat(cursor.sequence()).isEqualTo(1);assertThat(cursor.generation()).isEqualTo(7);
    var page=new SqliteProjectAgentEventStore(root).readPage(project,cursor.sequence(),false);assertThat(page.events()).containsExactly(two);assertThat(page.nextOffset()).isEqualTo(2);
    assertThat(cursors.compareAndSet(project,cursor,page.nextOffset())).isTrue();cursors.reset(project);
    assertThat(cursors.compareAndSet(project,cursor,999)).isFalse();assertThat(cursors.get(project).sequence()).isZero();assertThat(cursors.get(project).generation()).isEqualTo(8);
    try(var db=StorageBackup.readOnly(root.resolve("memory-consolidation.db"));var query=db.createStatement();var rows=query.executeQuery("SELECT offset,generation FROM agent_schedule_event_cursors")){rows.next();assertThat(rows.getLong(1)).isEqualTo(offset);assertThat(rows.getLong(2)).isEqualTo(7);}
  }
  @Test void incompleteCorruptAndDuplicateSourcesStopStartupWithoutLosingBytes()throws Exception {
    var legacy=new ProjectAgentEventStore(root);legacy.append(event("one","run"));Files.writeString(source(),"{partial",StandardOpenOption.APPEND);String hash=StorageBackup.hash(source());
    assertThatThrownBy(this::prepare).isInstanceOf(java.io.IOException.class);assertThat(StorageBackup.hash(source())).isEqualTo(hash);assertThat(StorageMigrationCoordinatorTest.version(root.resolve("storage.db"))).isZero();
    String first=Files.readString(source()).replace("{partial","");Files.writeString(source(),first+first);
    assertThatThrownBy(this::prepare).isInstanceOf(java.io.IOException.class);assertThat(Files.readString(source())).isEqualTo(first+first);
    Files.writeString(source(),first);prepare();assertThat(new SqliteProjectAgentEventStore(root).recent(project,10)).hasSize(1);
  }
  @Test void oversizedLegacyFactIsPreservedInHistoryButDoesNotBecomeANewSchedulerReplay()throws Exception {
    var legacy=new ProjectAgentEventStore(root);var large=legacy.append(new AgentEvent("large",0,now,AgentEventType.USER_INTERVENTION_RECEIVED,1,"session",null,"run",null,null,new UserInterventionPayload("input","x".repeat(300*1024)),project));
    var two=legacy.append(event("two","source"));legacyCursor(256*1024,true,5);prepare();var store=new SqliteProjectAgentEventStore(root);
    assertThat(store.recent(project,10)).containsExactly(large,two);var cursor=new SqliteEventCursorStore(root).get(project);assertThat(cursor.sequence()).isZero();
    assertThat(store.readPage(project,cursor.sequence(),false).events()).containsExactly(two);
  }
  @Test void multipleStoreInstancesSerializeProjectSequenceAllocation()throws Exception {
    prepare();var one=new SqliteProjectAgentEventStore(root);var two=new SqliteProjectAgentEventStore(root);
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var jobs=new ArrayList<java.util.concurrent.Future<?>>();for(int i=0;i<20;i++){int id=i;jobs.add(pool.submit(()->(id%2==0?one:two).append(event("id-"+id,"run-"+id))));}for(var job:jobs)job.get();
    }
    assertThat(one.readAfter(project,0,100)).extracting(AgentEvent::sequence).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1,20).boxed().toList());
    assertThat(one.lastSequence(project)).isEqualTo(20);assertThat(source()).doesNotExist();
  }
  @Test void globalInventoryIncludesProjectEventRowsWithoutPlanningAcrossScopes()throws Exception {
    prepare();new SqliteProjectAgentEventStore(root).append(event("one","run"));
    var planner=new RetentionPlanner(new StorageObjectRegistry(root));
    assertThat(planner.status(null).objects()).isEmpty();
    assertThat(planner.statusAll().objects()).extracting(RetentionPlanner.ObjectUsage::kind).contains("EVENT");
    assertThat(planner.root()).isEqualTo(root.toAbsolutePath().normalize());
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"interior-normal","boundary-discard","zero-discard","foreign-project","invalid-utf8","oversized","trailing-json"})
  void ambiguousCursorsAndUnverifiedRecordsNeverBecomeSuccessfulImports(String kind)throws Exception {
    new ProjectAgentEventStore(root).append(event("one","run"));
    if(kind.equals("interior-normal"))legacyCursor(5,false,4);
    if(kind.equals("boundary-discard"))legacyCursor(Files.size(source()),true,4);
    if(kind.equals("zero-discard"))legacyCursor(0,true,4);
    if(kind.equals("foreign-project"))Files.writeString(source(),Files.readString(source()).replace(project,UUID.randomUUID().toString()));
    if(kind.equals("invalid-utf8"))Files.write(source(),new byte[]{(byte)0xc3,0x28,0x0a},StandardOpenOption.APPEND);
    if(kind.equals("oversized"))Files.writeString(source()," ".repeat(1024*1024+1)+"\n",StandardOpenOption.APPEND);
    if(kind.equals("trailing-json"))Files.writeString(source(),Files.readString(source()).stripTrailing()+" {}\n");
    String hash=StorageBackup.hash(source());assertThatThrownBy(this::prepare).isInstanceOf(java.io.IOException.class);
    assertThat(StorageBackup.hash(source())).isEqualTo(hash);assertThat(StorageMigrationCoordinatorTest.version(root.resolve("storage.db"))).isZero();
  }
  @Test void productionBeansReplayOnlyTheUnprocessedSuffixAndKeepSchedulerDuplicateSuppression()throws Exception {
    var clock=Clock.fixed(now.minusSeconds(1),ZoneOffset.UTC);var dataSource=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("memory-consolidation.db"));
    var scheduler=new dev.mikoto2000.rei.temporal.PersistentAgentScheduler(dataSource,clock);String id;
    try(var scope=dev.mikoto2000.rei.core.chat.AgentRunScope.open(new dev.mikoto2000.rei.core.chat.AgentRunContext("parent","session",root,project))) {
      id=scheduler.scheduleOnEvent("source",AgentEventType.AGENT_RUN_COMPLETED,Duration.ofHours(1),"continue","session").id();
    }scheduler.activate(project,id);
    var legacy=new ProjectAgentEventStore(root);legacy.append(event("already-read","other"));long offset=Files.size(source());var matching=legacy.append(event("matching","source"));
    try(var connection=dataSource.getConnection();var update=connection.prepareStatement("UPDATE agent_schedule_event_cursors SET offset=?,generation=7 WHERE project=?")){update.setLong(1,offset);update.setString(2,project);update.executeUpdate();}
    try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",Map.of("rei.data-dir",root.toString())));
      context.register(Config.class);context.refresh();var store=context.getBean(ProjectAgentEventStore.class);assertThat(store).isInstanceOf(SqliteProjectAgentEventStore.class);
      var restarted=context.getBean(dev.mikoto2000.rei.temporal.PersistentAgentScheduler.class);var cursors=context.getBean(SqliteEventCursorStore.class);assertThat(cursors.get(project).sequence()).isEqualTo(1);
      try(var triggers=new dev.mikoto2000.rei.temporal.AgentEventTriggerService(restarted,new InMemoryAgentEventBus(),store)) {
        triggers.tick();assertThat(cursors.get(project).sequence()).isEqualTo(2);var claim=restarted.claimDue().orElseThrow();assertThat(claim.task().id()).isEqualTo(id);
        assertThat(restarted.eventTrigger(project,id).orElseThrow().matchedEventId()).isEqualTo(matching.id());triggers.tick();restarted.signalEvent(matching);assertThat(restarted.claimDue()).isEmpty();restarted.finish(claim,"COMPLETED","ok");
        assertThat(restarted.history(project,id).stream().filter(h->h.status().equals("SCHEDULED")).count()).isEqualTo(1);
      }
    }
    try(var connection=dataSource.getConnection();var query=connection.createStatement();var rows=query.executeQuery("SELECT offset,generation FROM agent_schedule_event_cursors")){rows.next();assertThat(rows.getLong(1)).isEqualTo(offset);assertThat(rows.getLong(2)).isEqualTo(7);}
  }
  @Test void hundredThousandEventsMigrateInSixtyFourMiBWithoutChangingSource()throws Exception {
    Files.createDirectories(source().getParent());
    try(var output=Files.newBufferedWriter(source())) {
      for(int i=1;i<=100000;i++) {
        var fact=new AgentEvent("event-"+i,i,now,AgentEventType.AGENT_RUN_COMPLETED,1,"session",null,"run-"+i,null,null,new AgentRunCompletedPayload("run-"+i,1),project);
        output.write(EventJsonCodec.mapper().writeValueAsString(fact));output.newLine();
      }
    }
    String hash=StorageBackup.hash(source());var child=worker(StorageEventImportWorker.class,project,"-Xmx64m");
    try {
      assertThat(child.waitFor(180,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(child.exitValue()).describedAs(Files.readString(root.resolve("worker.log"))).isZero();
      assertThat(Files.readString(root.resolve("worker.log"))).contains("IMPORTED 100000");
    }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS);}}
    assertThat(StorageBackup.hash(source())).isEqualTo(hash);
  }
  private Process worker(Class<?> type,String argument,String heap)throws Exception {
    String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
    Path args=root.resolve("worker.args");Files.writeString(args,heap+"\n-cp\n"+javaArgument(classpath)+"\n"+type.getName()+"\n"+javaArgument(root.toString())+"\n"+javaArgument(argument)+"\n");
    return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"@"+args).redirectErrorStream(true).redirectOutput(root.resolve("worker.log").toFile()).start();
  }
  private static String javaArgument(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
  @Test void killedSchemaThreeUpgradeRetriesEventsAndCursorExactlyOnce()throws Exception {
    prepare();
    try(var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("storage.db"));var sql=db.createStatement()) {
      for(String table:List.of("agent_events","event_sequences","event_cursors","event_imports","retention_executions","retention_execution_items","retention_purge_approvals","activity_segments","retention_automatic_consents","retention_automatic_runs"))sql.execute("DROP TABLE "+table);
      sql.executeUpdate("DELETE FROM storage_migrations WHERE version>3");sql.execute("PRAGMA user_version=3");
    }
    var legacy=new ProjectAgentEventStore(root);legacy.append(event("before","run1"));long offset=Files.size(source());legacy.append(event("after","run2"));legacyCursor(offset,false,11);String hash=StorageBackup.hash(source());
    var child=worker(StorageMigrationCrashWorker.class,"rows-imported","-Xmx64m");
    try {
      long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
      while(System.nanoTime()<deadline&&child.isAlive()&&!Files.readString(root.resolve("worker.log")).contains("APPLYING"))Thread.sleep(50);
      assertThat(Files.readString(root.resolve("worker.log"))).contains("APPLYING");
      assertThatThrownBy(()->new StorageMigrationCoordinator(root)).isInstanceOf(java.io.IOException.class);
    }finally{child.destroyForcibly();assertThat(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    assertThat(StorageMigrationCoordinatorTest.version(root.resolve("storage.db"))).isEqualTo(3);
    prepare();prepare();assertThat(new SqliteProjectAgentEventStore(root).recent(project,100)).extracting(AgentEvent::id).containsExactly("before","after");
    var cursor=new SqliteEventCursorStore(root).get(project);assertThat(cursor.sequence()).isEqualTo(1);assertThat(cursor.generation()).isEqualTo(11);
    assertThat(StorageBackup.hash(source())).isEqualTo(hash);
  }
  @org.springframework.context.annotation.Configuration
  @org.springframework.context.annotation.Import({StorageMigrationConfiguration.class,dev.mikoto2000.rei.event.EventStorageConfiguration.class})
  static class Config {
    @org.springframework.context.annotation.Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-01T00:00:02Z"),ZoneOffset.UTC);}
    @org.springframework.context.annotation.Bean javax.sql.DataSource memoryConsolidationDataSource(@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String root){return new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+Path.of(root).resolve("memory-consolidation.db"));}
    @org.springframework.context.annotation.Bean dev.mikoto2000.rei.temporal.PersistentAgentScheduler scheduler(javax.sql.DataSource source,Clock clock){return new dev.mikoto2000.rei.temporal.PersistentAgentScheduler(source,clock);}
  }
}
