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
}
