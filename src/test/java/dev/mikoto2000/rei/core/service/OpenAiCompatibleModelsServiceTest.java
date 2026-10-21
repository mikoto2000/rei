package dev.mikoto2000.rei.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 開始AI互換モデル一覧サービスの結果と状態の契約を検証するテスト。 */
class OpenAiCompatibleModelsServiceTest {

  /** 対話から使う共通操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void modelsUriAddsV1WhenMissing() {
    assertEquals(
        URI.create("https://api.example.com/v1/models"),
        OpenAiCompatibleModelsService.modelsUri("https://api.example.com"));
  }

  /** 対話から使う共通操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void modelsUriUsesExistingV1Path() {
    assertEquals(
        URI.create("https://api.example.com/v1/models"),
        OpenAiCompatibleModelsService.modelsUri("https://api.example.com/v1"));
  }

  /** 対話から使う共通操作で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void parseModelIdsReturnsSortedIds() {
    String json =
        """
        {
          "data": [
            {"id": "gpt-4.1"},
            {"id": "gpt-4o-mini"},
            {"id": "o3-mini"}
          ]
        }
        """;

    assertEquals(
        List.of("gpt-4.1", "gpt-4o-mini", "o3-mini"),
        OpenAiCompatibleModelsService.parseModelIds(json));
  }
}
