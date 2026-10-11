package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.ArrayList;

class SseDecoderTest {
  @Test void decodesMultilineDataAndIgnoresHeartbeatAndDuplicateSequence()throws Exception {
    var events=new ArrayList<SseDecoder.Event>();
    new SseDecoder(3).read(new StringReader(": keepalive\r\n\r\nid: 3\ndata: duplicate\n\nid: 4\nevent: agent.output\ndata: {\ndata: }\n\nevent: heartbeat\ndata: {}\n\n"),events::add);
    assertEquals(1,events.size());assertEquals(4,events.getFirst().sequence());assertEquals("{\n}",events.getFirst().data());
  }
  @Test void boundsAnUnterminatedEvent() {
    assertThrows(java.io.IOException.class,()->new SseDecoder(0).read(new StringReader("data: "+"x".repeat(1048577)),event->{}));
  }
}
