package dev.mikoto2000.rei.core;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import java.nio.file.*;
import java.util.List;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("integration")
class RepositoryMapCompilerIsolationTest {
  @TempDir Path root;
  private static final String STARTED = "rei.test.repository-map.plugin-started";

  // A real auto-start compiler plugin in the host classpath must never run during source observation.
  public static final class UnexpectedPlugin implements Plugin {
    public UnexpectedPlugin() { System.setProperty("rei.test.repository-map.plugin-started", "constructed"); }
    @Override public String getName() { return "UnexpectedRepositoryMapPlugin"; }
    @Override public boolean autoStart() { return true; }
    @Override public void init(JavacTask task, String... args) { System.setProperty("rei.test.repository-map.plugin-started", "started"); }
  }

  @Test void repositoryScanDoesNotDiscoverOrRunHostCompilerPlugins() throws Exception {
    withPluginClasspath(() -> {
      var view = new RepositoryMapService(path -> List.of("App.java")).map(root, "", 10);
      assertEquals("PARSED", view.items().getFirst().status());
      assertEquals(List.of("missing.External"), view.items().getFirst().imports());
      assertNull(System.getProperty(STARTED), "Source indexing must not execute host compiler plugins");
    });
  }

  @Test void singleFileSnapshotDoesNotDiscoverOrRunHostCompilerPlugins() throws Exception {
    withPluginClasspath(() -> {
      var snapshots = new FileSnapshots();
      var source = snapshots.get(root, Path.of("App.java"));
      var file = new RepositoryMapService(path -> List.of("App.java")).describeSnapshot(root, source);
      assertEquals("PARSED", file.status());
      assertNull(System.getProperty(STARTED), "Symbol reads must not execute host compiler plugins");
    });
  }

  @FunctionalInterface private interface Check { void run() throws Exception; }
  private void withPluginClasspath(Check check) throws Exception {
    Files.writeString(root.resolve("App.java"), "import missing.External; class App { External value; }");
    String provider = UnexpectedPlugin.class.getName();
    String entry = provider.replace('.', '/') + ".class";
    Path jar = root.resolve("host-plugin.jar");
    try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
      output.putNextEntry(new JarEntry(entry));
      try (var input = getClass().getClassLoader().getResourceAsStream(entry)) { assertNotNull(input); input.transferTo(output); }
      output.closeEntry();
      output.putNextEntry(new JarEntry("META-INF/services/com.sun.source.util.Plugin"));
      output.write((provider + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    }
    String classpath = System.getProperty("java.class.path"), started = System.getProperty(STARTED);
    try {
      System.setProperty("java.class.path", jar.toString()); System.clearProperty(STARTED);
      check.run();
    } finally {
      if (classpath == null) System.clearProperty("java.class.path"); else System.setProperty("java.class.path", classpath);
      if (started == null) System.clearProperty(STARTED); else System.setProperty(STARTED, started);
    }
  }
}
