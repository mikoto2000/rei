package dev.mikoto2000.rei.image;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.image.ImageModel;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import io.micrometer.observation.ObservationRegistry;

@Component
public class ImageModelProvider {

  private final ObjectProvider<ImageModel> defaultImageModelProvider;
  private final LlmProperties properties;
  private final ImageProperties imageProperties;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;
  private final Map<String, ImageModel> cache = new ConcurrentHashMap<>();

  @Autowired
  public ImageModelProvider(ObjectProvider<ImageModel> defaultImageModelProvider, LlmProperties properties,
      ImageProperties imageProperties, AgentEventFactory eventFactory, AgentEventPublisher eventPublisher) {
    this.defaultImageModelProvider = defaultImageModelProvider;
    this.properties = properties;
    this.imageProperties = imageProperties;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  ImageModelProvider(ImageModel defaultImageModel, LlmProperties properties) {
    this(new FixedObjectProvider<>(defaultImageModel), properties, new ImageProperties(), null, null);
  }

  public ImageModel imageModel() {
    return cache.computeIfAbsent(LlmFeature.IMAGE_GENERATION, ignored -> createImageModel());
  }

  private ImageModel createImageModel() {
    ImageModel defaultImageModel = defaultImageModel();
    LlmProperties.Server server = properties.feature(LlmFeature.IMAGE_GENERATION);
    ImageModel model = server == null || !server.hasCustomServer()
        ? defaultImageModel
        : new FallbackImageModel(LlmFeature.IMAGE_GENERATION, createOpenAiCompatibleImageModel(server),
            defaultImageModel, server.getModel());
    return eventFactory == null || eventPublisher == null
        ? model
        : new AgentEventImageModel(LlmFeature.IMAGE_GENERATION, model, eventFactory, eventPublisher);
  }

  public String model(String overrideModel) {
    if (overrideModel != null && !overrideModel.isBlank()) {
      return overrideModel;
    }
    LlmProperties.Server server = properties.feature(LlmFeature.IMAGE_GENERATION);
    if (server != null && server.getModel() != null && !server.getModel().isBlank()) {
      return server.getModel();
    }
    return configuredModel(defaultImageModel());
  }

  static String configuredModel(ImageModel model) {
    return model instanceof OpenAiImageModel openAi && openAi.getOptions() != null
        ? openAi.getOptions().getModel() : null;
  }

  private ImageModel defaultImageModel() {
    ImageModel imageModel = defaultImageModelProvider.getIfAvailable();
    if (imageModel == null) {
      throw new IllegalStateException("画像生成用 ImageModel が構成されていません");
    }
    return imageModel;
  }

  private ImageModel createOpenAiCompatibleImageModel(LlmProperties.Server server) {
    OpenAiImageOptions.Builder options = OpenAiImageOptions.builder()
        .baseUrl(dev.mikoto2000.rei.llm.OpenAiCompatibleEndpoint.baseUrl(server.getBaseUrl()))
        .apiKey(server.getApiKey() == null || server.getApiKey().isBlank() ? "dummy-key" : server.getApiKey())
        .timeout(Duration.ofSeconds(Math.max(1, imageProperties.getTimeoutSeconds())));
    String model = model(null);
    if (model != null && !model.isBlank()) {
      options.model(model);
    }
    return OpenAiImageModel.builder().options(options.build())
        .observationRegistry(ObservationRegistry.NOOP).build();
  }

  private record FixedObjectProvider<T>(T value) implements ObjectProvider<T> {
    @Override
    public T getObject(Object... args) {
      return value;
    }

    @Override
    public T getIfAvailable() {
      return value;
    }

    @Override
    public T getIfUnique() {
      return value;
    }

    @Override
    public T getObject() {
      return value;
    }
  }
}
