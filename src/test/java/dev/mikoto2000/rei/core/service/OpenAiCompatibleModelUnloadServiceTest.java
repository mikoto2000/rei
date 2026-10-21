package dev.mikoto2000.rei.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** 開始AI互換モデルUnloadサービスの結果と状態の契約を検証するテスト。 */
class OpenAiCompatibleModelUnloadServiceTest {

  /** 生成URIについて、API生成を利用することを確認する。 */
  @Test
  void generateUriUsesApiGenerateWhenV1Missing() {
    assertEquals(
        URI.create("https://api.example.com/api/generate"),
        OpenAiCompatibleModelUnloadService.generateUri("https://api.example.com"));
  }

  /** 対話から使う共通操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void generateUriStripsV1Suffix() {
    assertEquals(
        URI.create("https://api.example.com/api/generate"),
        OpenAiCompatibleModelUnloadService.generateUri("https://api.example.com/v1"));
  }
}
