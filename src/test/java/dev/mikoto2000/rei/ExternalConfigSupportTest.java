package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 外部設定補助の結果と状態の契約を検証するテスト。 */
class ExternalConfigSupportTest {

  /** 追加場所について、任意外部アプリケーションYAML形式の内容を利用することを確認する。 */
  @Test
  void additionalLocationUsesOptionalExternalApplicationYaml() {
    Path workDirectory = Path.of("/work/rei");
    String expected = "optional:file:" + workDirectory.resolve("application.yaml");

    assertEquals(expected, ExternalConfigSupport.additionalLocation(workDirectory));
  }
}
