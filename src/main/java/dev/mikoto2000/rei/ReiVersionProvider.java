package dev.mikoto2000.rei;

import java.io.IOException;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/**
 * れい形式のバージョンを用途や設定に応じて提供する窓口。
 *
 * <p>Backend version, available before Spring starts and in the legacy Shell.
 */
public final class ReiVersionProvider implements IVersionProvider {
  /** クラス読み込みの窓口。 */
  private final ClassLoader classLoader;

  /** 実装形式のバージョン。 */
  private final String implementationVersion;

  /** れいの起動を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ReiVersionProvider() {
    this(
        ReiVersionProvider.class.getClassLoader(),
        ReiVersionProvider.class.getPackage().getImplementationVersion());
  }

  /** 指定された値や利用先を使って、ReiVersionProviderを初期化する。 */
  ReiVersionProvider(ClassLoader classLoader, String implementationVersion) {
    this.classLoader = classLoader;
    this.implementationVersion = implementationVersion;
  }

  /** れいの起動で参照する形式のバージョンを返す。 */
  @Override
  public String[] getVersion() {
    String version = buildVersion();
    if (version == null) version = nonBlank(implementationVersion);
    return new String[] {"rei " + (version == null ? "development (version unknown)" : version)};
  }

  /** 利用先に渡せるよう、形式のバージョンを組み立てる。 */
  private String buildVersion() {
    try (var input = classLoader.getResourceAsStream("META-INF/build-info.properties")) {
      if (input == null) return null;
      var properties = new Properties();
      properties.load(input);
      // The lightweight terminal client has its own artifact and version.
      if (!"dev.mikoto2000".equals(properties.getProperty("build.group"))
          || !"rei".equals(properties.getProperty("build.artifact"))) return null;
      return nonBlank(properties.getProperty("build.version"));
    } catch (IOException | IllegalArgumentException unavailable) {
      return null;
    }
  }

  /** れいの起動で用いる以外空白を返す。 */
  private static String nonBlank(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
