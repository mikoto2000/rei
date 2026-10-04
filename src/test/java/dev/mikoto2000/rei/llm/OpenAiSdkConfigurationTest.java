package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

class OpenAiSdkConfigurationTest {
  @Test
  void endpointNormalizationRetainsPrefixAndAvoidsDuplicateVersion() {
    assertThat(OpenAiCompatibleEndpoint.baseUrl("http://ollama.local:11434/")).isEqualTo("http://ollama.local:11434/v1");
    assertThat(OpenAiCompatibleEndpoint.baseUrl("https://proxy.test/openai/")).isEqualTo("https://proxy.test/openai/v1");
    assertThat(OpenAiCompatibleEndpoint.baseUrl("https://proxy.test/openai/v1/")).isEqualTo("https://proxy.test/openai/v1");
    assertThat(OpenAiCompatibleEndpoint.baseUrl("")).isEmpty();
    assertThat(OpenAiCompatibleEndpoint.baseUrl(null)).isNull();
  }

  @Test
  void boundConnectionOptionsKeepLegacyWirePathsAndConfiguredModels() {
    runner().run(context -> {
      assertThat(context).hasNotFailed();
      var chat = context.getBean(OpenAiChatModel.class);
      assertThat(chat.getOptions().getModel()).isEqualTo("local-chat");
      assertThat(chat.call(new Prompt("hello")).getResult().getOutput().getText()).isEqualTo("hello");
      var image = context.getBean(OpenAiImageModel.class);
      assertThat(image.getOptions().getModel()).isEqualTo("local-image");
      assertThat(image.call(new ImagePrompt("cat")).getResult().getOutput().getB64Json()).isEqualTo("aW1hZ2U=");
      assertThat(context.getBean(OpenAiEmbeddingModel.class).embed("document")).containsExactly(0.1f, 0.2f);
      assertThat(context.getBean(Requests.class).urls).containsExactly(
          "https://provider.invalid/prefix/v1/chat/completions",
          "https://provider.invalid/prefix/v1/images/generations",
          "https://embedding.invalid/proxy/custom/embeddings");
      assertThat(context.getBean(Requests.class).bodies.get(0)).contains("\"model\":\"local-chat\"");
      assertThat(context.getBean(Requests.class).bodies.get(1)).contains("\"model\":\"local-image\"");
    });
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration.class,
            OpenAiChatAutoConfiguration.class,
            OpenAiEmbeddingAutoConfiguration.class, OpenAiImageAutoConfiguration.class))
        .withUserConfiguration(OpenAiSdkConfiguration.class, RecordingConfiguration.class)
        .withPropertyValues("spring.ai.openai.api-key=dummy-key",
            "spring.ai.openai.base-url=https://provider.invalid/prefix/",
            "spring.ai.openai.chat.options.model=local-chat",
            "spring.ai.openai.image.options.model=local-image",
            "spring.ai.openai.embedding.options.model=local-embedding",
            "spring.ai.openai.embedding.base-url=https://embedding.invalid/proxy/v1/",
            "spring.ai.openai.embedding.embeddings-path=/custom/embeddings");
  }

  static class Requests {
    final List<String> urls = new ArrayList<>();
    final List<String> bodies = new ArrayList<>();
  }

  @Configuration(proxyBeanMethods = false)
  static class RecordingConfiguration {
    @Bean Requests requests() { return new Requests(); }
    @Bean @Order(Ordered.LOWEST_PRECEDENCE)
    OpenAiHttpClientBuilderCustomizer recordRequests(Requests requests) {
      return builder -> builder.interceptor(chain -> {
        var request = chain.request();
        requests.urls.add(request.url().toString());
        var buffer = new okio.Buffer();
        request.body().writeTo(buffer);
        requests.bodies.add(buffer.readUtf8());
        String path = request.url().encodedPath();
        String response = path.endsWith("chat/completions")
            ? "{\"id\":\"chat\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"local-chat\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}"
            : path.endsWith("images/generations")
                ? "{\"created\":0,\"data\":[{\"b64_json\":\"aW1hZ2U=\"}]}"
                : "{\"object\":\"list\",\"model\":\"local-embedding\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";
        return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200).message("OK").body(okhttp3.ResponseBody.create(response,
                okhttp3.MediaType.get("application/json"))).build();
      });
    }
  }
}
