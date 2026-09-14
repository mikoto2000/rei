package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo(endpoint)).andExpect(header("Authorization", "Bearer " + key))
        .andExpect(jsonPath("$.model").value("embedding-model"))
        .andRespond(withSuccess("""
            {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],
             "model":"embedding-model","usage":{"prompt_tokens":1,"total_tokens":1}}
            """, MediaType.APPLICATION_JSON));
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(OpenAiEmbeddingAutoConfiguration.class))
        .withBean(RestClient.Builder.class, () -> builder)
        .withPropertyValues("spring.ai.openai.base-url=https://chat.invalid",
            "spring.ai.openai.api-key=chat-key", "REI_OPENAI_EMBEDDING_MODEL=embedding-model",
            "logging.file.name=target/embedding-endpoint-test.log")
        .withPropertyValues(properties)
        .run(context -> assertArrayEquals(new float[] {0.1f, 0.2f},
            context.getBean(EmbeddingModel.class).embed("document")));
    server.verify();
  }
}
