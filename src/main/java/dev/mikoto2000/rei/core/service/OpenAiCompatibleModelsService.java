package dev.mikoto2000.rei.core.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@Service
public class OpenAiCompatibleModelsService {

  private final String baseUrl;
  private final String apiKey;

  public OpenAiCompatibleModelsService(
      @Value("${spring.ai.openai.base-url}") String baseUrl,
      @Value("${spring.ai.openai.api-key:}") String apiKey) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
  }

  public List<String> listModels() {
    HttpRequest.Builder builder = HttpRequest.newBuilder(modelsUri())
        .header("Accept", "application/json")
        .GET();

    if (apiKey != null && !apiKey.isBlank()) {
      builder.header("Authorization", "Bearer " + apiKey);
    }

    try {
      HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
          .send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("モデル一覧の取得に失敗しました: HTTP " + response.statusCode());
      }
      return parseModelIds(response.body());
    } catch (IOException e) {
      throw new IllegalStateException("モデル一覧の取得に失敗しました", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("モデル一覧の取得に失敗しました", e);
    }
  }

  static List<String> parseModelIds(String responseBody) {
    JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
    JsonArray data = root.getAsJsonArray("data");
    if (data == null) {
      return List.of();
    }

    return data.asList().stream()
        .map(element -> element.getAsJsonObject().get("id"))
        .filter(id -> id != null && !id.isJsonNull())
        .map(id -> id.getAsString())
        .sorted(Comparator.naturalOrder())
        .toList();
  }

  URI modelsUri() {
    return modelsUri(baseUrl);
  }

  static URI modelsUri(String baseUrl) {
    String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    if (normalizedBaseUrl.endsWith("/v1")) {
      return URI.create(normalizedBaseUrl + "/models");
    }
    return URI.create(normalizedBaseUrl + "/v1/models");
  }
}
