package dev.mikoto2000.rei.testsupport;

import java.nio.file.Path;
import dev.mikoto2000.rei.core.configuration.SqliteVecProperties;
import dev.mikoto2000.rei.core.sqlitevec.PlatformDetector;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecAssetResolver;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecInstaller;
import tools.jackson.databind.json.JsonMapper;

/** Share only the version/platform-specific native binary; databases remain per-test. */
public final class SqliteVecTestExtension {
  private SqliteVecTestExtension() {}

  public static synchronized Path resolve() {
    var properties = new SqliteVecProperties();
    properties.setCacheDir(Path.of("target", "sqlite-vec-test-cache").toAbsolutePath().toString());
    return new SqliteVecInstaller(properties, new PlatformDetector(),
        new SqliteVecAssetResolver(), new JsonMapper()).resolveExtensionPath();
  }
}
