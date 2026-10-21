package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** イベント配信受信形式の解釈の結果と状態の契約を検証するテスト。 */
class SseDecoderTest {
  /** 軽量CLIでの対話で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void decodesMultilineDataAndIgnoresHeartbeatAndDuplicateSequence() throws Exception {
    var events = new ArrayList<SseDecoder.Event>();
    new SseDecoder(3)
        .read(
            new StringReader(
                ": keepalive\r\n\r\nid: 3\ndata: duplicate\n\nid: 4\nevent: agent.output\ndata: {\ndata: }\n\nevent: heartbeat\ndata: {}\n\n"),
            events::add);
    assertEquals(1, events.size());
    assertEquals(4, events.getFirst().sequence());
    assertEquals("{\n}", events.getFirst().data());
  }

  /** 軽量CLIでの対話で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void boundsAnUnterminatedEvent() {
    assertThrows(
        java.io.IOException.class,
        () ->
            new SseDecoder(0).read(new StringReader("data: " + "x".repeat(1048577)), event -> {}));
  }
}
