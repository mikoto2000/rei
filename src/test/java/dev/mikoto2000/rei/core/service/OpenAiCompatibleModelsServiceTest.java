package dev.mikoto2000.rei.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

class OpenAiCompatibleModelsServiceTest {

  @Test
  void modelsUriAddsV1WhenMissing() {
    assertEquals(URI.create("https://api.example.com/v1/models"),
        OpenAiCompatibleModelsService.modelsUri("https://api.example.com"));
  }

  @Test
  void modelsUriUsesExistingV1Path() {
    assertEquals(URI.create("https://api.example.com/v1/models"),
        OpenAiCompatibleModelsService.modelsUri("https://api.example.com/v1"));
  }

  @Test
  void parseModelIdsReturnsSortedIds() {
    String json = """
        {
          "data": [
            {"id": "gpt-4.1"},
            {"id": "gpt-4o-mini"},
            {"id": "o3-mini"}
          ]
        }
        """;

    assertEquals(List.of("gpt-4.1", "gpt-4o-mini", "o3-mini"), OpenAiCompatibleModelsService.parseModelIds(json));
  }
}
