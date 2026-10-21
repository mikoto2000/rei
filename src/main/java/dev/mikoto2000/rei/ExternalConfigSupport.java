package dev.mikoto2000.rei;

import dev.mikoto2000.rei.core.datasource.ReiPaths;
import java.nio.file.Path;
import java.util.Map;

/** 外部設定で共通に利用する補助処理。 */
final class ExternalConfigSupport {

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ExternalConfigSupport() {}

  /** れいの起動で用いる既定設定を返す。 */
  static Map<String, Object> defaultProperties() {
    return Map.of(
        "spring.config.additional-location",
        additionalLocation(dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()),
        "rei.data-dir",
        dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().toString());
  }

  /** れいの起動で用いる既定設定を返す。 */
  static Map<String, Object> defaultProperties(Path workDirectory) {
    return Map.of("spring.config.additional-location", additionalLocation(workDirectory));
  }

  /** れいの起動で用いる追加場所を返す。 */
  static String additionalLocation(Path workDirectory) {
    return "optional:file:" + ReiPaths.configFilePath(workDirectory);
  }
}
