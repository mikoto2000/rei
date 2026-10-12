package dev.mikoto2000.rei;

import java.io.IOException;
import java.util.Properties;

import picocli.CommandLine.IVersionProvider;

/** Backend version, available before Spring starts and in the legacy Shell. */
public final class ReiVersionProvider implements IVersionProvider {
  private final ClassLoader classLoader;
  private final String implementationVersion;

  public ReiVersionProvider() {
    this(ReiVersionProvider.class.getClassLoader(), ReiVersionProvider.class.getPackage().getImplementationVersion());
  }

  ReiVersionProvider(ClassLoader classLoader, String implementationVersion) {
    this.classLoader = classLoader;
    this.implementationVersion = implementationVersion;
  }

  @Override
  public String[] getVersion() {
    String version = buildVersion();
    if (version == null) version = nonBlank(implementationVersion);
    return new String[] {"rei " + (version == null ? "development (version unknown)" : version)};
  }

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

  private static String nonBlank(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
