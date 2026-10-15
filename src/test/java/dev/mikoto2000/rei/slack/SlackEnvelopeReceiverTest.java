package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

class SlackEnvelopeReceiverTest {
  @Test
  void botReplyIsDurableBeforeAckAndNeverUsesHumanAdmission() {
    var runtime = mock(SlackRuntime.class);
    var agents = mock(SlackAgentService.class);
    var receiver = new SlackEnvelopeReceiver(runtime, "T", agents);
    String raw = envelope("T").replace("\"user\":\"U\"", "\"user\":\"U\",\"bot_id\":\"B\"");
    assertEquals("envelope", receiver.receive(raw));
    verify(agents).receive(any());
    verifyNoInteractions(runtime);
    when(agents.receive(any())).thenThrow(new IllegalStateException("DB failed"));
    assertNull(receiver.receive(raw));
    verifyNoInteractions(runtime);
  }

  private String envelope(String team) {
    return """
    {"type":"events_api","envelope_id":"envelope","payload":{"type":"event_callback","event_id":"E","team_id":"%s","event":{"type":"message","channel":"D","channel_type":"im","ts":"1","user":"U","text":"hello"}}}
    """
        .formatted(team);
  }

  @Test
  void persistsBeforeAcknowledgementAndWithholdsAckOnFailure() {
    var runtime = mock(SlackRuntime.class);
    var receiver = new SlackEnvelopeReceiver(runtime, "T");
    assertEquals("envelope", receiver.receive(envelope("T")));
    verify(runtime).accept(any());
    when(runtime.accept(any())).thenThrow(new IllegalStateException("persistence failed"));
    assertNull(receiver.receive(envelope("T")));
  }

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
