package dev.mikoto2000.rei.llm;

import org.springframework.ai.model.openai.autoconfigure.AbstractOpenAiProperties;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 開始AISdkの構成要素を接続し、設定に従って利用できるようにする。
 *
 * <p>Compatibility for existing Rei connection settings after Spring AI's SDK migration.
 */
@Configuration(proxyBeanMethods = false)
public class OpenAiSdkConfiguration {
  /** 必要な対象を利用できるよう、AIサーバー作業ルートCompatibilityの利用を開始する。 */
  @Bean
  static BeanPostProcessor openAiServerRootCompatibility() {
    return new BeanPostProcessor() {
      /** 用途に応じたモデル利用で用いる投稿プロセス変更前初期化を返す。 */
      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof AbstractOpenAiProperties properties) {
          properties.setBaseUrl(OpenAiCompatibleEndpoint.baseUrl(properties.getBaseUrl()));
          // Rei's empty per-feature key means inherit the shared key; AI 2 treats an
          // empty key as an explicit unauthenticated connection instead.
          if (!(properties
                  instanceof
                  org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties)
              && properties.getApiKey() != null
              && properties.getApiKey().isBlank()) {
            properties.setApiKey(null);
          }
        }
        return bean;
      }
    };
  }

  /** 用途に応じたモデル利用で用いるれい開始AI外部情報を取得するHTTP通信の窓口Customizerを返す。 */
  @Bean
  @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
  OpenAiHttpClientBuilderCustomizer reiOpenAiHttpClientCustomizer(Environment environment) {
    String embeddingPath =
        environment.getProperty("spring.ai.openai.embedding.embeddings-path", "/v1/embeddings");
    return builder -> {
      builder.interceptor(new ChatStreamTimeoutInterceptor());
      builder.interceptor(new ShowUiSdkRequestInterceptor());
      if (embeddingPath != null
          && !embeddingPath.isBlank()
          && !embeddingPath.equals("/v1/embeddings")) {
        builder.interceptor(
            chain -> {
              var request = chain.request();
              String path = request.url().encodedPath();
              String suffix = "/v1/embeddings";
              if (path.endsWith(suffix)) {
                String prefix = path.substring(0, path.length() - suffix.length());
                String replacement =
                    embeddingPath.startsWith("/") ? embeddingPath : "/" + embeddingPath;
                request =
                    request
                        .newBuilder()
                        .url(request.url().newBuilder().encodedPath(prefix + replacement).build())
                        .build();
              }
              return chain.proceed(request);
            });
      }
    };
  }
}
