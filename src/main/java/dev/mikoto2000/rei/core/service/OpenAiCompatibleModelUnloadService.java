package dev.mikoto2000.rei.core.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OpenAiCompatibleModelUnloadService {

  private final String baseUrl;
  private final String apiKey;

  public OpenAiCompatibleModelUnloadService(
      @Value("${spring.ai.openai.base-url}") String baseUrl,
      @Value("${spring.ai.openai.api-key:}") String apiKey) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
  }

  public void unload(String modelName) {
    String payload = "{\"model\":\"" + escapeJson(modelName) + "\",\"keep_alive\":0}";
    HttpRequest.Builder builder = HttpRequest.newBuilder(unloadUri())
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload));

    if (apiKey != null && !apiKey.isBlank()) {
      builder.header("Authorization", "Bearer " + apiKey);
    }

    try {
      HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
          .send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("model unload failed: HTTP " + response.statusCode());
      }
    } catch (IOException e) {
      throw new IllegalStateException("model unload failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("model unload interrupted", e);
    }
  }

  URI unloadUri() {
    return generateUri(baseUrl);
  }

  static URI generateUri(String baseUrl) {
    String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    if (normalizedBaseUrl.endsWith("/v1")) {
      return URI.create(normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 3) + "/api/generate");
    }
    return URI.create(normalizedBaseUrl + "/api/generate");
  }

  private static String escapeJson(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
