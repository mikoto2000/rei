package dev.mikoto2000.rei.core.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 開始AI互換モデルUnloadのユースケースを実現する。 */
@Service
public class OpenAiCompatibleModelUnloadService {

  /** 接続先の基準URL。 */
  private final String baseUrl;

  /** APIキー。 */
  private final String apiKey;

  /** 指定された値や利用先を使って、OpenAiCompatibleModelUnloadServiceを初期化する。 */
  public OpenAiCompatibleModelUnloadService(
      @Value("${spring.ai.openai.base-url}") String baseUrl,
      @Value("${spring.ai.openai.api-key:}") String apiKey) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
  }

  /** 対話から使う共通操作に関するunloadを処理する。 */
  public void unload(String modelName) {
    String payload = "{\"model\":\"" + escapeJson(modelName) + "\",\"keep_alive\":0}";
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(unloadUri())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload));

    if (apiKey != null && !apiKey.isBlank()) {
      builder.header("Authorization", "Bearer " + apiKey);
    }

    try {
      HttpResponse<String> response =
          java.net.http.HttpClient.newHttpClient()
              .send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("model unload failed: HTTP " + response.statusCode());
      }
    } catch (IOException e) {
      throw new IllegalStateException("model unload failed", e);
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new IllegalStateException("model unload interrupted", e);
    }
  }

  /** 対話から使う共通操作で用いるunloadURIを返す。 */
  URI unloadUri() {
    return generateUri(baseUrl);
  }

  /** 依頼された内容を提供するため、参照先のURIを生成する。 */
  static URI generateUri(String baseUrl) {
    String normalizedBaseUrl =
        baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    if (normalizedBaseUrl.endsWith("/v1")) {
      return URI.create(
          normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 3) + "/api/generate");
    }
    return URI.create(normalizedBaseUrl + "/api/generate");
  }

  /** 対話から使う共通操作で用いる特殊文字の保護JSON変換を返す。 */
  private static String escapeJson(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
