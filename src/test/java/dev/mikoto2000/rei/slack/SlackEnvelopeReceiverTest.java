package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/** Slack共通の通知属性Receiverの結果と状態の契約を検証するテスト。 */
class SlackEnvelopeReceiverTest {
  /** Slackとの連携で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void botReplyIsDurableBeforeAckAndNeverUsesHumanAdmission() {
    var runtime = mock(SlackRuntime.class);
    var agents = mock(SlackAgentService.class);
    var receiver = new SlackEnvelopeReceiver(runtime, "T", agents);
    String raw = envelope("T").replace("\"user\":\"U\"", "\"user\":\"U\",\"bot_id\":\"B\"");
    assertEquals("envelope", receiver.receive(raw));
    verify(agents).receive(any());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(runtime);
    when(agents.receive(any())).thenThrow(new IllegalStateException("DB failed"));
    assertNull(receiver.receive(raw));
    verifyNoInteractions(runtime);
  }

  /** {@code envelope} の条件を再現するための補助情報を返す。 */
  private String envelope(String team) {
    return """
    {"type":"events_api","envelope_id":"envelope","payload":{"type":"event_callback","event_id":"E","team_id":"%s","event":{"type":"message","channel":"D","channel_type":"im","ts":"1","user":"U","text":"hello"}}}
    """
        .formatted(team);
  }

  /** Slackとの連携で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void persistsBeforeAcknowledgementAndWithholdsAckOnFailure() {
    var runtime = mock(SlackRuntime.class);
    var receiver = new SlackEnvelopeReceiver(runtime, "T");
    assertEquals("envelope", receiver.receive(envelope("T")));
    verify(runtime).accept(any());
    when(runtime.accept(any())).thenThrow(new IllegalStateException("persistence failed"));
    assertNull(receiver.receive(envelope("T")));
  }

  /** Slackとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void wrongWorkspaceAndUnsupportedEventsNeverReachRun() {
    var runtime = mock(SlackRuntime.class);
    var receiver = new SlackEnvelopeReceiver(runtime, "T");
    assertEquals("envelope", receiver.receive(envelope("other")));
    verifyNoInteractions(runtime);
    assertNull(receiver.receive("{\"type\":\"hello\"}"));
    verifyNoInteractions(runtime);
  }
}
