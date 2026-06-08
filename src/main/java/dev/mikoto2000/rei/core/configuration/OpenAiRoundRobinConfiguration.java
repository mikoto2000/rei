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
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
  @Primary
  ChatModel roundRobinChatModel(
      ReiOpenAiProperties reiOpenAiProperties,
      OpenAiConnectionProperties connectionProperties,
      OpenAiChatProperties chatProperties,
      ToolCallingManager toolCallingManager,
      ObjectProvider<RetryTemplate> retryTemplate,
      ObjectProvider<ObservationRegistry> observationRegistry,
      ObjectProvider<ResponseErrorHandler> responseErrorHandler) {
    List<RoundRobinChatModel.Delegate> delegates = servers(reiOpenAiProperties, connectionProperties).stream()
        .map(server -> new RoundRobinChatModel.Delegate(chatModel(
            server.baseUrl(),
            server.chatModel(),
            connectionProperties,
            chatProperties,
            toolCallingManager,
            retryTemplate,
            observationRegistry,
            responseErrorHandler),
            server.chatModel()))
        .toList();
    return new RoundRobinChatModel(delegates, true);
  }

  @Bean
  @Primary
  EmbeddingModel roundRobinEmbeddingModel(
      ReiOpenAiProperties reiOpenAiProperties,
      OpenAiConnectionProperties connectionProperties,
      OpenAiEmbeddingProperties embeddingProperties,
      ObjectProvider<RetryTemplate> retryTemplate,
      ObjectProvider<ObservationRegistry> observationRegistry,
      ObjectProvider<ResponseErrorHandler> responseErrorHandler) {
    List<RoundRobinEmbeddingModel.Delegate> delegates = servers(reiOpenAiProperties, connectionProperties).stream()
        .map(server -> new RoundRobinEmbeddingModel.Delegate(embeddingModel(
            server.baseUrl(),
            server.embeddingModel(),
            connectionProperties,
            embeddingProperties,
            retryTemplate,
            observationRegistry,
            responseErrorHandler),
            server.embeddingModel()))
        .toList();
    return new RoundRobinEmbeddingModel(delegates, true);
  }

  private OpenAiEmbeddingModel embeddingModel(
      String baseUrl,
      String embeddingModel,
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
    OpenAiEmbeddingOptions options = embeddingOptions(embeddingProperties.getOptions(), embeddingModel);
    if (retry != null && registry != null) {
      return new OpenAiEmbeddingModel(openAiApi, metadataMode, options, retry, registry);
    }
    if (retry != null) {
      return new OpenAiEmbeddingModel(openAiApi, metadataMode, options, retry);
    }
    return new OpenAiEmbeddingModel(openAiApi, metadataMode, options);
  }

  private OpenAiChatModel chatModel(
      String baseUrl,
      String chatModel,
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
        .defaultOptions(chatOptions(chatProperties.getOptions(), chatModel))
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

  private OpenAiChatOptions chatOptions(OpenAiChatOptions baseOptions, String model) {
    OpenAiChatOptions options = baseOptions == null ? OpenAiChatOptions.builder().build() : baseOptions.copy();
    if (model != null && !model.isBlank()) {
      options.setModel(model);
    }
    return options;
  }

  private OpenAiEmbeddingOptions embeddingOptions(OpenAiEmbeddingOptions baseOptions, String model) {
    OpenAiEmbeddingOptions options = new OpenAiEmbeddingOptions();
    if (baseOptions != null) {
      options.setModel(baseOptions.getModel());
      options.setEncodingFormat(baseOptions.getEncodingFormat());
      options.setDimensions(baseOptions.getDimensions());
      options.setUser(baseOptions.getUser());
    }
    if (model != null && !model.isBlank()) {
      options.setModel(model);
    }
    return options;
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

  private List<Server> servers(ReiOpenAiProperties reiOpenAiProperties, OpenAiConnectionProperties connectionProperties) {
    List<Server> configuredServers = reiOpenAiProperties.getServers() == null ? List.of() : reiOpenAiProperties.getServers().stream()
        .filter(server -> server != null && server.getBaseUrl() != null && !server.getBaseUrl().isBlank())
        .map(server -> new Server(server.getBaseUrl().strip(), blankToNull(server.getChatModel()), blankToNull(server.getEmbeddingModel())))
        .toList();
    if (!configuredServers.isEmpty()) {
      return configuredServers;
    }
    List<Server> configuredUrls = reiOpenAiProperties.getBaseUrls() == null ? List.of() : reiOpenAiProperties.getBaseUrls().stream()
        .filter(value -> value != null && !value.isBlank())
        .map(value -> new Server(value.strip(), null, null))
        .toList();
    if (!configuredUrls.isEmpty()) {
      return configuredUrls;
    }
    return List.of(new Server(connectionProperties.getBaseUrl(), null, null));
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private record Server(String baseUrl, String chatModel, String embeddingModel) {
  }
}
