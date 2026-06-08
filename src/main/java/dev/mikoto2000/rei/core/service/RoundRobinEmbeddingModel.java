package dev.mikoto2000.rei.core.service;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

public class RoundRobinEmbeddingModel implements EmbeddingModel {

  private final RoundRobinSelector<Delegate> selector;

  public RoundRobinEmbeddingModel(List<EmbeddingModel> delegates) {
    this(delegates.stream().map(delegate -> new Delegate(delegate, null)).toList(), true);
  }

  public RoundRobinEmbeddingModel(List<Delegate> delegates, boolean ignored) {
    this.selector = new RoundRobinSelector<>(delegates);
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    Delegate delegate = selector.next();
    return delegate.model().call(withModel(request, delegate.modelName()));
  }

  @Override
  public float[] embed(Document document) {
    return selector.next().model().embed(document);
  }

  @Override
  public String getEmbeddingContent(Document document) {
    return selector.first().model().getEmbeddingContent(document);
  }

  @Override
  public int dimensions() {
    return selector.first().model().dimensions();
  }

  public int delegateCount() {
    return selector.size();
  }

  private EmbeddingRequest withModel(EmbeddingRequest request, String modelName) {
    if (modelName == null || modelName.isBlank()) {
      return request;
    }
    org.springframework.ai.embedding.EmbeddingOptions options = request.getOptions();
    OpenAiEmbeddingOptions openAiOptions = new OpenAiEmbeddingOptions();
    if (options instanceof OpenAiEmbeddingOptions existing) {
      openAiOptions.setEncodingFormat(existing.getEncodingFormat());
      openAiOptions.setDimensions(existing.getDimensions());
      openAiOptions.setUser(existing.getUser());
    } else if (options != null) {
      openAiOptions.setDimensions(options.getDimensions());
    }
    openAiOptions.setModel(modelName);
    return new EmbeddingRequest(request.getInstructions(), openAiOptions);
  }

  public record Delegate(EmbeddingModel model, String modelName) {
  }
}
