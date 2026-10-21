package dev.mikoto2000.rei.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.LlmRequestStartedPayload;
import dev.mikoto2000.rei.llm.LlmFeature;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;

/** エージェントイベント画像モデルの結果と状態の契約を検証するテスト。 */
class AgentEventImageModelTest {

  /** 画像の生成で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void publishesImageGenerationLifecycle() {
    ImageModel delegate = mock(ImageModel.class);
    ImagePrompt prompt = new ImagePrompt("draw a cat");
    ImageResponse response = mock(ImageResponse.class);
    when(delegate.call(prompt)).thenReturn(response);
    List<AgentEvent> events = new ArrayList<>();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);

    ImageResponse actual =
        new AgentEventImageModel(
                LlmFeature.IMAGE_GENERATION,
                delegate,
                new AgentEventFactory(Clock.systemUTC()),
                bus)
            .call(prompt);

    assertEquals(response, actual);
    assertEquals(AgentEventType.LLM_REQUEST_STARTED, events.get(0).type());
    assertEquals(
        LlmFeature.IMAGE_GENERATION,
        ((LlmRequestStartedPayload) events.get(0).payload()).feature());
    assertEquals(AgentEventType.LLM_RESPONSE_COMPLETED, events.get(1).type());
  }

  /** 画像の生成で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void doesNotPublishResponseWhenImageGenerationFails() {
    ImageModel delegate = mock(ImageModel.class);
    ImagePrompt prompt = new ImagePrompt("draw a cat");
    when(delegate.call(prompt)).thenThrow(new IllegalStateException("boom"));
    List<AgentEvent> events = new ArrayList<>();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);
    AgentEventImageModel model =
        new AgentEventImageModel(
            LlmFeature.IMAGE_GENERATION, delegate, new AgentEventFactory(Clock.systemUTC()), bus);

    assertThrows(IllegalStateException.class, () -> model.call(prompt));
    assertEquals(AgentEventType.LLM_REQUEST_STARTED, events.getFirst().type());
    assertFalse(
        events.stream().anyMatch(event -> event.type() == AgentEventType.LLM_RESPONSE_COMPLETED));
  }
}
