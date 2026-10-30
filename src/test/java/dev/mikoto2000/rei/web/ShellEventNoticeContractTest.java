package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventPayload;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.EventJsonCodec;
import dev.mikoto2000.rei.ui.shell.ShellAgentEventRenderer;
import dev.mikoto2000.rei.ui.shell.ShellEventOutput;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** CLI と Shell が同じ golden data を使い、表示項目の配信漏れも検出する。 */
class ShellEventNoticeContractTest {
  @Test
  void shellAndPublicPayloadMatchTheCliNoticeContract() throws Exception {
    var mapper = EventJsonCodec.mapper();
    var samples =
        mapper.readTree(
            Path.of("terminal/cli/src/test/resources/shell-event-notices.json").toFile());
    for (var sample : samples) {
      var payload = (ObjectNode) sample.path("payload").deepCopy();
      payload.put("_payloadType", sample.path("payloadType").asText());
      var event =
          new AgentEvent(
              "event",
              1,
              Instant.parse("2026-10-30T00:00:00Z"),
              AgentEventType.from(sample.path("type").asText()),
              1,
              "session",
              "turn",
              "run",
              null,
              null,
              mapper.treeToValue(payload, AgentEventPayload.class),
              "project1");
      var lines = new ArrayList<String>();
      var renderer =
          new ShellAgentEventRenderer(
              new ShellEventOutput() {
                public void print(String value) {
                  fail("Notice unexpectedly became a stream: " + event.type());
                }

                public void println(String value) {
                  lines.add(value);
                }

                public void flush() {}
              });
      renderer.onEvent(event);
      renderer.finish();
      var expected = new ArrayList<String>();
      sample.path("lines").forEach(line -> expected.add(line.asText()));
      assertEquals(expected, lines, event.type().value());
      var dto = WebApiEventDto.from(event, false, "");
      for (var field : sample.path("requiredFields"))
        assertTrue(dto.payload().containsKey(field.asText()), event.type() + " missing " + field);
    }
  }
}
