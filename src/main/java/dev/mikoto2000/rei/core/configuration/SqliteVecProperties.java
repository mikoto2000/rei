package dev.mikoto2000.rei.core.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SQLiteベクトルの設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rei.sqlite-vec")
public class SqliteVecProperties {

  /** 形式のバージョン。 */
  private String version = "0.1.9";

  /** 自動取得。 */
  private boolean autoDownload = true;

  /** キャッシュDir。 */
  private String cacheDir =
      dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
          .resolve("extensions/sqlite-vec")
          .toString();

  /** 拡張子パス。 */
  private String extensionPath;

  /** 解放接続先の基準URL。 */
  private String releaseBaseUrl = "https://github.com/asg017/sqlite-vec/releases/download";
}
