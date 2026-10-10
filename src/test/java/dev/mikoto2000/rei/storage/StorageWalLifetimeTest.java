package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import dev.mikoto2000.rei.event.*;

@Tag("integration")
class StorageWalLifetimeTest {
  @TempDir Path root;
  AnnotationConfigApplicationContext context() {
    var context = new AnnotationConfigApplicationContext();
    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("rei.data-dir", root.toString())));
    context.register(StorageMigrationConfiguration.class);
    context.refresh();
    return context;
  }
  void write(String id) {
    new StorageDatabase(root).transaction(db -> {
      try (var sql = db.prepareStatement("INSERT INTO event_sequences VALUES(?,1)")) {
        sql.setString(1, id); sql.executeUpdate();
      }
      return null;
    });
  }
  @Test void writerConnectionIsReusedWhileEveryWriteCommitsIndependently() {
    try(var context=context()) {
      var connections=new ArrayList<java.sql.Connection>();
      for(int i=0;i<3;i++) {
        String id="write-"+i;
        new StorageDatabase(root).transaction(db->{
          connections.add(db);
          try(var sql=db.prepareStatement("INSERT INTO event_sequences VALUES(?,1)")){sql.setString(1,id);sql.executeUpdate();}
          return null;
        });
        new StorageDatabase(root).read(db->{try(var sql=db.prepareStatement("SELECT sequence FROM event_sequences WHERE project_id=?")){sql.setString(1,id);try(var rows=sql.executeQuery()){assertThat(rows.next()).isTrue();}}return null;});
      }
      assertThat(connections.get(1)).isSameAs(connections.get(0));
      assertThat(connections.get(2)).isSameAs(connections.get(0));
    }
  }
  @Test void contextKeepsWalAliveWithoutDeferringCommitsOrChangingPragmas() throws Exception {
    try (var context = context()) {
      write("committed");
      assertThat(Files.exists(root.resolve("storage.db-wal"))).isTrue();
      assertThat(Files.size(root.resolve("storage.db-wal"))).isPositive();
      new StorageDatabase(root).read(db -> {
        try (var sql = db.createStatement()) {
          try (var rows = sql.executeQuery("SELECT sequence FROM event_sequences WHERE project_id='committed'")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(1);
          }
          for (var expected : Map.of("synchronous", 2, "wal_autocheckpoint", 1000).entrySet()) {
            try (var rows = sql.executeQuery("PRAGMA " + expected.getKey())) { assertThat(rows.getInt(1)).isEqualTo(expected.getValue()); }
          }
        }
        return null;
      });
    }
    assertThat(Files.exists(root.resolve("storage.db-wal"))).isFalse();
    try (var restarted = context()) { write("after-restart"); }
  }
  @Test void sharedContextsReleaseOnlyAfterLastOwnerAndCloseIsIdempotent() {
    var first = context(); var second = context();
    try {
      write("one"); first.close(); first.close();
      assertThat(Files.exists(root.resolve("storage.db-wal"))).isTrue();
      write("two");
    } finally { first.close(); second.close(); }
    assertThat(Files.exists(root.resolve("storage.db-wal"))).isFalse();
  }
  @Test void rollbackStillAtomicAndIdleConnectionDoesNotBlockMaintenance() {
    try(var context=context()) {
      var database=new StorageDatabase(root);
      assertThatThrownBy(()->database.transaction(db->{
        try(var sql=db.createStatement()){sql.executeUpdate("INSERT INTO event_sequences VALUES('rolled-back',1)");}
        throw new java.io.IOException("simulated failure");
      })).isInstanceOf(IllegalStateException.class);
      database.read(db->{try(var sql=db.createStatement();var rows=sql.executeQuery("SELECT count(*) FROM event_sequences")){assertThat(rows.getInt(1)).isZero();}return null;});
      write("committed");
      var maintenance=new StorageMaintenance(context.getBean(StorageObjectRegistry.class));
      var checkpoint=maintenance.checkpoint();
      assertThat(checkpoint.busy()).isZero();
      assertThat(checkpoint.checkpointedFrames()).isEqualTo(checkpoint.logFrames());
      maintenance.vacuum(java.time.Duration.ofSeconds(5));
    }
  }
  @Test void nestedWriteCannotCommitOuterPartialData() {
    try(var context=context()) {
      var database=new StorageDatabase(root);
      assertThatThrownBy(()->database.transaction(db->{
        try(var sql=db.createStatement()){sql.executeUpdate("INSERT INTO event_sequences VALUES('outer',1)");}
        write("inner");return null;
      })).isInstanceOf(IllegalStateException.class);
      database.read(db->{try(var sql=db.createStatement();var rows=sql.executeQuery("SELECT count(*) FROM event_sequences")){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero();}return null;});
      write("after-rollback");
    }
  }
  @Test void unusableWriterIsReleasedAndReopenedAfterRollbackFailure() {
    try(var context=context()) {
      var database=new StorageDatabase(root);
      assertThatThrownBy(()->database.transaction(db->{db.close();return null;})).isInstanceOf(IllegalStateException.class);
      write("recovered");
      database.read(db->{try(var sql=db.createStatement();var rows=sql.executeQuery("SELECT sequence FROM event_sequences WHERE project_id='recovered'")){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(1);}return null;});
    }
    assertThat(Files.exists(root.resolve("storage.db-wal"))).isFalse();
  }
  @Test void failedContextInitializationReleasesBothLeases() throws Exception {
    var context=new AnnotationConfigApplicationContext();
    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("rei.data-dir",root.toString())));
    context.register(StorageMigrationConfiguration.class);
    context.registerBean("broken",Object.class,()->{write("startup-write");throw new IllegalStateException("startup failed");});
    try {assertThatThrownBy(context::refresh).hasRootCauseMessage("startup failed");}finally{context.close();}
    assertThat(Files.exists(root.resolve("storage.db-wal"))).isFalse();
    try(var migration=new StorageMigrationCoordinator(root)){migration.prepare();}
  }
  @Test void automaticCheckpointStillWritesBackWithoutExplicitMaintenance() throws Exception {
    try(var context=context()) {
      var database=new StorageDatabase(root);
      database.transaction(db->{try(var sql=db.createStatement()){sql.execute("CREATE TABLE checkpoint_fixture(id INTEGER PRIMARY KEY,body TEXT)");}return null;});
      long before=Files.size(root.resolve("storage.db"));
      for(int i=0;i<50;i++) {
        int id=i;
        database.transaction(db->{try(var sql=db.prepareStatement("INSERT INTO checkpoint_fixture VALUES(?,?)")){sql.setInt(1,id);sql.setString(2,"x".repeat(100_000));sql.executeUpdate();}return null;});
      }
      assertThat(Files.size(root.resolve("storage.db"))).isGreaterThan(before);
      assertThat(Files.exists(root.resolve("storage.db-wal"))).isTrue();
      database.read(db->{try(var sql=db.createStatement();var rows=sql.executeQuery("SELECT count(*) FROM checkpoint_fixture")){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(50);}return null;});
    }
  }
  @Test void committedWalSurvivesAbnormalProcessExitAndRestart() throws Exception {
    try(var prepared=context()) { write("before-crash"); }
    String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
    Path arguments=root.resolve("java.args");
    Files.writeString(arguments,"-cp\n"+quote(classpath)+"\n"+StorageWalCrashWorker.class.getName()+"\n"+quote(root.toString()));
    String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
    var worker=new ProcessBuilder(executable,"@"+arguments).redirectErrorStream(true).redirectOutput(root.resolve("worker.log").toFile()).start();
    try {
      assertThat(worker.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(worker.exitValue()).withFailMessage(Files.readString(root.resolve("worker.log"))).isZero();
    }finally{if(worker.isAlive()){worker.destroyForcibly();worker.waitFor(10,java.util.concurrent.TimeUnit.SECONDS);}}
    assertThat(Files.size(root.resolve("storage.db-wal"))).isPositive();
    try(var restarted=context()) {
      new StorageDatabase(root).read(db->{try(var sql=db.createStatement();var rows=sql.executeQuery("SELECT sequence FROM event_sequences WHERE project_id='crash-committed'")){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(42);}return null;});
      write("after-crash");
    }
  }
  private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value=AgentEventType.class,names={"AGENT_RUN_COMPLETED","AGENT_RUN_CANCELLED","AGENT_RUN_FAILED"})
  void streamToolAndTerminalEventsStayImmediatelyDurableAcrossProjectsAndRestart(AgentEventType terminal) {
    var expected=new LinkedHashMap<String,List<String>>();
    var factory=new AgentEventFactory(java.time.Clock.fixed(java.time.Instant.EPOCH,java.time.ZoneOffset.UTC));
    try(var context=context()) {
      var store=new SqliteProjectAgentEventStore(root);var bus=new InMemoryAgentEventBus();
      var activity=new ManagedActivityLog(context.getBean(StorageObjectRegistry.class));
      var profile=new ProfileEventLogStore();profile.setManagedActivityLog(activity);
      var delivered=new ArrayList<String>();
      try(var subscriber=new ProjectAgentEventSubscriber(bus,store)) {
        bus.subscribe(profile);bus.subscribe(e->delivered.add(e.id()));
        for(int run=0;run<2;run++) {
          String project=UUID.randomUUID().toString(),id="run-"+run;
          var owner=new dev.mikoto2000.rei.core.chat.AgentRunContext(id,"session-"+run,root,project);
          var error=new ErrorInformation("Synthetic","test",null);
          var end=switch(terminal){case AGENT_RUN_COMPLETED->factory.runCompleted(id,1);case AGENT_RUN_CANCELLED->factory.runCancelled(id,error);default->factory.runFailed(id,error);};
          var events=List.of(factory.runStarted(id,"test",null),factory.thinkingDelta("thought-"+run,"stream-only-content"),
              factory.messageDelta("answer-"+run,"stream-only-content"),factory.toolStarted("tool-"+run,"test","input"),
              factory.toolCompleted("tool-"+run,"test",1,"ok"),factory.messageDelta("answer-"+run,"tail"),end);
          var ids=new ArrayList<String>();
          for(var original:events) {
            var event=original.withOwnership(owner);ids.add(event.id());
            if(event.type()==AgentEventType.MESSAGE_DELTA||event.type()==AgentEventType.THINKING_DELTA)bus.publish(event);else bus.publishBoundary(event);
            assertThat(delivered).contains(event.id());
            assertThat(store.referenceStatus(project,Set.of(event.id()))).containsEntry(event.id(),"available");
          }
          expected.put(project,ids);
          assertThat(activity.read(project)).hasSize(events.size());
          assertThat(activity.read(project).toString()).doesNotContain("stream-only-content");
        }
      }
    }
    try(var restarted=context()) {
      var store=new SqliteProjectAgentEventStore(root);
      expected.forEach((project,ids)->assertThat(store.readAfter(project,0,100)).extracting(AgentEvent::id).containsExactlyElementsOf(ids));
    }
  }
}
