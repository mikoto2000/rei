package dev.mikoto2000.rei.core.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 開始AI互換モデル一覧のユースケースを実現する。 */
@Service
public class OpenAiCompatibleModelsService {

  /** 接続先の基準URL。 */
  private final String baseUrl;

  /** APIキー。 */
  private final String apiKey;

  /** 指定された値や利用先を使って、OpenAiCompatibleModelsServiceを初期化する。 */
  public OpenAiCompatibleModelsService(
      @Value("${spring.ai.openai.base-url}") String baseUrl,
      @Value("${spring.ai.openai.api-key:}") String apiKey) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
  }

  /** 利用可能な対象を確認できるよう、モデル一覧を一覧にする。 */
  public List<String> listModels() {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(modelsUri()).header("Accept", "application/json").GET();

    if (apiKey != null && !apiKey.isBlank()) {
      builder.header("Authorization", "Bearer " + apiKey);
    }

    try {
      HttpResponse<String> response =
          java.net.http.HttpClient.newHttpClient()
              .send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("モデル一覧の取得に失敗しました: HTTP " + response.statusCode());
      }
      return parseModelIds(response.body());
    } catch (IOException e) {
      throw new IllegalStateException("モデル一覧の取得に失敗しました", e);
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new IllegalStateException("モデル一覧の取得に失敗しました", e);
    }
  }

  /** 入力を機能内で扱えるよう、モデル識別子一覧を解釈する。 */
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

  /** 対話から使う共通操作で用いるモデル一覧URIを返す。 */
  URI modelsUri() {
    return modelsUri(baseUrl);
  }

  /** 対話から使う共通操作で用いるモデル一覧URIを返す。 */
  static URI modelsUri(String baseUrl) {
    String normalizedBaseUrl =
        baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    if (normalizedBaseUrl.endsWith("/v1")) {
      return URI.create(normalizedBaseUrl + "/models");
    }
    return URI.create(normalizedBaseUrl + "/v1/models");
  }
}
