package dev.mikoto2000.rei.storage;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import dev.mikoto2000.rei.core.command.StorageCommand;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class StorageRetentionCommandTest {
  @TempDir Path root;
  @Test void commandPlansApprovesAndShowsHistoryWithoutModifyingBodies()throws Exception {
    try(var migration=new StorageMigrationCoordinator(root)){migration.prepare();}
    var registry=new StorageObjectRegistry(root,Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"),ZoneOffset.UTC));
    Path body=Files.createDirectories(root.resolve("logs/activity-archives")).resolve(UUID.randomUUID()+".jsonl");Files.writeString(body,"record");
    registry.registerProducedFile(body,"ACTIVITY_RAW",null,null,Instant.parse("2026-01-01T00:00:00Z"),true,List.of());
    var planner=new RetentionPlanner(registry);var command=new CommandLine(new StorageCommand(planner));var output=new StringWriter();command.setOut(new PrintWriter(output));command.setErr(new PrintWriter(output));
    assertThat(command.execute("retention","plan","--category","activity-raw","--max-objects","1","--max-bytes","100")).isZero();
    assertThat(output.toString()).contains("recoverable_bytes=6","scope=global","candidate");String plan=planner.history().getFirst().id();
    assertThat(command.execute("retention","approve",plan)).isZero();assertThat(command.execute("retention","history")).isZero();
    assertThat(output.toString()).contains("approved",plan);assertThat(Files.readString(body)).isEqualTo("record");
  }
  @Test void unknownOrMissingProjectCannotFallBackToGlobalPlan()throws Exception {
    try(var migration=new StorageMigrationCoordinator(root)){migration.prepare();}var planner=new RetentionPlanner(new StorageObjectRegistry(root));
    var command=new CommandLine(new StorageCommand(planner));command.setOut(new PrintWriter(new StringWriter()));command.setErr(new PrintWriter(new StringWriter()));
    assertThat(command.execute("retention","plan","--project","current")).isEqualTo(2);
    assertThat(command.execute("retention","plan","--project","all")).isEqualTo(2);assertThat(planner.history()).isEmpty();
  }
  @Test void commandAppliesRestoresAndSeparatelyApprovesPurgeAndAutomaticConsent()throws Exception {
    try(var migration=new StorageMigrationCoordinator(root)){migration.prepare();}var clock=new RetentionExecutionTest.MutableClock();var registry=new StorageObjectRegistry(root,clock);var planner=new RetentionPlanner(registry);var executor=new RetentionExecutor(registry,planner);var automatic=new AutomaticRetention(registry,planner,executor);
    Path body=Files.createDirectories(root.resolve("logs/activity-archives")).resolve(UUID.randomUUID()+".jsonl");Files.writeString(body,"diagnostic");registry.registerProducedFile(body,"ACTIVITY_RAW",null,null,clock.instant().minus(Duration.ofDays(100)),true,List.of());
    var command=new CommandLine(new StorageCommand(planner,executor,new StorageMaintenance(registry),automatic));var output=new StringWriter();command.setOut(new PrintWriter(output));command.setErr(new PrintWriter(output));
    var plan=planner.plan("activity-raw",null,1,100);assertThat(command.execute("retention","apply",plan.id())).isEqualTo(2);assertThat(body).exists();
    assertThat(command.execute("retention","approve",plan.id())).isZero();assertThat(command.execute("retention","apply",plan.id())).isZero();String execution=executor.history().getFirst().id();assertThat(body).doesNotExist();
    assertThat(command.execute("retention","restore",execution)).isZero();assertThat(body).exists();
    plan=planner.plan("activity-raw",null,1,100);planner.approve(plan.id());execution=executor.apply(plan.id()).id();clock.time=clock.time.plus(Duration.ofDays(8));
    assertThat(command.execute("retention","purge",execution)).isEqualTo(2);assertThat(command.execute("retention","approve-purge",execution)).isZero();assertThat(command.execute("retention","purge",execution)).isZero();
    assertThat(command.execute("retention","auto","propose","--category","activity-raw","--max-objects","1","--max-bytes","100","--interval-seconds","600")).isZero();String consent=automatic.history().getFirst().id();
    assertThat(output.toString()).contains("retention_seconds=7776000","interval_seconds=600","max_bytes=100","permanent_purge=false");
    assertThat(command.execute("retention","auto","approve",consent)).isZero();assertThat(command.execute("retention","auto","disable",consent)).isZero();
    assertThat(command.execute("maintenance","status")).isZero();assertThat(output.toString()).contains("free_pages=","used_page_bytes=");
  }
}
