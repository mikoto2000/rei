package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ProfileEventLogStore;

class ProfileCommandTest {

  @TempDir
  Path tempDir;

  @Test
  void summaryPrintsCountsAndDurations() {
    ProfileEventLogStore store = store();
    AgentEventFactory events = new AgentEventFactory(
        Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo")));
    store.append(events.runStarted("run-1", "chat", null));
    store.append(events.runCompleted("run-1", 250L));

    String output = capture(() -> new ProfileCommand(store).printSummary());

    assertThat(output).contains("events: 2");
    assertThat(output).contains("agent.run.started 1");
    assertThat(output).contains("agent.run.completed 1");
    assertThat(output).contains("avg=250");
  }

  @Test
  void chartPrintsBuckets() {
    ProfileEventLogStore store = store();
    AgentEventFactory events = new AgentEventFactory(
        Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo")));
    store.append(events.messageStarted("message-1", "assistant"));

    String output = capture(() -> new ProfileCommand(store).printChart(60L, 10));

    assertThat(output).contains("|");
    assertThat(output).contains("#");
    assertThat(output).contains("1");
  }

  @Test
  void mermaidPrintsXyChart() {
    ProfileEventLogStore store = store();
    AgentEventFactory events = new AgentEventFactory(
        Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo")));
    store.append(events.messageStarted("message-1", "assistant"));

    String output = capture(() -> new ProfileCommand(store).printMermaid(10));

    assertThat(output).contains("```mermaid");
    assertThat(output).contains("xychart-beta");
    assertThat(output).contains("message.started");
  }

  private ProfileEventLogStore store() {
    return new ProfileEventLogStore(tempDir.resolve(".rei").resolve("profile.log"),
        new ObjectMapper().registerModule(new JavaTimeModule()));
  }

  private String capture(Runnable runnable) {
    PrintStream previous = System.out;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
      runnable.run();
      return output.toString(StandardCharsets.UTF_8);
    } finally {
      System.setOut(previous);
    }
  }
}
