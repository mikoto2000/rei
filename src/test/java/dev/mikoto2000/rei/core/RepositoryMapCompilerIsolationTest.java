package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 保存と検索の窓口対応表コンパイラー作業の分離の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class RepositoryMapCompilerIsolationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う開始。 */
  private static final String STARTED = "rei.test.repository-map.plugin-started";

  // A real auto-start compiler plugin in the host classpath must never run during source
  // observation.
  /** 検証条件を再現するための想定外のプラグイン。 */
  public static final class UnexpectedPlugin implements Plugin {
    /** 検証条件に必要な値を受け取り、UnexpectedPluginを用意する。 */
    public UnexpectedPlugin() {
      System.setProperty("rei.test.repository-map.plugin-started", "constructed");
    }

    /** 検証用に、共通の実行基盤で参照する名前を返す。 */
    @Override
    public String getName() {
      return "UnexpectedRepositoryMapPlugin";
    }

    /** {@code autoStart} の条件を再現するための補助情報を返す。 */
    @Override
    public boolean autoStart() {
      return true;
    }

    /** {@code init} の条件を再現するため、検証用の操作や状態を扱う。 */
    @Override
    public void init(JavacTask task, String... args) {
      System.setProperty("rei.test.repository-map.plugin-started", "started");
    }
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void repositoryScanDoesNotDiscoverOrRunHostCompilerPlugins() throws Exception {
    withPluginClasspath(
        () -> {
          var view = new RepositoryMapService(path -> List.of("App.java")).map(root, "", 10);
          assertEquals("PARSED", view.items().getFirst().status());
          assertEquals(List.of("missing.External"), view.items().getFirst().imports());
          assertNull(
              System.getProperty(STARTED),
              "Source indexing must not execute host compiler plugins");
        });
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void singleFileSnapshotDoesNotDiscoverOrRunHostCompilerPlugins() throws Exception {
    withPluginClasspath(
        () -> {
          var snapshots = new FileSnapshots();
          var source = snapshots.get(root, Path.of("App.java"));
          var file =
              new RepositoryMapService(path -> List.of("App.java")).describeSnapshot(root, source);
          assertEquals("PARSED", file.status());
          assertNull(
              System.getProperty(STARTED), "Symbol reads must not execute host compiler plugins");
        });
  }

  /** 検証条件を再現するための確認。 */
  @FunctionalInterface
  private interface Check {
    /** 検証用に、共通の実行基盤の操作を実行する。 */
    void run() throws Exception;
  }

  /** 検証用に、指定されたプラグインClasspathを反映した情報を作成する。 */
  private void withPluginClasspath(Check check) throws Exception {
    Files.writeString(
        root.resolve("App.java"), "import missing.External; class App { External value; }");
    String provider = UnexpectedPlugin.class.getName();
    String entry = provider.replace('.', '/') + ".class";
    Path jar = root.resolve("host-plugin.jar");
    try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
      output.putNextEntry(new JarEntry(entry));
      try (var input = getClass().getClassLoader().getResourceAsStream(entry)) {
        assertNotNull(input);
        input.transferTo(output);
      }
      output.closeEntry();
      output.putNextEntry(new JarEntry("META-INF/services/com.sun.source.util.Plugin"));
      output.write((provider + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    }
    String classpath = System.getProperty("java.class.path"), started = System.getProperty(STARTED);
    try {
      System.setProperty("java.class.path", jar.toString());
      System.clearProperty(STARTED);
      check.run();
    } finally {
      if (classpath == null) System.clearProperty("java.class.path");
      else System.setProperty("java.class.path", classpath);
      if (started == null) System.clearProperty(STARTED);
      else System.setProperty(STARTED, started);
    }
  }
}
