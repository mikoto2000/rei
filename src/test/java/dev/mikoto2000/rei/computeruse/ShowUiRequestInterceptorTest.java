package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 表示表示要求Interceptorの結果と状態の契約を検証するテスト。 */
class ShowUiRequestInterceptorTest {
  /** 検証に使う保存や受け渡しに用いるデータ形式の変換。 */
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper();

  /** その他項目一覧を保持することを確認する。 */
  @Test
  void rewritesOnlyTheIsolatedShowUiRequestAndPreservesOtherFields() throws Exception {
    var input =
        json.writeValueAsBytes(
            Map.of(
                "model",
                "showui-local",
                "max_tokens",
                128,
                "messages",
                List.of(
                    Map.of(
                        "role",
                        "user",
                        "content",
                        List.of(
                            Map.of(
                                "type",
                                "text",
                                "text",
                                ShowUiRequestInterceptor.INSTRUCTION + "\n保存ボタン"),
                            Map.of(
                                "type",
                                "image_url",
                                "image_url",
                                Map.of("url", "data:image/png;base64,aA==")))))));
    var output = json.readTree(ShowUiRequestInterceptor.rewriteBody(input));
    assertEquals("showui-local", output.path("model").asText());
    assertEquals(128, output.path("max_tokens").asInt());
    var content = output.path("messages").get(0).path("content");
    assertEquals(3, content.size());
    assertEquals(ShowUiRequestInterceptor.INSTRUCTION, content.get(0).path("text").asText());
    assertEquals(
        "data:image/png;base64,aA==", content.get(1).path("image_url").path("url").asText());
    assertEquals("保存ボタン", content.get(2).path("text").asText());
  }

  /** 画面操作の支援で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void unrelatedAndAlreadyOrderedRequestsAreUnchanged() throws Exception {
    for (String input :
        List.of(
            "{\"messages\":[]}",
            "{\"model\":\"vision\",\"messages\":[{\"role\":\"user\",\"content\":\"ordinary request\"}]}")) {
      var bytes = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      assertSame(bytes, ShowUiRequestInterceptor.rewriteBody(bytes));
    }
    var bytes =
        json.writeValueAsBytes(
            Map.of(
                "messages",
                List.of(
                    Map.of(
                        "role",
                        "user",
                        "content",
                        List.of(
                            Map.of("type", "text", "text", ShowUiRequestInterceptor.INSTRUCTION),
                            Map.of(
                                "type",
                                "image_url",
                                "image_url",
                                Map.of("url", "data:image/png;base64,aA==")),
                            Map.of("type", "text", "text", "Save"))))));
    assertSame(bytes, ShowUiRequestInterceptor.rewriteBody(bytes));
  }
}
