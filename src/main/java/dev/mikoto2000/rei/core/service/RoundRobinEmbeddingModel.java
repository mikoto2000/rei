package dev.mikoto2000.rei.core.service;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

public class RoundRobinEmbeddingModel implements EmbeddingModel {

  private final RoundRobinSelector<EmbeddingModel> selector;

  public RoundRobinEmbeddingModel(List<EmbeddingModel> delegates) {
    this.selector = new RoundRobinSelector<>(delegates);
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    return selector.next().call(request);
  }

  @Override
  public float[] embed(Document document) {
    return selector.next().embed(document);
  }

  @Override
  public String getEmbeddingContent(Document document) {
    return selector.first().getEmbeddingContent(document);
  }

  @Override
  public int dimensions() {
    return selector.first().dimensions();
  }

  public int delegateCount() {
    return selector.size();
  }
}
