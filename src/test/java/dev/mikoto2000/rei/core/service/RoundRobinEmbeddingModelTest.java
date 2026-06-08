package dev.mikoto2000.rei.core.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;

class RoundRobinEmbeddingModelTest {

  @Test
  void callDelegatesInRoundRobinOrder() {
    EmbeddingModel first = mock(EmbeddingModel.class);
    EmbeddingModel second = mock(EmbeddingModel.class);
    EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);
    RoundRobinEmbeddingModel model = new RoundRobinEmbeddingModel(List.of(first, second));

    model.call(request);
    model.call(request);
    model.call(request);

    verify(first, org.mockito.Mockito.times(2)).call(request);
    verify(second).call(request);
  }
}
