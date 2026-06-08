package dev.mikoto2000.rei.core.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.mockito.ArgumentCaptor;

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

  @Test
  void callOverridesRequestModelWithSelectedDelegateModel() {
    EmbeddingModel first = mock(EmbeddingModel.class);
    EmbeddingModel second = mock(EmbeddingModel.class);
    EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);
    RoundRobinEmbeddingModel model = new RoundRobinEmbeddingModel(List.of(
        new RoundRobinEmbeddingModel.Delegate(first, "embed-a"),
        new RoundRobinEmbeddingModel.Delegate(second, "embed-b")),
        true);

    model.call(request);
    model.call(request);

    ArgumentCaptor<EmbeddingRequest> firstRequest = ArgumentCaptor.forClass(EmbeddingRequest.class);
    ArgumentCaptor<EmbeddingRequest> secondRequest = ArgumentCaptor.forClass(EmbeddingRequest.class);
    verify(first).call(firstRequest.capture());
    verify(second).call(secondRequest.capture());
    org.junit.jupiter.api.Assertions.assertEquals("embed-a", firstRequest.getValue().getOptions().getModel());
    org.junit.jupiter.api.Assertions.assertEquals("embed-b", secondRequest.getValue().getOptions().getModel());
  }
}
