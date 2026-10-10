package dev.mikoto2000.rei.storage;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class SessionTurnMigrationTest {
  @TempDir Path root;
  final Instant now=Instant.parse("2026-10-01T00:00:00.123456789Z");
  SessionMetadata metadata(String id){return new SessionMetadata(id,"project","日本語の会話",now,now);}
  void prepare()throws Exception{try(var migration=new StorageMigrationCoordinator(root)){migration.prepare();}}
  @Test void legacyImportPreservesRowsAndRestartNeverOverwritesNewWrites()throws Exception {
    var legacy=new FileSessionRepository(root.resolve("sessions.json"));legacy.accept(metadata("chat:one"),()->{});
    var turns=new ConversationTurnStore(root);var context=new AgentRunContext("run","chat:one",root);
    turns.start(context,"質問",now);turns.finish(context,ConversationTurnStore.Status.CANCELLED,"回答");
    turns.recordMetadata(context,Map.of("時間","一秒"));
    String before=StorageBackup.hash(root.resolve("sessions.json"));var expected=turns.read("chat:one");
    prepare();var sessions=new SqliteSessionRepository(root);var sqlTurns=new SqliteConversationTurnStore(root);
    assertThat(sessions.findById("chat:one")).contains(metadata("chat:one"));
    assertThat(sqlTurns.read("chat:one")).isEqualTo(expected);
    assertThat(sqlTurns.cancelledContext("chat:one")).contains("質問");
    sessions.accept(metadata("new"),()->{});sqlTurns.start(new AgentRunContext("next","chat:one",root),"続き",now.plusNanos(1));
    prepare();assertThat(new SqliteSessionRepository(root).findById("new")).isPresent();
    assertThat(new SqliteConversationTurnStore(root).read("chat:one")).hasSize(2);
    assertThat(StorageBackup.hash(root.resolve("sessions.json"))).isEqualTo(before);
  }
  @Test void admissionCommitsBeforeCallbackAndRejectRestoresExactlyPriorMetadata()throws Exception {
    prepare();var store=new SqliteSessionRepository(root);var first=metadata("one");
    store.accept(first,()->assertThat(new SqliteSessionRepository(root).findById("one")).contains(first));
    assertThatThrownBy(()->store.accept(first.touched(now.plusSeconds(2)),()->{throw new IllegalStateException("reject");})).hasMessage("reject");
    assertThat(new SqliteSessionRepository(root).findById("one")).contains(first);
    assertThatThrownBy(()->store.accept(metadata("new"),()->{throw new IllegalStateException("reject");})).hasMessage("reject");
    assertThat(store.findById("new")).isEmpty();
    store.accept(first.touched(now.plusSeconds(3)),()->{});store.accept(first,()->{});
    assertThat(store.findById("one")).contains(first.touched(now.plusSeconds(3)));
  }
  @Test void unicodeKeysetAndNanosecondTurnOrderMatchExistingJavaApi()throws Exception {
    prepare();var sessions=new SqliteSessionRepository(root);
    for(String id:List.of("\uE000","\uD800\uDC00","a"))sessions.accept(metadata(id),()->{});
    assertThat(sessions.findPage(null,null,2)).extracting(SessionMetadata::sessionId).containsExactly("a","\uD800\uDC00");
    assertThat(sessions.findPage(null,new CursorKey(now,"\uD800\uDC00"),2)).extracting(SessionMetadata::sessionId).containsExactly("\uE000");
    var turns=new SqliteConversationTurnStore(root);
    for(String id:List.of("z","a","0"))turns.startOrdered(new AgentRunContext(id,"chat",root),id,now.minusSeconds(1));
    assertThat(turns.findTurns("chat",null,101)).extracting(SessionTurn::runId).containsExactly("z","a","0");
    assertThat(turns.read("chat").getLast().createdAt()).isEqualTo(now.minusSeconds(1).plusNanos(2));
  }
  @Test void projectAndGlobalTurnFilesRemainIsolatedIncludingUncataloguedConversations()throws Exception {
    String project=UUID.randomUUID().toString(),conversation="project:"+project+":chat";
    var old=new ConversationTurnStore(root);
    old.start(new AgentRunContext("p",conversation,root),"project",null);
    old.start(new AgentRunContext("g","chat",root),"global",now);
    prepare();var turns=new SqliteConversationTurnStore(root);
    assertThat(turns.read(conversation)).extracting(ConversationTurnStore.Turn::runId).containsExactly("p");
    assertThat(turns.findTurns(conversation,null,10)).isEmpty();
    assertThat(turns.read("chat")).extracting(ConversationTurnStore.Turn::runId).containsExactly("g");
  }
  @Test void malformedOrDuplicateLegacyRowsAbortAtomicImportAndRetainSource()throws Exception {
    String rows=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .writeValueAsString(List.of(metadata("one"),metadata("one")));
    Files.writeString(root.resolve("sessions.json"),rows);
    assertThatThrownBy(this::prepare).isInstanceOf(java.io.IOException.class);
    assertThat(Files.readString(root.resolve("sessions.json"))).isEqualTo(rows);
    assertThat(StorageMigrationCoordinatorTest.version(root.resolve("storage.db"))).isZero();
    Files.writeString(root.resolve("sessions.json"),"[]");prepare();
    assertThat(new SqliteSessionRepository(root).findPage(null,null,10)).isEmpty();
  }
  @Configuration @Import({StorageMigrationConfiguration.class,SessionHistoryConfiguration.class}) static class Config {
    @Bean Clock clock(){return Clock.systemUTC();}
  }
  @Test void foundationVersionOneUpgradesAtomicallyAndRetainsItsBackupAndMarker()throws Exception {
    try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("storage.db"));var query=db.createStatement()) {
      query.execute("CREATE TABLE storage_migrations(version INTEGER PRIMARY KEY,status TEXT NOT NULL,backup TEXT,completed TEXT NOT NULL)");
      query.execute("INSERT INTO storage_migrations VALUES(1,'COMPLETE',NULL,'2026-10-01')");query.execute("PRAGMA user_version=1");
    }
    new FileSessionRepository(root.resolve("sessions.json")).accept(metadata("one"),()->{});
    Path backup;
    try(var migration=new StorageMigrationCoordinator(root)){backup=migration.prepare().backup();}
    assertThat(StorageMigrationCoordinatorTest.version(backup.resolve("files/storage.db"))).isEqualTo(1);
    assertThat(StorageMigrationCoordinatorTest.version(root.resolve("storage.db"))).isEqualTo(2);
    assertThat(new SqliteSessionRepository(root).findById("one")).contains(metadata("one"));
  }
  @Test void trailingOrBrokenTurnJsonRollsBackEvenPreviouslyImportedSessions()throws Exception {
    new FileSessionRepository(root.resolve("sessions.json")).accept(metadata("one"),()->{});
    Path turn=Files.createDirectories(root.resolve("state/turns")).resolve(SqliteConversationTurnStore.key("one")+".json");
    Files.writeString(turn,"[] {} ");
    assertThatThrownBy(this::prepare).isInstanceOf(java.io.IOException.class);
    assertThat(StorageMigrationCoordinatorTest.version(root.resolve("storage.db"))).isZero();
    assertThat(Files.readString(turn)).isEqualTo("[] {} ");
    Files.writeString(turn,"[");assertThatThrownBy(this::prepare).isInstanceOf(java.io.IOException.class);
    Files.writeString(turn,"[]");prepare();assertThat(new SqliteSessionRepository(root).findById("one")).isPresent();
  }
  @Test void sourceOrBackupChangedAfterRowsImportCannotCommitAndRetryImportsOnlyOnce()throws Exception {
    new FileSessionRepository(root.resolve("sessions.json")).accept(metadata("one"),()->{});
    String original=Files.readString(root.resolve("sessions.json"));
    try(var migration=new StorageMigrationCoordinator(root,(stage,backup)->{
      if(stage==StorageMigrationCoordinator.Stage.ROWS_IMPORTED)Files.writeString(root.resolve("sessions.json"),"[]");
    })){assertThatThrownBy(migration::prepare).isInstanceOf(java.io.IOException.class);}
    assertThat(StorageMigrationCoordinatorTest.version(root.resolve("storage.db"))).isZero();
    Files.writeString(root.resolve("sessions.json"),original);
    try(var migration=new StorageMigrationCoordinator(root,(stage,backup)->{
      if(stage==StorageMigrationCoordinator.Stage.ROWS_IMPORTED)Files.writeString(backup.resolve("files/sessions.json"),"[]");
    })){assertThatThrownBy(migration::prepare).isInstanceOf(java.io.IOException.class);}
    assertThat(StorageMigrationCoordinatorTest.version(root.resolve("storage.db"))).isZero();
    prepare();assertThat(new SqliteSessionRepository(root).findPage(null,null,100)).containsExactly(metadata("one"));
  }
  @Test void terminalUpdatesMetadataAndNotificationDeduplicationSurviveRestart()throws Exception {
    prepare();var turns=new SqliteConversationTurnStore(root);var context=new AgentRunContext("same","chat",root);
    turns.start(context,"one",now);turns.start(context,"two",now);turns.finish(context,ConversationTurnStore.Status.COMPLETED,"answer");
    turns.start(context,"three",now);turns.finish(context,ConversationTurnStore.Status.CANCELLED);
    turns.recordMetadata(context,Map.of("timing","1"));
    var notification=new ConversationLogEntry("chat","scope","assistant",now.atOffset(ZoneOffset.UTC),"通知",0,
        ConversationLogStore.BEHAVIOR_NOTIFICATION,"notification",Map.of("由来","テスト"));
    turns.appendAssistantNotification(notification);new SqliteConversationTurnStore(root).appendAssistantNotification(notification);
    turns.finish(context,ConversationTurnStore.Status.FAILED,"must not replace terminal rows");
    assertThat(turns.read("chat")).extracting(ConversationTurnStore.Turn::status).containsExactly(ConversationTurnStore.Status.COMPLETED,ConversationTurnStore.Status.COMPLETED,ConversationTurnStore.Status.CANCELLED,ConversationTurnStore.Status.COMPLETED);
    assertThat(turns.read("chat").getFirst().assistantMessage()).isEqualTo("answer");
    assertThat(turns.read("chat").getFirst().metadata()).containsEntry("timing","1");
    assertThat(turns.read("chat").getLast().metadata()).containsEntry("由来","テスト");
  }
  @Test void concurrentRepositoryInstancesDoNotLoseAdmissionsOrRegressTimes()throws Exception {
    prepare();var first=new SqliteSessionRepository(root);var second=new SqliteSessionRepository(root);first.accept(metadata("one"),()->{});
    try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs=new ArrayList<java.util.concurrent.Future<?>>();
      for(int i=1;i<=20;i++){int index=i;jobs.add(executor.submit(()->{
        var repository=index%2==0?first:second;repository.accept(metadata("one").touched(now.plusSeconds(index)),()->{});repository.accept(metadata("new-"+index),()->{});
      }));}
      for(var job:jobs)job.get(20,java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(first.findById("one")).contains(metadata("one").touched(now.plusSeconds(20)));
    assertThat(first.findPage(null,null,100)).hasSize(21);
  }
  @Test void failedSqliteWriteNeverDispatches()throws Exception {
    prepare();
    try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("storage.db"));var query=db.createStatement()) {
      query.execute("CREATE TRIGGER fail_session BEFORE INSERT ON sessions BEGIN SELECT RAISE(ABORT,'write failure fixture'); END");
    }
    var repository=new SqliteSessionRepository(root);
    assertThatThrownBy(()->repository.accept(metadata("one"),()->{throw new AssertionError("must not dispatch");})).isInstanceOf(IllegalStateException.class);
    assertThat(repository.findById("one")).isEmpty();
  }
  @Test void ongoingHistoryReaderDoesNotPreventIndependentTurnCommit()throws Exception {
    prepare();var turns=new SqliteConversationTurnStore(root);
    turns.start(new AgentRunContext("first","chat",root),"one",now);
    try(var reader=StorageBackup.readOnly(root.resolve("storage.db"));var query=reader.createStatement();
        var rows=query.executeQuery("SELECT record FROM turns")) {
      assertThat(rows.next()).isTrue();
      assertThatCode(()->turns.start(new AgentRunContext("second","chat",root),"two",now.plusNanos(1))).doesNotThrowAnyException();
    }
    assertThat(new SqliteConversationTurnStore(root).read("chat")).hasSize(2);
  }
  @Test void hundredThousandSessionsImportInChildJvmWithSixtyFourMiBHeap()throws Exception {
    Path source=root.resolve("sessions.json");
    try(var generator=StorageDatabase.JSON.getFactory().createGenerator(Files.newOutputStream(source))) {
      generator.writeStartArray();for(int i=0;i<100000;i++)StorageDatabase.JSON.writeValue(generator,metadata("session-"+i));generator.writeEndArray();
    }
    String original=StorageBackup.hash(source);Path arguments=root.resolve("bulk.args");
    String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
    Files.writeString(arguments,"-Xmx64m\n-cp\n"+javaArgument(classpath)+"\n"+StorageSessionImportWorker.class.getName()+"\n"+javaArgument(root.toString())+"\n");
    var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"@"+arguments)
        .redirectErrorStream(true).redirectOutput(root.resolve("bulk.log").toFile()).start();
    try {
      assertThat(child.waitFor(120,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(child.exitValue()).describedAs(Files.readString(root.resolve("bulk.log"))).isZero();
      assertThat(Files.readString(root.resolve("bulk.log"))).contains("IMPORTED 100000");
    }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS);}}
    assertThat(StorageBackup.hash(source)).isEqualTo(original);
  }
  @Test void interruptedLegacyTemporaryFileIsRetainedButNeverPublishedAsCommittedTurns()throws Exception {
    var legacy=new ConversationTurnStore(root);legacy.start(new AgentRunContext("committed","chat",root),"kept",now);
    Path temporary=root.resolve("state/turns/turns-123456789.tmp");Files.writeString(temporary,"uncommitted interrupted write");
    Path backup;
    try(var migration=new StorageMigrationCoordinator(root)){backup=migration.prepare().backup();}
    assertThat(new SqliteConversationTurnStore(root).read("chat")).extracting(ConversationTurnStore.Turn::runId).containsExactly("committed");
    assertThat(Files.readString(temporary)).isEqualTo("uncommitted interrupted write");
    assertThat(Files.readString(backup.resolve("files/state/turns/turns-123456789.tmp"))).isEqualTo("uncommitted interrupted write");
  }
  private static String javaArgument(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
  @Test void processKilledAfterRowsImportLeavesVersionOneAndRetriesWithoutDuplicates()throws Exception {
    try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("storage.db"));var query=db.createStatement()) {
      query.execute("CREATE TABLE storage_migrations(version INTEGER PRIMARY KEY,status TEXT NOT NULL,backup TEXT,completed TEXT NOT NULL)");
      query.execute("INSERT INTO storage_migrations VALUES(1,'COMPLETE',NULL,'2026-10-01')");query.execute("PRAGMA user_version=1");
    }
    Path source=root.resolve("sessions.json");
    try(var generator=StorageDatabase.JSON.getFactory().createGenerator(Files.newOutputStream(source))) {
      generator.writeStartArray();for(int i=0;i<15000;i++)StorageDatabase.JSON.writeValue(generator,metadata("session-"+i));generator.writeEndArray();
    }
    String original=StorageBackup.hash(source);Path arguments=root.resolve("crash.args");
    Files.writeString(arguments,"-cp\n"+javaArgument(System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")))+"\n"+StorageMigrationCrashWorker.class.getName()+"\n"+javaArgument(root.toString())+"\nrows-imported\n");
    var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"@"+arguments).redirectErrorStream(true).start();
    try {
      var ready=java.util.concurrent.CompletableFuture.supplyAsync(()->{
        try(var reader=child.inputReader()){String line;while((line=reader.readLine())!=null)if(line.equals("APPLYING"))return true;return false;}
        catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
      });
      assertThat(ready.get(60,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(()->new StorageMigrationCoordinator(root)).isInstanceOf(java.io.IOException.class);
    }finally{child.destroyForcibly();assertThat(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    assertThat(StorageMigrationCoordinatorTest.version(root.resolve("storage.db"))).isEqualTo(1);
    prepare();
    long count=new StorageDatabase(root).read(connection->{try(var query=connection.createStatement();var rows=query.executeQuery("SELECT count(*) FROM sessions")){rows.next();return rows.getLong(1);}});
    assertThat(count).isEqualTo(15000);assertThat(StorageBackup.hash(source)).isEqualTo(original);
    prepare();assertThat(new SqliteSessionRepository(root).findById("session-14999")).isPresent();
  }
  @Test void springStartupAutomaticallyImportsBeforeRepositoriesServeAndRestartUsesSqlite()throws Exception {
    new FileSessionRepository(root.resolve("sessions.json")).accept(metadata("one"),()->{});
    for(int i=0;i<2;i++)try(var context=new AnnotationConfigApplicationContext()) {
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("storage",Map.of("rei.data-dir",root.toString())));
      context.register(Config.class);context.refresh();
      assertThat(context.getBean(SessionRepository.class)).isInstanceOf(SqliteSessionRepository.class);
      assertThat(context.getBean(ConversationTurnStore.class)).isInstanceOf(SqliteConversationTurnStore.class);
      assertThat(context.getBean(SessionRepository.class).findById("one")).contains(metadata("one"));
    }
  }
}
