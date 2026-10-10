package dev.mikoto2000.rei.storage;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.event.ProfileEventLogEntry;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class ManagedActivityLogTest {
  @TempDir Path root;StorageObjectRegistry registry;
  @BeforeEach void prepare()throws Exception {try(var gate=new StorageMigrationCoordinator(root)){gate.prepare();}registry=new StorageObjectRegistry(root,Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"),ZoneOffset.UTC));}
  ProfileEventLogEntry entry(String id,String session,String run){return new ProfileEventLogEntry(id,1,registry.clock.instant(),"diagnostic",1,session,null,run,null,null,null,Map.of("日本語","保持"));}
  @Test void newSegmentsPreserveLegacyLogAndOnlyClosedOwnedBodiesCanBePlanned()throws Exception {
    Path legacy=root.resolve("logs/activity.jsonl");Files.createDirectories(legacy.getParent());Files.writeString(legacy,StorageDatabase.JSON.writeValueAsString(entry("legacy",null,null))+"\n");String hash=StorageBackup.hash(legacy);
    var log=new ManagedActivityLog(registry,1024);log.append(null,entry("new",null,null));
    var planner=new RetentionPlanner(registry);planner.updatePolicy("activity-raw",null,null,1L);assertThat(planner.plan("activity-raw",null,100,100000).candidates()).isEmpty();
    log.closeSegment(null);assertThat(log.read(null)).extracting(ProfileEventLogEntry::id).containsExactly("legacy","new");assertThat(StorageBackup.hash(legacy)).isEqualTo(hash);
    log.append(null,entry("second",null,null));log.closeSegment(null);assertThat(planner.plan("activity-raw",null,100,100000).candidates()).hasSize(1);
  }
  @Test void anySessionOrRunArchiveStaysProtectedAndProjectScopesDoNotMix()throws Exception {
    String project=UUID.randomUUID().toString();var log=new ManagedActivityLog(registry,1024);
    log.append(project,entry("project","session","run"));log.closeSegment(project);log.append(null,entry("global",null,null));log.closeSegment(null);
    assertThat(log.read(project)).extracting(ProfileEventLogEntry::id).containsExactly("project");assertThat(log.read(null)).extracting(ProfileEventLogEntry::id).containsExactly("global");
    var planner=new RetentionPlanner(registry);planner.updatePolicy("activity-raw",Duration.ofSeconds(1),null,null);
    assertThat(planner.status(project).protectionReasons()).containsKey("referenced");
  }
  @Test void changedOpenSegmentIsRetainedAndCannotBeAdoptedAsVerified()throws Exception {
    var log=new ManagedActivityLog(registry,1024);log.append(null,entry("one",null,null));Path file;
    try(var files=Files.list(root.resolve("logs/activity-archives"))){file=files.findFirst().orElseThrow();}Files.writeString(file,"externally changed");
    assertThatThrownBy(()->log.closeSegment(null)).isInstanceOf(IllegalStateException.class);assertThat(Files.readString(file)).isEqualTo("externally changed");
    assertThat(new RetentionPlanner(registry).status(null).objects()).isEmpty();
  }
  @Test void sameSizeExternalChangeIsDetectedBeforeClosingOrCollection()throws Exception {
    var log=new ManagedActivityLog(registry,1024);log.append(null,entry("one",null,null));Path file;
    try(var files=Files.list(root.resolve("logs/activity-archives"))){file=files.findFirst().orElseThrow();}String changed=Files.readString(file).replace("one","two");Files.writeString(file,changed);
    log.append(null,entry("next",null,null));assertThatThrownBy(()->log.closeSegment(null)).isInstanceOf(IllegalStateException.class);assertThat(Files.readString(file)).startsWith(changed);assertThat(new RetentionPlanner(registry).status(null).objects()).isEmpty();
  }
  @Test void failedMetadataCommitDoesNotPublishAnUncommittedArchive()throws Exception {
    registry.database.transaction(db->{try(var query=db.createStatement()){query.execute("CREATE TRIGGER fail_activity BEFORE UPDATE ON activity_segments BEGIN SELECT RAISE(ABORT,'fixture disk/write failure'); END");}return null;});
    var log=new ManagedActivityLog(registry,1024);assertThatThrownBy(()->log.append(null,entry("uncommitted",null,null))).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(()->log.read(null)).isInstanceOf(IllegalStateException.class);assertThat(new RetentionPlanner(registry).status(null).objects()).isEmpty();
  }
  @Test void springWiresProductionProfileStoreAndExecutionCommandBeforeServing()throws Exception {
    try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",Map.of("rei.data-dir",root.toString())));context.register(Config.class);context.refresh();
      var store=context.getBean(dev.mikoto2000.rei.event.ProfileEventLogStore.class);assertThat(store.file()).isEqualTo(root.resolve("logs/activity.jsonl"));
      store.append(new dev.mikoto2000.rei.event.AgentEvent("production",1,registry.clock.instant(),dev.mikoto2000.rei.event.AgentEventType.AGENT_RUN_COMPLETED,1,"session",null,"run",null,null,new dev.mikoto2000.rei.event.AgentRunCompletedPayload("run",1),null));
      assertThat(store.readAll()).extracting(ProfileEventLogEntry::id).containsExactly("production");assertThat(root.resolve("logs/activity.jsonl")).doesNotExist();assertThat(store.summarize().total()).isEqualTo(1);
      var command=new picocli.CommandLine(context.getBean(dev.mikoto2000.rei.core.command.StorageCommand.class));command.setOut(new java.io.PrintWriter(new java.io.StringWriter()));assertThat(command.execute("maintenance","status")).isZero();
    }
  }
  @org.springframework.context.annotation.Configuration
  @org.springframework.context.annotation.Import(StorageMigrationConfiguration.class)
  static class Config {
    @org.springframework.context.annotation.Bean dev.mikoto2000.rei.event.ProfileEventLogStore profile(){return new dev.mikoto2000.rei.event.ProfileEventLogStore();}
    @org.springframework.context.annotation.Bean dev.mikoto2000.rei.core.command.StorageCommand command(RetentionPlanner planner){return new dev.mikoto2000.rei.core.command.StorageCommand(planner);}
  }
}
