package dev.mikoto2000.rei.core.configuration;

import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import dev.mikoto2000.rei.core.service.RoundRobinChatModel;
import dev.mikoto2000.rei.core.service.RoundRobinEmbeddingModel;
import io.micrometer.observation.ObservationRegistry;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReiOpenAiProperties.class)
@ConditionalOnProperty(prefix = "rei.openai", name = "round-robin-enabled", havingValue = "true")
public class OpenAiRoundRobinConfiguration {

  @Bean
  ChatModel roundRobinChatModel(
      ReiOpenAiProperties reiOpenAiProperties,
      OpenAiConnectionProperties connectionProperties,
      OpenAiChatProperties chatProperties,
      ToolCallingManager toolCallingManager,
      ObjectProvider<RetryTemplate> retryTemplate,
      ObjectProvider<ObservationRegistry> observationRegistry,
      ObjectProvider<ResponseErrorHandler> responseErrorHandler) {
    List<ChatModel> delegates = baseUrls(reiOpenAiProperties, connectionProperties).stream()
        .map(baseUrl -> chatModel(
            baseUrl,
            connectionProperties,
            chatProperties,
            toolCallingManager,
            retryTemplate,
            observationRegistry,
            responseErrorHandler))
        .map(ChatModel.class::cast)
        .toList();
    return new RoundRobinChatModel(delegates);
  }

  @Bean
  EmbeddingModel roundRobinEmbeddingModel(
      ReiOpenAiProperties reiOpenAiProperties,
      OpenAiConnectionProperties connectionProperties,
      OpenAiEmbeddingProperties embeddingProperties,
      ObjectProvider<RetryTemplate> retryTemplate,
      ObjectProvider<ObservationRegistry> observationRegistry,
      ObjectProvider<ResponseErrorHandler> responseErrorHandler) {
    List<EmbeddingModel> delegates = baseUrls(reiOpenAiProperties, connectionProperties).stream()
        .map(baseUrl -> embeddingModel(
            baseUrl,
            connectionProperties,
            embeddingProperties,
            retryTemplate,
            observationRegistry,
            responseErrorHandler))
        .map(EmbeddingModel.class::cast)
        .toList();
    return new RoundRobinEmbeddingModel(delegates);
  }

  private OpenAiEmbeddingModel embeddingModel(
      String baseUrl,
      OpenAiConnectionProperties connectionProperties,
      OpenAiEmbeddingProperties embeddingProperties,
      ObjectProvider<RetryTemplate> retryTemplate,
      ObjectProvider<ObservationRegistry> observationRegistry,
      ObjectProvider<ResponseErrorHandler> responseErrorHandler) {
    OpenAiApi openAiApi = openAiApi(
        baseUrl,
        connectionProperties.getApiKey(),
        "/v1/chat/completions",
        embeddingProperties.getEmbeddingsPath(),
        responseErrorHandler);
    MetadataMode metadataMode = embeddingProperties.getMetadataMode() == null ? MetadataMode.EMBED : embeddingProperties.getMetadataMode();
    RetryTemplate retry = retryTemplate.getIfUnique();
    ObservationRegistry registry = observationRegistry.getIfUnique();
    if (retry != null && registry != null) {
      return new OpenAiEmbeddingModel(openAiApi, metadataMode, embeddingProperties.getOptions(), retry, registry);
    }
    if (retry != null) {
      return new OpenAiEmbeddingModel(openAiApi, metadataMode, embeddingProperties.getOptions(), retry);
    }
    return new OpenAiEmbeddingModel(openAiApi, metadataMode, embeddingProperties.getOptions());
  }

  private OpenAiChatModel chatModel(
      String baseUrl,
      OpenAiConnectionProperties connectionProperties,
      OpenAiChatProperties chatProperties,
      ToolCallingManager toolCallingManager,
      ObjectProvider<RetryTemplate> retryTemplate,
      ObjectProvider<ObservationRegistry> observationRegistry,
      ObjectProvider<ResponseErrorHandler> responseErrorHandler) {
    OpenAiChatModel.Builder builder = OpenAiChatModel.builder()
        .openAiApi(openAiApi(
            baseUrl,
            connectionProperties.getApiKey(),
            chatProperties.getCompletionsPath(),
            "/v1/embeddings",
            responseErrorHandler))
        .defaultOptions(chatProperties.getOptions())
        .toolCallingManager(toolCallingManager);
    RetryTemplate retry = retryTemplate.getIfUnique();
    if (retry != null) {
      builder.retryTemplate(retry);
    }
    ObservationRegistry registry = observationRegistry.getIfUnique();
    if (registry != null) {
      builder.observationRegistry(registry);
    }
    return builder.build();
  }

  private OpenAiApi openAiApi(
      String baseUrl,
      String apiKey,
      String completionsPath,
      String embeddingsPath,
      ObjectProvider<ResponseErrorHandler> responseErrorHandler) {
    return OpenAiApi.builder()
        .baseUrl(baseUrl)
        .apiKey(apiKey)
        .completionsPath(completionsPath)
        .embeddingsPath(embeddingsPath)
        .restClientBuilder(RestClient.builder())
        .webClientBuilder(WebClient.builder())
        .responseErrorHandler(responseErrorHandler.getIfAvailable())
        .build();
  }

  private List<String> baseUrls(ReiOpenAiProperties reiOpenAiProperties, OpenAiConnectionProperties connectionProperties) {
    List<String> configured = reiOpenAiProperties.getBaseUrls() == null ? List.of() : reiOpenAiProperties.getBaseUrls().stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::strip)
        .toList();
    if (!configured.isEmpty()) {
      return configured;
    }
    return List.of(connectionProperties.getBaseUrl());
  }
}
