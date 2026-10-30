package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class RunEventNoticesTest {
  @Test
  void noDefinedEventFallsBackToRawJson() {
    for (var type : dev.mikoto2000.rei.event.AgentEventType.values()) {
      var lines = new ArrayList<String>();
      var output = new RunEventOutput(lines::add);
      var payload = BackendClient.JSON.createObjectNode();
      payload.put("unrecognizedPrivateField", "must never appear");
      output.accept(type.value(), payload);
      output.flush();
      assertFalse(String.join("\n", lines).contains("unrecognizedPrivateField"), type.value());
      assertFalse(String.join("\n", lines).contains("must never appear"), type.value());
    }
  }

  @Test
  void noticesMatchTheShellGoldenData() throws Exception {
    try (var input = getClass().getResourceAsStream("/shell-event-notices.json")) {
      assertNotNull(input);
      for (var sample : BackendClient.JSON.readTree(input)) {
        var lines = new ArrayList<String>();
        var output = new RunEventOutput(lines::add);
        var event = BackendClient.JSON.createObjectNode();
        event.put("projectId", "project1");
        event.put("timestamp", "2026-10-30T00:00:00Z");
        event.set("payload", sample.path("payload"));
        output.acceptEvent(sample.path("type").asText(), event);
        output.flush();
        var expected = new ArrayList<String>();
        sample.path("lines").forEach(line -> expected.add(line.asText()));
        assertEquals(expected, lines, sample.path("type").asText());
      }
    }
  }

  @Test
  void deferredTopicsUseEventTimeAndQuietEventsNeverInterruptAnswers() throws Exception {
    var lines = new ArrayList<String>();
    var output = new RunEventOutput(lines::add);
    var start = Instant.parse("2026-10-30T00:00:00Z");
    output.accept(
        "message.delta",
        BackendClient.JSON.readTree("{\"messageId\":\"m\",\"delta\":\"回答\"}"),
        start);
    output.accept(
        "topic.spoken",
        BackendClient.JSON.readTree("{\"candidateId\":\"t\",\"messageId\":\"m\"}"),
        start);
    output.accept(
        "topic.auto_speak.suppressed",
        BackendClient.JSON.readTree("{\"reason\":\"busy\"}"),
        start.plusSeconds(10));
    output.accept("task.started", BackendClient.JSON.readTree("{\"internal\":\"private\"}"), start);
    output.accept(
        "message.completed",
        BackendClient.JSON.readTree("{\"messageId\":\"m\",\"text\":\"回答\"}"),
        start);
    output.accept(
        "topic.auto_speak.suppressed",
        BackendClient.JSON.readTree("{\"reason\":\"busy\"}"),
        start.plusSeconds(1800));
    assertEquals(4, lines.size());
    assertEquals("回答", lines.get(1));
    assertEquals("[topic] spoken: t -> m", lines.get(2));
    assertEquals("[topic] auto speak suppressed: busy", lines.get(3));
  }
}
