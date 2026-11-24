package dev.mikoto2000.rei.llm.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ToolDiscoveryWireTest {
  @Test
  void sdkActuallySerializes32Then33DefinitionsWithoutNetwork() throws Exception {
    var execution = ToolDiscoveryLoopTest.execution("wire");
    var template = ToolDiscoveryLoopTest.prompt(execution);
    var initial =
        new org.springframework.ai.chat.prompt.Prompt(
            template.getInstructions(),
            ((org.springframework.ai.openai.OpenAiChatOptions) template.getOptions())
                .mutate()
                .toolCallbacks(ToolCatalogTest.realDefinitions())
                .build());
    var session = ToolDiscoveryLoopTest.discovery().start(initial, execution, c -> c);
    var first = session.project(initial);
    ((ToolCallingChatOptions) first.getOptions())
        .getToolCallbacks().stream()
            .filter(c -> c.getToolDefinition().name().equals("searchTools"))
            .findFirst()
            .orElseThrow()
            .call("{\"query\":\"googleCalendarListEventsForDate\",\"maxResults\":1}");
    var second = session.project(initial);
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration.class,
                OpenAiChatAutoConfiguration.class))
        .withUserConfiguration(Recording.class)
        .withPropertyValues(
            "spring.ai.openai.api-key=dummy-key",
            "spring.ai.openai.base-url=https://provider.invalid/v1",
            "spring.ai.openai.chat.options.model=test-model")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var model = context.getBean(OpenAiChatModel.class);
          model.call(initial);
          model.call(first);
          model.call(second);
          var bodies = context.getBean(Bodies.class).values;
          assertThat(bodies).hasSize(3);
          var json = new com.fasterxml.jackson.databind.ObjectMapper();
          assertThat(json.readTree(bodies.get(0)).path("tools")).hasSize(184);
          assertThat(json.readTree(bodies.get(1)).path("tools")).hasSize(32);
          assertThat(json.readTree(bodies.get(2)).path("tools")).hasSize(33);
          assertThat(bodies.get(1).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
              .isLessThan(bodies.get(0).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
          System.out.println(
              "Discovery wire bytes: baseline="
                  + bodies.get(0).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                  + ", basic="
                  + bodies.get(1).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                  + ", specialized="
                  + bodies.get(2).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        });
  }

  static class Bodies {
    final List<String> values = new ArrayList<>();
  }

  @Configuration(proxyBeanMethods = false)
  static class Recording {
    @Bean
    Bodies bodies() {
      return new Bodies();
    }

    @Bean
    OpenAiHttpClientBuilderCustomizer recording(Bodies bodies) {
      return builder ->
          builder.interceptor(chain -> {
            var request = chain.request();
            var buffer = new okio.Buffer();
            request.body().writeTo(buffer);
            bodies.values.add(buffer.readUtf8());
            return new okhttp3.Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    okhttp3.ResponseBody.create(
                        "{\"id\":\"chat\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}",
                        okhttp3.MediaType.get("application/json")))
                .build();
          });
    }
  }
}
