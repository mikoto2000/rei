package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import dev.mikoto2000.rei.llm.OpenAiSdkConfiguration;

@org.junit.jupiter.api.Tag("integration")
class EmbeddingEndpointTest {
  @Test void embeddingUsesItsOwnEndpointCredentialsAndPath() {
    verify("https://embedding.invalid/custom/embeddings", "embedding-key",
        "REI_OPENAI_EMBEDDING_BASE_URL=https://embedding.invalid",
        "REI_OPENAI_EMBEDDING_API_KEY=embedding-key",
        "REI_OPENAI_EMBEDDING_PATH=/custom/embeddings");
  }

  @Test void emptyEmbeddingSettingsUseCommonConnection() {
    verify("https://chat.invalid/v1/embeddings", "chat-key",
        "REI_OPENAI_EMBEDDING_BASE_URL=", "REI_OPENAI_EMBEDDING_API_KEY=");
  }

  private void verify(String endpoint, String key, String... properties) {
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    OpenAiHttpClientBuilderCustomizer transport = builder -> builder.interceptor(chain -> {
      var request = chain.request();
      requests.incrementAndGet();
      assertEquals(endpoint, request.url().toString());
      assertEquals("Bearer " + key, request.header("Authorization"));
      var body = new okio.Buffer();
      request.body().writeTo(body);
      assertEquals("embedding-model", new com.fasterxml.jackson.databind.ObjectMapper()
          .readTree(body.readByteArray()).path("model").asText());
      return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
          .code(200).message("OK").body(okhttp3.ResponseBody.create("""
            {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],
             "model":"embedding-model","usage":{"prompt_tokens":1,"total_tokens":1}}
            """, okhttp3.MediaType.get("application/json"))).build();
    });
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(OpenAiEmbeddingAutoConfiguration.class))
        .withUserConfiguration(OpenAiSdkConfiguration.class)
        .withBean("recordingTransport", OpenAiHttpClientBuilderCustomizer.class, () -> transport)
        .withPropertyValues("spring.ai.openai.base-url=https://chat.invalid",
            "spring.ai.openai.api-key=chat-key", "REI_OPENAI_EMBEDDING_MODEL=embedding-model",
            "logging.file.name=target/embedding-endpoint-test.log")
        .withPropertyValues(properties)
        .run(context -> assertArrayEquals(new float[] {0.1f, 0.2f},
            context.getBean(EmbeddingModel.class).embed("document")));
    assertEquals(1, requests.get());
  }
}
