package dev.mikoto2000.rei.testsupport;

import dev.mikoto2000.rei.core.configuration.SqliteVecProperties;
import dev.mikoto2000.rei.core.sqlitevec.PlatformDetector;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecAssetResolver;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecInstaller;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/**
 * 検証条件を再現するためのSQLiteベクトル検証拡張子。
 *
 * <p>Share only the version/platform-specific native binary; databases remain per-test.
 */
public final class SqliteVecTestExtension {
  /** 検証条件に必要な値を受け取り、SqliteVecTestExtensionを用意する。 */
  private SqliteVecTestExtension() {}

  /** {@code resolve} の条件を再現するための補助情報を返す。 */
  public static synchronized Path resolve() {
    var properties = new SqliteVecProperties();
    properties.setCacheDir(Path.of("target", "sqlite-vec-test-cache").toAbsolutePath().toString());
    return new SqliteVecInstaller(
            properties, new PlatformDetector(), new SqliteVecAssetResolver(), new JsonMapper())
        .resolveExtensionPath();
  }
}
